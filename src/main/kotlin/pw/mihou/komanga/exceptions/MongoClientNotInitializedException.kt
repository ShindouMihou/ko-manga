package pw.mihou.komanga.exceptions

object MongoClientNotInitializedException :
    RuntimeException("MongoDB client is not initialized in `Komanga` class.")
