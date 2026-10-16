package app.lagcoach.data

import java.io.File

actual object FileIO {
    lateinit var dir: File

    actual fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    actual fun write(name: String, text: String) {
        // Write-then-rename so a crash mid-write never leaves a truncated file.
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).writeText(text)
            tmp.delete()
        }
    }
}
