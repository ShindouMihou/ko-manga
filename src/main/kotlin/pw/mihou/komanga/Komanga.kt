package pw.mihou.komanga

import com.mongodb.MongoServerException
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toSet
import org.bson.Document
import org.slf4j.LoggerFactory
import pw.mihou.komanga.databases.LocksDatabase
import pw.mihou.komanga.databases.MigrationsDatabase
import pw.mihou.komanga.exceptions.DuplicateMigrationKeyException
import pw.mihou.komanga.exceptions.MongoClientNotInitializedException
import pw.mihou.komanga.interfaces.Migration
import pw.mihou.komanga.locks.KoLock
import pw.mihou.komanga.models.MigrationKind
import pw.mihou.komanga.models.MigrationModel
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// @TODO Add proper documentations.
object Komanga {

    @Volatile var client: MongoClient? = null
    @Volatile var database: MongoDatabase? = null

    /**
     * [maxHoldTime] dictates how long a Lock created with this specific instance
     * can be held before it is forcibly released. This is usually done to secure ourselves
     * from unexpected closures that disables [Komanga] from working to free the locks.
     */
    @Volatile var maxHoldTime = 30.seconds

    /**
     * [allowFreeUnlocks] is a setting that will force the [Komanga] client to freely
     * unlock any locks regardless of whether it is owned by the instance or not.
     *
     * THIS CAN BE A DANGEROUS MOVE. You should only ever use this when the situation
     * calls for it, otherwise, using the [KoLock]'s [lock] method will await for the lock
     * to expire through the lock's [maxHoldTime] instead.
     */
    @Volatile var allowFreeUnlocks = false

    // @important lockHolder is specific to a single Komanga instance and is used to identify
    //            each instance as a holder of specific locks.
    internal val lockHolder = UUID.randomUUID().toString()

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

    suspend fun close() {
        LocksDatabase.clear()
    }

    suspend fun rollback(kind: MigrationKind): Boolean {
        val useTransactions = isReplicaSet()
        val database = Komanga.database ?: throw MongoClientNotInitializedException

        return KoLock.once("rollback@$kind", maxHoldTime = 3.minutes) {
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
                            return@once false
                        }

                        logger.info("Rollback for ${migration.name} was completed.")
                    } else {
                        try {
                            migration.down(database, collection)
                        } catch (ex: Exception) {
                            logger.error("Rollback for ${migration.name} failed to complete. As this was not done with transactions, data changes " +
                                    "may have occurred.", ex)
                            model.delete()
                            return@once false
                        }
                    }
                } catch (ex: Exception) {
                    // @reason last defense capture
                    logger.error("Rollback for ${migration.name} failed to complete, due to the following exception: ", ex)
                    return@once false
                }
            }

            return@once true
        } ?: false
    }

    suspend fun migrate(kind: MigrationKind): Boolean {
        val useTransactions = isReplicaSet()
        val database = Komanga.database ?: throw MongoClientNotInitializedException

        return KoLock.once("migrate@$kind", maxHoldTime = 3.minutes) {
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
                            return@once false
                        }
                    } else {
                        try {
                            migration.up(database, collection)
                        } catch (ex: Exception) {
                            logger.error("Migration ${migration.name} failed to complete. As this was not done with transactions, data changes " +
                                    "may have occurred.", ex)
                            model.delete()
                            return@once false
                        }
                    }

                    logger.info("Migration for ${migration.name} was completed.")
                } catch (ex: Exception) {
                    // @reason last defense capture
                    logger.error("Migration ${migration.name} failed to complete, due to the following exception: ", ex)
                    return@once false
                }
            }

            return@once true
        } ?: false
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

    internal suspend fun isReplicaSet(): Boolean = try {
        val client = Komanga.client ?: throw MongoClientNotInitializedException
        client.getDatabase("admin").runCommand(Document("replSetGetStatus", 1))
        true
    } catch (ex: MongoServerException) {
        false
    }
}