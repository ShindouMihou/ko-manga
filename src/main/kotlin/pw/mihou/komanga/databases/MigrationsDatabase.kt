package pw.mihou.komanga.databases

import com.mongodb.MongoException
import org.bson.Document
import pw.mihou.komanga.Komanga
import pw.mihou.komanga.exceptions.MongoClientNotInitializedException
import pw.mihou.komanga.models.MigrationModel
import pw.mihou.komanga.mongo.ErrorCodes

object MigrationsDatabase {
    internal val collection = run {
        val database = Komanga.database ?:
            throw MongoClientNotInitializedException

        return@run database.getCollection<MigrationModel>("koMigrations")
    }

    internal suspend fun insert(model: MigrationModel): Boolean {
        try {
            collection.insertOne(model)
            return true
        } catch (ex: MongoException) {
            if (ex.code == ErrorCodes.DUPLICATE_KEY_CODE) {
                return false
            }
            throw ex
        }
    }

    internal suspend fun delete(id: String) = collection
        .deleteOne(filter = Document("_id", id))
        .wasAcknowledged()
}