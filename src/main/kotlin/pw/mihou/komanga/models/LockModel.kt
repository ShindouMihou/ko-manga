package pw.mihou.komanga.models

import java.time.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.codecs.pojo.annotations.BsonProperty

data class LockModel(
    @BsonId val id: String,
    @BsonProperty("created_at") val createdAt: Instant = Instant.now(),
    @BsonProperty("hold_until") val holdUntil: Instant,
    val holder: String
)
