package pw.mihou.komanga.interfaces

import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import org.bson.Document
import pw.mihou.komanga.models.MigrationKind

interface Migration {
    val name: String
    val kind: MigrationKind

    val collectionName: String
    val allowTransactions: Boolean get() = kind == MigrationKind.DATA

    suspend fun up(db: MongoDatabase, coll: MongoCollection<Document>)
    suspend fun down(db: MongoDatabase, coll: MongoCollection<Document>)
}