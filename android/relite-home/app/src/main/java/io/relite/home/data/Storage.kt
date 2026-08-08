package io.relite.home.data

/**
 * Minimal read/write-a-string abstraction so persistence logic
 * (WorkspaceRepository) can be unit tested without touching the
 * filesystem or Android's Context.
 */
interface Storage {
    fun read(): String?
    fun write(content: String)
}

/** Simple in-memory Storage, useful for tests and as a first-run default. */
class InMemoryStorage(private var content: String? = null) : Storage {
    override fun read(): String? = content
    override fun write(content: String) {
        this.content = content
    }
}
