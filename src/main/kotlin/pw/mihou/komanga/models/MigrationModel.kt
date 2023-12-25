package pw.mihou.komanga.models

import org.bson.codecs.pojo.annotations.BsonId
import org.bson.codecs.pojo.annotations.BsonProperty
import pw.mihou.komanga.databases.MigrationsDatabase
import java.time.Instant

data class MigrationModel(
    @BsonId val id: String,
    @BsonProperty("created_at") val createdAt: Instant = Instant.now(),
    val kind: MigrationKind
) {
    suspend fun insert() = MigrationsDatabase.insert(this)
    suspend fun delete() = MigrationsDatabase.delete(id)
}

enum class MigrationKind {
    DATA, COLLECTION, INDEX
}