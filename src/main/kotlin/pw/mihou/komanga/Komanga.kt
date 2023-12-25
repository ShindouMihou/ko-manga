package pw.mihou.komanga

import com.mongodb.MongoCommandException
import com.mongodb.MongoException
import com.mongodb.MongoServerException
import com.mongodb.TransactionOptions
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bson.Document
import org.slf4j.LoggerFactory
import pw.mihou.komanga.databases.MigrationsDatabase
import pw.mihou.komanga.exceptions.DuplicateMigrationKeyException
import pw.mihou.komanga.exceptions.MongoClientNotInitializedException
import pw.mihou.komanga.interfaces.Migration
import pw.mihou.komanga.models.MigrationKind
import pw.mihou.komanga.models.MigrationModel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object Komanga {

    @Volatile var client: MongoClient? = null
    @Volatile var database: MongoDatabase? = null

    private val migrations = mutableSetOf<Migration>()
    private val mutex = ReentrantLock()

    internal val logger = LoggerFactory.getLogger(Komanga::class.java)

    fun load(vararg migrations: Migration) {
        mutex.withLock {
            for (migration in migrations) {
                if (this.migrations.any { it.name == migration.name }) {
                    throw DuplicateMigrationKeyException(migration.name)
                }

                this.migrations += migration
            }
        }
    }

    suspend fun rollback(kind: MigrationKind): Boolean {
        val useTransactions = isReplicaSet()
        val database = Komanga.database ?: throw MongoClientNotInitializedException

        val appliedMigrations =  MigrationsDatabase.collection
            .find(Filters.eq("kind", kind))
            .allowDiskUse(true)
            .map { it.id }
            .toSet()

        val copy = migrations.filter {
            it.kind == kind
                    && appliedMigrations.contains(it.name)
        }.toList()


        for (migration in copy) {
            val model = MigrationModel(id = migration.name, kind = migration.kind)
            val ack = model.delete()
            if (!ack) {
                logger.warn("Migration ${migration.name} cannot be deleted, it likely doesn't exist somehow?")
                continue
            }

            try {
                val collection = database.getCollection<Document>(migration.collectionName)
                if (useTransactions && migration.allowTransactions) {
                    val tAck = transactional {
                        migration.down(database, collection)
                    }

                    if (!tAck) {
                        logger.error("Rollback for ${migration.name} failed to complete. As this was done via transactions, " +
                                "there was no change with the data.")
                        model.delete()
                        return false
                    }
                } else {
                    try {
                        migration.down(database, collection)
                    } catch (ex: Exception) {
                        logger.error("Rollback for ${migration.name} failed to complete. As this was not done with transactions, data changes " +
                                "may have occurred.", ex)
                        model.delete()
                        return false
                    }
                }
            } catch (ex: Exception) {
                // @reason last defense capture
                logger.error("Rollback for ${migration.name} failed to complete, due to the following exception: ", ex)
                return false
            }
        }

        return true
    }

    suspend fun migrate(kind: MigrationKind): Boolean {
        val useTransactions = isReplicaSet()
        val database = Komanga.database ?: throw MongoClientNotInitializedException

        val copy = migrations.filter { it.kind == kind }.toList()
        for (migration in copy) {
            val model = MigrationModel(id = migration.name, kind = migration.kind)
            val ack = model.insert()
            if (!ack) {
                logger.warn("Migration ${migration.name} was already completed, skipping.")
                continue
            }

            try {
                val collection = database.getCollection<Document>(migration.collectionName)
                if (useTransactions && migration.allowTransactions) {
                    val tAck = transactional {
                        migration.up(database, collection)
                    }

                    if (!tAck) {
                        logger.error("Migration ${migration.name} failed to complete. As this was done via transactions, " +
                                "there was no change with the data.")
                        model.delete()
                        return false
                    }
                } else {
                    try {
                        migration.up(database, collection)
                    } catch (ex: Exception) {
                        logger.error("Migration ${migration.name} failed to complete. As this was not done with transactions, data changes " +
                                "may have occurred.", ex)
                        model.delete()
                        return false
                    }
                }
            } catch (ex: Exception) {
                // @reason last defense capture
                logger.error("Migration ${migration.name} failed to complete, due to the following exception: ", ex)
                return false
            }
        }

        return true
    }

    private suspend fun transactional(task: suspend () -> Unit): Boolean {
        val client = Komanga.client ?: throw MongoClientNotInitializedException
        val session = client.startSession()

        return try {
            session.startTransaction()
            task()
            session.commitTransaction()

            true
        } catch (ex: Exception) {
            session.abortTransaction()
            false
        } finally {
            session.close()
        }
    }

    private suspend fun isReplicaSet(): Boolean = try {
        val client = Komanga.client ?: throw MongoClientNotInitializedException
        client.getDatabase("admin").runCommand(Document("replSetGetStatus", 1))
        true
    } catch (ex: MongoServerException) {
        false
    }
}