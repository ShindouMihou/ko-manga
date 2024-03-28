package pw.mihou.komanga.locks

import com.mongodb.MongoException
import com.mongodb.client.model.changestream.OperationType
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.singleOrNull
import org.bson.Document
import pw.mihou.komanga.Komanga
import pw.mihou.komanga.databases.LocksDatabase
import pw.mihou.komanga.exceptions.LockIsOwnedByAnotherException
import pw.mihou.komanga.models.LockModel
import pw.mihou.komanga.mongo.ErrorCodes

/**
 * [KoLock] is a special lock that uses MongoDB as a centralized manager. This works through the use
 * of MongoDB's unique index which will only allow one and only one document with the same value on the index
 * to exist. It also uses MongoDB's change stream when the database is a replica set to await for the lock  to
 * be freed, additionally, it uses another technique called `hold_until` or `max_hold_time` that dictates how
 * long a lock should exist before it can be forcibly unlocked.
 */
class KoLock internal constructor(
    private val key: String,
    private val maxHoldTime: Duration = Komanga.maxHoldTime
) {

    companion object {
        fun of(key: String, maxHoldTime: Duration = Komanga.maxHoldTime) = KoLock(key, maxHoldTime)

        /**
         * [once] executes the [executable] when the [tryLock] for the [KoLock] with the given [key]
         * is acquired, this will return null when [tryLock] fails to acquire the lock.
         */
        suspend fun <T> once(
            key: String,
            maxHoldTime: Duration = Komanga.maxHoldTime,
            executable: suspend () -> T
        ): T? {
            val lock = KoLock(key, maxHoldTime)
            try {
                if (!lock.tryLock()) {
                    return null
                }
                return executable()
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * [withLock] executes a given [executable] when [lock] is acquired and [unlock]s the [KoLock]
     * when the [executable] is finished. This will blockingly await for the lock to be acquired and uses
     * [lock] internally with the same drawbacks and issues.
     */
    suspend inline fun withLock(executable: () -> Unit) {
        lock()
        try {
            executable()
        } finally {
            unlock()
        }
    }

    /**
     * [lock] locks the [KoLock] with a maximum holding time of [maxHoldTime]. This will blockingly
     * await for the lock to be unlocked, or for the lock to reach the maximum holding time. Depending on the
     * database configuration, there are two ways that this will await for the lock:
     * 1. If the database is a replica set, then we will use change stream with a maximum wait time to listen to when
     * the lock is freed or until it reaches the maximum wait time.
     * 2. If the database is not a replica set, or change stream failed, we will wait for the `holdUntil` of the
     * lock before forcibly unlocking the lock and acquire it.
     *
     * Note that the current instance may be the one to unlock it, but another instance may acquire it first
     * as [KoLock] doesn't inherently respect fairness. If that happens then the [KoLock] will wait for that
     * instance to finish and retry again.
     *
     * @throws IllegalStateException when the lock was almost acquired, but couldn't be acquired.
     */
    suspend fun lock() {
        try {
            val now = Instant.now()
            val holdUntil = now.plusMillis(maxHoldTime.inWholeMilliseconds)
            val lock = LockModel(id = key, holdUntil = holdUntil, holder = Komanga.lockHolder)

            val lockAck = LocksDatabase.insert(lock)
            if (!lockAck) {
                throw IllegalStateException("Failed to acquire lock $key (free). Didn't receive an acknowledge from MongoDb.")
            }
        } catch (ex: MongoException) {
            if (ex.code == ErrorCodes.DUPLICATE_KEY_CODE) {
                val lock = LocksDatabase.find(key) ?: return lock()

                var now = Instant.now().toEpochMilli()
                val holdUntil = lock.holdUntil.toEpochMilli()

                // if we are ahead of the maximum holding time.
                if (now > holdUntil) {
                    val deletionAck = LocksDatabase.delete(key)
                    if (deletionAck) {
                        return lock()
                    }

                    throw IllegalStateException("Failed to delete lock $key (expired). Didn't receive an acknowledge from MongoDb.")
                }

                if (holdUntil > (now + 10.seconds.inWholeMilliseconds)) {
                    val isReplSet = Komanga.isReplicaSet()
                    if (isReplSet) {
                        val stream = Komanga.database?.getCollection<LockModel>("koLocks")?.watch(
                            listOf(
                                Document(
                                    "\$match",
                                    Document("_id", key),
                                ),
                            ),
                        )
                        if (stream != null) {
                            val remainder = lock.holdUntil.toEpochMilli() - now

                            val op = stream
                                .maxAwaitTime(remainder, TimeUnit.MILLISECONDS)
                                .filter { it.operationType == OperationType.DELETE }
                                .singleOrNull()
                            if (op != null) {
                                return lock()
                            }
                        }
                    }

                    // fallback to regular awaits instead.
                    // we are reassigning `now` because this can happen after `stream` failed.
                    now = Instant.now().toEpochMilli()
                }

                val remainder = lock.holdUntil.toEpochMilli() - now
                if (remainder > 0) { // remainder can go negative especially if watch stream failed.
                    delay(remainder)
                }

                val deletionAck = LocksDatabase.delete(key)
                if (deletionAck) {
                    return lock()
                }

                throw IllegalStateException("Failed to delete lock $key (expired). Didn't receive an acknowledge from MongoDb.")
            }
        }
    }

    /**
     * [tryLock] tries to lock the [KoLock] if it can. Unlike [lock], this doesn't blockingly wait for the
     * lock to be unlocked or acquired, this is a hit-and-leave method where the instance will try to lock
     * if it can. If the lock is already acquired by another, it checks if the lock has existed beyond its
     * maximum holding time, and if it has, then forcibly acquires the lock, otherwise, foregoes the lock.
     *
     * @throws IllegalStateException when the lock couldn't be freed after it has already expired.
     * @return have we acquired the lock or not?
     */
    suspend fun tryLock(): Boolean {
        try {
            val now = Instant.now()
            val holdUntil = now.plusMillis(maxHoldTime.inWholeMilliseconds)
            val lock = LockModel(id = key, holdUntil = holdUntil, holder = Komanga.lockHolder)

            return LocksDatabase.insert(lock)
        } catch (ex: MongoException) {
            if (ex.code == ErrorCodes.DUPLICATE_KEY_CODE) {
                val lock = LocksDatabase.find(key) ?: return tryLock()

                val now = Instant.now().toEpochMilli()
                val holdUntil = lock.holdUntil.toEpochMilli()

                val remainder = holdUntil - now
                if (remainder > 0) {
                    return false
                }

                val deletionAck = LocksDatabase.delete(key)
                if (deletionAck) {
                    return tryLock()
                }

                throw IllegalStateException("Failed to delete lock $key (expired). Didn't receive an acknowledge from MongoDb.")
            }
            throw ex
        }
    }

    /**
     * [unlock] unlocks the [KoLock] if it is owned by the current instance, otherwise
     * checks whether it has exceeded its `hold_until` timestamp, and if it did, then forcibly unlocks the
     * lock.
     *
     * One can also forcibly unlock when [Komanga.allowFreeUnlocks] is set to [true], but that is never recommended
     * until the situation deliberately calls for it.
     */
    suspend fun unlock(): Boolean {
        val lock = LocksDatabase.find(key) ?: return true
        if (lock.holder != Komanga.lockHolder && !Komanga.allowFreeUnlocks) {
            val now = Instant.now().toEpochMilli()
            val holdUntil = lock.holdUntil.toEpochMilli()

            val remainder = holdUntil - now
            if (remainder > 0) {
                throw LockIsOwnedByAnotherException(key)
            }
        }
        return LocksDatabase.delete(key)
    }
}
