Ko-manga, or kotlin-Manga, is a simple, embedded MongoDB toolkit for Kotlin programs that can perform simple migrations, 
and other MongoDB transactions with features such as locking with MongoDB and related.

# Installation

To get started, simply install `ko-manga` as a dependency through Jitpack:
- [Jitpack](https://jitpack.io/#pw.mihou/ko-manga)

# Getting started

Ko-manga is very straightforward with most utilities available under the `Komanga` singleton object, but here 
are some things that can help you get started, including embedding a command-line tool to your application for 
quicker development.

## Initializing Komanga

Ko-manga relies on having a connection to MongoDB through its Kotlin driver client, which means we need a way to 
have Ko-manga use the MongoDB client. You can direct Ko-manga to use a specific MongoDB client by setting its `Komanga.client` 
property.

You can also configure which database it will specifically create its own collections on using `Komanga.database`, this is 
important as Ko-manga will create several collections that are prefixed with `ko`, by setting it to a common database, it might 
end up conflicting especially if you have multiple applications on the same MongoDB instance running Ko-manga.

```kotlin
// ... Initialize your MongoDB client instance.
Komanga.client = mongo
Komanga.database = mongo.getDatabase("flyght")
```

To build your own command-line tool with Ko-manga, simply refer to [`KomangaManager`](src/main/kotlin/pw/mihou/komanga/manager/KomangaManager.kt).

## Creating migrations

Currently, we do not offer any built-in tool to generate migrations for you, but we can provide you with some template generation that 
will help you in generating the models. To create a migration, you have to write the following code:
```kotlin
internal object MigrationName: Migration {
    override val name: String = "migration_name"
    // You can also use MigrationKind.COLLECTION and MigrationKind.INDEX
    // Depending on what kind of migration you want to make.
    override val kind: MigrationKind = MigrationKind.DATA
    override val collectionName: String = "$collection"
    
    override suspend fun up(db: MongoDatabase, coll: MongoCollection<Document>) {
    }
    override suspend fun down(db: MongoDatabase, coll: MongoCollection<Document>) {
    }
}
```

Once you have created your migration, you also have to list it to `Ko-manga` using:
```kotlin
Komanga.load(MigrationName)
```

It allows Komanga to know that this migration exist and how to handle the various operations that are needed for that 
migration.

You can subsequently create a simple generator for generating this migration by following this code:
```kotlin
fun runKomangaGenerator(args: List<String>): Boolean {
    if (args.isEmpty()) {
        return false
    }
    
    val arg0 = args[0]
    if (arg0 == "--create-migration" && args.size > 2) {
        val collection = args[1]

        val name = args.drop(2).joinToString("") { it[0].uppercase() + it.drop(1) }
        val fileName = args.drop(2).joinToString("_") { it.lowercase() }
        val path =
            // @TODO Edit the directory here
            Paths.get(
                    "src/main/kotlin/<directory>/migrations/${System.currentTimeMillis()}_$fileName.kt"
                )
                .toAbsolutePath()
                .toString()

        val file = File(path)
        if (!file.exists()) {
            file.createNewFile()
        }

        // @TODO Edit the package here
        file.writeText(
            """
                |package pro.qucy.flyght.komanga.migrations
                |
                |import com.mongodb.kotlin.client.coroutine.MongoCollection
                |import com.mongodb.kotlin.client.coroutine.MongoDatabase
                |import org.bson.Document
                |import pw.mihou.komanga.interfaces.Migration
                |import pw.mihou.komanga.models.MigrationKind
                |
                |// @TODO Don't forget to add this to Koe.kt
                |internal object ${name}Migration: Migration {
                |    override val name: String = "${fileName}_migration"
                |    override val kind: MigrationKind = MigrationKind.DATA
                |    override val collectionName: String = "$collection"
                |
                |    override suspend fun up(db: MongoDatabase, coll: MongoCollection<Document>) {
                |    }
                |    override suspend fun down(db: MongoDatabase, coll: MongoCollection<Document>) {
                |    }
                |}
            """
                .trimMargin("|"),
        )

        Log.info("Created migration file under $path.")
        return true
    }

    return false
}
```

## Command-line Tool

You can integrate `ko-manga` as a command-line tool on your program by either creating your own run command argument
handler, or using our built-in [`KomangaManager`](src/main/kotlin/pw/mihou/komanga/manager/KomangaManager.kt) which comes
with all the arguments default.

To set up `KomangaManager`, simply add the following code on your `main` function:
```kotlin
Koe.run(args.toList())
```

We recommend stopping the execution of the program when doing the migrations, and also including `--rolling` as a
"continue-forward" measure, such as in the below example:
```kotlin
if (Koe.run(args.toList()) && !args.contains("--rolling")) {
    Log.warn("Ko-manga arguments was ran, skipping program execution.")
    exitProcess(0) 
}
```

## Running migrations manually

If you don't prefer to use a command-line tool to migrate and related, you can refer to the methods available in the 
`Komanga` instance:
```kotlin
Komanga.rollback(MigrationKind.DATA)
Komanga.migrate(MigrationKind.DATA)
```

## Locking and Unlocking

Komanga allows you to use MongoDB as a distributed lock mechanism. This lock mechanism is designed with a time-lock-signatured 
based system wherein each lock has a maximum amount of time before it is forcibly unlocked by another instance, depending on 
the configuration. In addition, locks are assigned to a specific instance through a randomized UUID.

To perform a lock, simply use the `KoLock.once` method. You can also use the `KoLock.of` method to 
create a `KoLock` instance which allows you to use `lock`, `unlock`, `tryLock` and `withLock`. 

`KoLock.once` executes the task given ONLY ONCE which means that when there is another instance taking hold of the lock, it will 
cancel and return `null`. This is great for tasks that should only be executed once.

As an example, we can use `KoLock.once` to migrate the database once and only once.
```kotlin
KoLock.once("migrate") {
    Komanga.migrate(MigrationKind.DATA)
}
```

Although it will immediately release the lock once the execution has completed, which means that any future calls to the 
same lock key will trigger the same task. It is recommended to add a tiny delay to accommodate for any delays before releasing the lock, such as in the 
following code:
```kotlin
KoLock.once("migrate") {
    Komanga.migrate(MigrationKind.DATA)
    delay(10.seconds) // Hold lock until 10 seconds later where all other instances have passed this code.
}
```

You can also write this code with the `of` method as follows:
```kotlin
val lock = KoLock.of("migrate")
if (!lock.tryLock()) {
    return null
}

try {
    Komanga.migrate(MigrationKind.DATA)
    delay(10.seconds)
} finally {
    lock.unlock()
}
```

## License

Ko-manga is rightfully distributed under the MIT License. You can freely modify, use, copy, merge, publish, distribute 
and related as intended by the MIT license.