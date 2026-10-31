package com.foxfw.companion

import java.io.File

/**
 * Phone-local library of made tags: each is stored as its Flipper .nfc/.rfid text
 * under the app's external files dir (`tagmaker/`), so it re-deploys to either
 * backend and can be shared to a Flipper SD as-is.
 */
object TagLibrary {
    private fun dir(): File? =
        FoxApplication.instance.getExternalFilesDir(null)?.let { File(it, "tagmaker").apply { mkdirs() } }

    private fun ext(tag: Tag) = if (tag is LfTag) "rfid" else "nfc"

    fun save(tag: Tag): Boolean = try {
        val d = dir() ?: return false
        val id = when (tag) { is LfTag -> tag.data; is HfTag -> tag.uid }.joinToString("") { "%02X".format(it) }
        File(d, "${tag.type.name}_$id.${ext(tag)}").writeText(tag.toFlipperFile())
        true
    } catch (_: Throwable) { false }

    fun list(): List<String> =
        dir()?.listFiles { f -> f.isFile && (f.name.endsWith(".nfc") || f.name.endsWith(".rfid")) }
            ?.map { it.name }?.sorted() ?: emptyList()

    fun load(name: String): Tag? = try {
        dir()?.let { TagCodec.parseFlipperFile(File(it, name).readText()) }
    } catch (_: Throwable) { null }
}
