package io.relite.home.data

/**
 * Minimal read/write-a-string abstraction so persistence logic
 * (WorkspaceRepository) can be unit tested without touching the
 * filesystem or Android's Context.
 */
interface Storage {
    fun read(): String?

    /**
     * Returns true once `content` is durably persisted, false if the write
     * failed. Section 24 (v0.4.0): a caller (`WorkspaceRepository.save`,
     * ultimately `WorkspaceController.mutate`) must know whether
     * persistence actually succeeded *before* committing to the new
     * in-memory state — a write that fails must leave both memory and
     * disk on the previous, still-good state, not a state that only
     * exists in memory and disappears the moment the process dies.
     */
    fun write(content: String): Boolean

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
    /** When true, [write] reports failure without mutating [content] — simulates a persistence failure in tests. */
    var failWrites: Boolean = false

    override fun read(): String? = content

    override fun write(content: String): Boolean {
        if (failWrites) return false
        this.content = content
        return true
    }
}
