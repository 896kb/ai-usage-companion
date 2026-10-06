package dev.kb896.aiusage.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.net.Uri
import android.provider.OpenableColumns
import dev.kb896.aiusage.data.PanelFont
import dev.kb896.aiusage.data.PanelFontFile
import dev.kb896.aiusage.rear.FontFileInfo
import dev.kb896.aiusage.rear.PanelType
import java.io.File

/**
 * The faces the panel can be set in: the two the app ships, the phone's own families,
 * and font files imported into the app's folder.
 *
 * "The phone's own" is what Android lets an app see — the system's font files, which on
 * HyperOS include the font a theme put in. Fonts a user keeps as files are brought in by
 * import: Android has no shared folder of installed fonts for apps to read.
 */
object PanelFonts {

    val bundled: List<PanelFont> = listOf(
        PanelFont("Outfit", bundled = PanelType.OUTFIT),
        PanelFont("Departure Mono", bundled = PanelType.DEPARTURE),
    )

    /**
     * The system's families that can set the panel — upright, with Latin letters and
     * digits, grouped by the family each file names. Off the main thread: every font
     * file on the phone is opened.
     *
     * Android's own list first, then the font folders read directly: HyperOS keeps faces
     * there that the list leaves out — the lock screen's clock faces, in /product/fonts.
     */
    fun system(): List<PanelFont> {
        val groups = LinkedHashMap<String, MutableList<PanelFontFile>>()
        val seen = HashSet<Pair<String, Int>>()
        fun add(file: File, index: Int, weight: Int?) {
            if (!seen.add(file.path to index)) return
            val info = FontFileInfo.read(file, index) ?: return
            if (info.italic) return
            groups.getOrPut(info.family) { mutableListOf() } +=
                PanelFontFile(file.path, index, weight ?: info.weight, info.variable)
        }
        for (font in SystemFonts.getAvailableFonts()) {
            val file = font.file ?: continue
            if (font.style.slant != FontStyle.FONT_SLANT_UPRIGHT) continue
            add(file, font.ttcIndex, font.style.weight)
        }
        for (folder in FOLDERS) for (file in File(folder).listFiles().orEmpty()) {
            if (file.extension.lowercase() in setOf("ttf", "otf", "ttc")) add(file, 0, null)
        }
        return families(groups).filter { latin(it) }
    }

    private val FOLDERS = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts")

    /** The folder imported fonts are copied into, read by both processes. */
    private fun dir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    fun imported(context: Context): List<PanelFont> {
        val groups = LinkedHashMap<String, MutableList<PanelFontFile>>()
        for (file in dir(context).listFiles().orEmpty().sortedBy { it.name }) {
            val info = FontFileInfo.read(file) ?: continue
            groups.getOrPut(info.family) { mutableListOf() } +=
                PanelFontFile(file.path, 0, info.weight, info.variable)
        }
        return families(groups)
    }

    fun isImported(context: Context, font: PanelFont): Boolean =
        font.files.isNotEmpty() && font.files.all { it.path.startsWith(dir(context).path) }

    /**
     * Copies the file at [uri] in, and hands back its family — with any other weights of
     * it imported before. Null, and nothing kept, when it is not a font.
     */
    fun import(context: Context, uri: Uri): PanelFont? {
        val shown = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        val name = (shown ?: "font-${System.currentTimeMillis()}")
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
        val target = File(dir(context), name)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
        } catch (_: Exception) {
            target.delete()
            return null
        }
        val info = FontFileInfo.read(target)
        if (info == null || !latin(PanelFont(info.family, files = listOf(PanelFontFile(target.path))))) {
            target.delete()
            return null
        }
        return imported(context).firstOrNull { it.name == info.family }
    }

    fun remove(font: PanelFont) {
        font.files.forEach { File(it.path).delete() }
    }

    /** One file with a weight axis stands for the family; otherwise one file per weight. */
    private fun families(groups: Map<String, List<PanelFontFile>>): List<PanelFont> =
        groups.mapNotNull { (name, files) ->
            if (bundled.any { it.name.equals(name, ignoreCase = true) }) return@mapNotNull null
            val unique = files.distinctBy { it.path to it.index }
            val variable = unique.firstOrNull { it.variable }
            PanelFont(name, files = variable?.let(::listOf) ?: unique.distinctBy { it.weight }.sortedBy { it.weight })
        }.sortedBy { it.name.lowercase() }

    /** Whether the face has its own Latin capitals, lowercase and digits — no fallback asked. */
    private fun latin(font: PanelFont): Boolean = try {
        val f = font.files.first()
        val family = FontFamily.Builder(Font.Builder(File(f.path)).setTtcIndex(f.index).build()).build()
        val paint = Paint().apply { typeface = Typeface.CustomFallbackBuilder(family).build() }
        listOf("A", "a", "0").all { paint.hasGlyph(it) }
    } catch (_: Exception) {
        false
    }
}
