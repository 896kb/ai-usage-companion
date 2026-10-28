package dev.kb896.aiusage.ui

/**
 * The pinned apps' names and icons for the front's portal preview, kept by package: a
 * reorder, an unpin or one more pin loads only what is new, not every icon again (up to
 * 48 of them, on every tap of a move-up arrow).
 *
 * Pure — the icon type is the caller's (a Bitmap on the phone) — so it is tested on the
 * JVM. Not thread-safe: MainActivity keeps it on the main thread.
 */
class PortalPreviewApps<I> {

    private val known = HashMap<String, Pair<String, I?>>()

    /** Of [shown], the packages not loaded yet, in its order. */
    fun missing(shown: List<String>): List<String> = shown.filter { it !in known }

    /** [pkg]'s name and icon (null: it has none to show). */
    fun put(pkg: String, label: String, icon: I?) {
        known[pkg] = label to icon
    }

    /**
     * [shown]'s names and icons in its order, as the sheet takes them — a package not
     * loaded yet goes by its own name, without an icon — and the packages no longer shown
     * are forgotten, so their icons go with them.
     */
    fun arranged(shown: List<String>): Pair<List<String>, List<I?>> {
        known.keys.retainAll(shown.toSet())
        return shown.map { known[it]?.first ?: it } to shown.map { known[it]?.second }
    }
}
