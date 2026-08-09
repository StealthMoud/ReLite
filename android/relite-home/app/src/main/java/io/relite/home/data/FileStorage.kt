package io.relite.home.data

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

/**
 * Android-facing Storage backed by [AtomicFile] under the app's private
 * data dir — not a plain `File.writeText()` (section 48/49, v0.3.0
 * plan). [AtomicFile] writes to a sibling `<name>.new` file, fsyncs, and
 * only then renames it over the real file; a process death mid-write
 * leaves the previous good file untouched (or lets `AtomicFile` finish
 * recovering the `.new` file itself on next read), so a workspace write
 * interrupted by a crash/kill can't leave a half-written, truncated
 * `workspace.json` as the *only* copy.
 */
class FileStorage(private val file: File) : Storage {
    private val atomicFile = AtomicFile(file)

    override fun read(): String? =
        try {
            String(atomicFile.readFully(), StandardCharsets.UTF_8)
        } catch (_: FileNotFoundException) {
            null
        }

    override fun write(content: String): Boolean {
        file.parentFile?.mkdirs()
        val stream = atomicFile.startWrite()
        return try {
            stream.write(content.toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(stream)
            true
        } catch (e: Exception) {
            atomicFile.failWrite(stream)
            false
        }
    }

    override fun backupCorrupt(content: String) {
        val backupFile = File(file.parentFile, "${file.name}.corrupt")
        if (backupFile.exists()) return // don't clobber an earlier preserved copy
        backupFile.writeText(content)
    }
}
