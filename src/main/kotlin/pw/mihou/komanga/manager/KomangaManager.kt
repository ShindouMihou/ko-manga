package pw.mihou.komanga.manager

import pw.mihou.komanga.Komanga
import pw.mihou.komanga.models.MigrationKind

typealias KomangaTask = suspend () -> Unit

/**
 * [KomangaManager] is a simple manager that enables developers to quickly integrate
 * Komanga migrations onto their program commandline arguments.
 */
object KomangaManager {
    /**
     * [run] selectively looks into the [args] of the program to identify specific
     * database migration-related tasks to execute and runs them all.
     *
     * @return true when there was a task executed.
     */
    suspend fun run(args: List<String>): Boolean {
        val tasks = mutableListOf<KomangaTask>()
        for (raw in args) {
            val arg = raw.lowercase()
            when (arg) {
                "--migrate-index" -> {
                    tasks += { Komanga.migrate(MigrationKind.INDEX) }
                }

                "--migrate-data" -> {
                    tasks += { Komanga.migrate(MigrationKind.DATA) }
                }

                "--migrate-collection" -> {
                    tasks += { Komanga.migrate(MigrationKind.COLLECTION) }
                }

                "--rollback-index" -> {
                    tasks += { Komanga.rollback(MigrationKind.INDEX) }
                }

                "--rollback-data" -> {
                    tasks += { Komanga.rollback(MigrationKind.DATA) }
                }

                "--rollback-collection" -> {
                    tasks += { Komanga.rollback(MigrationKind.COLLECTION) }
                }
            }
        }
        if (tasks.isEmpty()) {
            return false
        }

        for (task in tasks) {
            task()
        }
        return true
    }
}
