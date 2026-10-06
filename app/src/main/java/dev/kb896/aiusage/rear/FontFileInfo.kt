package dev.kb896.aiusage.rear

import java.io.File
import java.io.RandomAccessFile

/**
 * What a font file says about itself: its family's name, its weight, whether it is
 * italic and whether it carries a weight axis — read from the file's own tables, since
 * Android hands out font files but not their names.
 *
 * Only the tables that say so are read (the directory, `name`, `OS/2`, `fvar`), a few
 * kilobytes of a file that may be megabytes. A collection (.ttc) is read at [index].
 * Anything that does not parse as a TrueType or OpenType font is null.
 */
data class FontFileInfo(
    val family: String,
    val weight: Int,
    val italic: Boolean,
    /** A variable font with a `wght` axis: one file, every weight. */
    val variable: Boolean,
) {
    companion object {
        fun read(file: File, index: Int = 0): FontFileInfo? = try {
            RandomAccessFile(file, "r").use { read(it, index) }
        } catch (_: Exception) {
            null
        }

        private fun read(f: RandomAccessFile, index: Int): FontFileInfo? {
            var offset = 0L
            if (tag(f, 0) == "ttcf") {
                f.seek(8)
                val count = f.readInt()
                if (index !in 0 until count) return null
                f.seek(12L + 4L * index)
                offset = f.readInt().toLong() and 0xFFFFFFFFL
            }
            f.seek(offset)
            val version = f.readInt()
            if (version != 0x00010000 && version != 0x4F54544F /* OTTO */ && version != 0x74727565 /* true */) return null
            val tables = f.readUnsignedShort()
            val at = HashMap<String, Long>()
            for (i in 0 until tables) {
                val entry = offset + 12 + 16L * i
                val name = tag(f, entry)
                f.seek(entry + 8)
                at[name] = f.readInt().toLong() and 0xFFFFFFFFL
            }
            val family = at["name"]?.let { familyName(f, it) } ?: return null
            var weight = 400
            var italic = false
            at["OS/2"]?.let {
                f.seek(it + 4); weight = f.readUnsignedShort().coerceIn(1, 1000)
                f.seek(it + 62); italic = f.readUnsignedShort() and 1 != 0
            }
            val variable = at["fvar"]?.let { hasWeightAxis(f, it) } ?: false
            return FontFileInfo(family, weight, italic, variable)
        }

        private fun tag(f: RandomAccessFile, at: Long): String {
            f.seek(at)
            val b = ByteArray(4); f.readFully(b)
            return String(b, Charsets.ISO_8859_1)
        }

        /**
         * The typographic family (name 16) over the legacy one (name 1), which splits a
         * family by weight ("Roboto Black"); Windows English first, then any Windows
         * Unicode entry, then Macintosh Roman.
         */
        private fun familyName(f: RandomAccessFile, table: Long): String? {
            f.seek(table + 2)
            val count = f.readUnsignedShort()
            val strings = table + f.readUnsignedShort()
            data class Entry(val platform: Int, val encoding: Int, val language: Int, val id: Int, val length: Int, val offset: Int)
            val entries = (0 until count).map {
                Entry(f.readUnsignedShort(), f.readUnsignedShort(), f.readUnsignedShort(),
                    f.readUnsignedShort(), f.readUnsignedShort(), f.readUnsignedShort())
            }
            fun text(e: Entry): String? {
                f.seek(strings + e.offset)
                val b = ByteArray(e.length); f.readFully(b)
                val s = when (e.platform) {
                    3, 0 -> String(b, Charsets.UTF_16BE)
                    1 -> String(b, Charsets.ISO_8859_1)
                    else -> return null
                }.trim()
                return s.ifEmpty { null }
            }
            for (id in intArrayOf(16, 1)) {
                val ofId = entries.filter { it.id == id }
                val pick = ofId.firstOrNull { it.platform == 3 && it.language == 0x409 }
                    ?: ofId.firstOrNull { it.platform == 3 || it.platform == 0 }
                    ?: ofId.firstOrNull { it.platform == 1 && it.encoding == 0 }
                pick?.let(::text)?.let { return it }
            }
            return null
        }

        private fun hasWeightAxis(f: RandomAccessFile, table: Long): Boolean {
            f.seek(table + 4)
            val axesAt = table + f.readUnsignedShort()
            f.seek(table + 8)
            val axes = f.readUnsignedShort()
            val size = f.readUnsignedShort()
            return (0 until axes).any { tag(f, axesAt + size.toLong() * it) == "wght" }
        }
    }
}
