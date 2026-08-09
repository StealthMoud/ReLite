package io.relite.home.data

/**
 * Minimal read/write-a-string abstraction so persistence logic
 * (WorkspaceRepository) can be unit tested without touching the
 * filesystem or Android's Context.
 */
interface Storage {
    fun read(): String?
    fun write(content: String)

    /**
     * Preserve `content` (data that failed to deserialize) somewhere
     * recoverable, without touching the primary read()/write() slot —
     * section 50: a corrupt file must not be silently replaced by an
     * empty layout with no trace of what was there before. Default is a
     * no-op for storage backends (like [InMemoryStorage]) that don't
     * need durable corruption recovery.
     */
    fun backupCorrupt(content: String) {}
}

/** Simple in-memory Storage, useful for tests and as a first-run default. */
class InMemoryStorage(private var content: String? = null) : Storage {
    override fun read(): String? = content
    override fun write(content: String) {
        this.content = content
    }
}
