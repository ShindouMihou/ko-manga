package pw.mihou.komanga.databases

import com.mongodb.MongoException
import com.mongodb.client.model.Filters
import kotlinx.coroutines.flow.firstOrNull
import pw.mihou.komanga.Komanga
import pw.mihou.komanga.exceptions.MongoClientNotInitializedException
import pw.mihou.komanga.models.LockModel
import pw.mihou.komanga.mongo.ErrorCodes

object LocksDatabase {
    internal val collection = run {
        val database = Komanga.database ?:
            throw MongoClientNotInitializedException

        return@run database.getCollection<LockModel>("koLocks")
    }

    internal suspend fun find(id: String) = collection
        .find(Filters.eq("_id", id))
        .firstOrNull()

    internal suspend fun insert(model: LockModel): Boolean {
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
        .deleteOne(Filters.eq("_id", id))
        .wasAcknowledged()

    internal suspend fun clear() = collection
        .deleteMany(Filters.eq("holder", Komanga.lockHolder))
        .wasAcknowledged()
}