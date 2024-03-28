package pw.mihou.komanga.exceptions

class LockIsOwnedByAnotherException(key: String) :
    RuntimeException("Couldn't manage the lock $key because it is owned by another instance.")
