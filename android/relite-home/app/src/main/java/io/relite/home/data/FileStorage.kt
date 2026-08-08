package io.relite.home.data

import java.io.File

/** Android-facing Storage backed by a single file under the app's private data dir. */
class FileStorage(private val file: File) : Storage {
    override fun read(): String? = if (file.exists()) file.readText() else null

    override fun write(content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
