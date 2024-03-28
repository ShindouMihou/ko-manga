package pw.mihou.komanga.exceptions

class DuplicateMigrationKeyException(key: String) :
    RuntimeException("Couldn't load migration $key due to another migration existing with the same name.")
