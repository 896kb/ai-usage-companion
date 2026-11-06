package dev.kb896.aiusage.service

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import dev.kb896.aiusage.rear.NoticeShade
import dev.kb896.aiusage.rear.ShellNotices
import java.io.ByteArrayOutputStream

/**
 * The notifications the shell's list over a guest shows (ShellNotices), kept in the app's process
 * and handed over whole with every change: the relay's picks, as they reach the panel. In memory
 * only, like the panel's own, and no more than [MAX].
 */
object BackNotices {
    private const val MAX = 12
    private const val ICON_PX = 96

    private var items: List<NoticeShade.Item> = emptyList()

    /** The keys now on the back, newest first. */
    fun keys(): List<String> = items.map { it.key }
    private val icons = HashMap<String, ByteArray>()

    /**
     * The panel's list, whole: the one list there is. What the relay has handed over since is only
     * so that a banner has something to show before the panel has said it has it.
     */
    @Synchronized
    fun replace(context: Context, next: List<NoticeShade.Item>) {
        if (next == items) return
        items = next.take(MAX)
        for (item in items) loadIcon(context, item.pkg)
        send(context, "")
    }

    private fun loadIcon(context: Context, pkg: String) {
        if (pkg !in icons) runCatching {
            val bmp = context.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            icons[pkg] = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }

    /** One that has just arrived, newest first, with its banner. */
    @Synchronized
    fun posted(context: Context, item: NoticeShade.Item) {
        items = (listOf(item) + items.filter { it.key != item.key }).take(MAX)
        loadIcon(context, item.pkg)
        send(context, item.key)
    }

    @Synchronized
    fun removed(context: Context, key: String) {
        val next = items.filter { it.key != key }
        if (next.size == items.size) return
        items = next
        send(context, "")
    }

    /** Only these are on the front now. */
    @Synchronized
    fun keep(context: Context, keys: Set<String>) {
        val next = items.filter { it.key in keys }
        if (next.size == items.size) return
        items = next
        send(context, "")
    }

    private fun send(context: Context, pop: String) {
        Graph.of(context).bridge.setNotices(ShellNotices.encode(items, icons), pop)
    }
}
