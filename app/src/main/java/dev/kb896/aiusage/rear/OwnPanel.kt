package dev.kb896.aiusage.rear

/**
 * Telling THIS install's panel from the other build's.
 *
 * The release (dev.kb896.aiusage) and the debug build (dev.kb896.aiusage.dev, an
 * applicationIdSuffix) can be installed and running at once, each with its own shell-uid
 * RearUserService — and both panels are the same class, dev.kb896.aiusage.rear.RearActivity,
 * because that comes from the namespace. Every parser matched "RearActivity" alone, so each
 * install adopted the other's panel as its own, read it as alive, moved it, and its wakes
 * went on lighting display 1 for a panel that was not even on top.
 *
 * So a panel is ours only under OUR package: the component's `<pkg>/` prefix, as both
 * `am stack list` (`taskId=…: <pkg>/<activity>`, `ComponentInfo{<pkg>/…}`) and SurfaceFlinger
 * (`<pkg>/<activity>#<id>`, `VRI-` before it on Android 17) spell it. SurfaceFlinger cuts long
 * names in the middle with `[...]`, but keeps the start — measured 24 characters on Android
 * 16, 31 with `VRI-` on 17, and `dev.kb896.aiusage.dev/` is 22 — and the end, where
 * `RearActivity#<id>` is.
 */
object OwnPanel {

    /** Both builds' packages start here; anything else is not the other build. */
    const val FAMILY = "dev.kb896.aiusage"

    const val ACTIVITY = "RearActivity"

    /**
     * Whether [text] names [pkg]'s [activity]: `<pkg>/` with nothing of a package name just
     * before it (so `dev.kb896.aiusage/` is not found inside `x.dev.kb896.aiusage/`), and
     * [activity] in the component that follows, up to the next space or `}`.
     */
    fun names(text: String, pkg: String, activity: String = ACTIVITY): Boolean {
        val prefix = "$pkg/"
        var i = text.indexOf(prefix)
        while (i >= 0) {
            val before = if (i == 0) ' ' else text[i - 1]
            if (!before.isLetterOrDigit() && before != '.' && before != '_') {
                val start = i + prefix.length
                var end = start
                while (end < text.length && !text[end].isWhitespace() && text[end] != '}') end++
                if (text.substring(start, end).contains(activity)) return true
            }
            i = text.indexOf(prefix, i + 1)
        }
        return false
    }

    /** [pkg] is the other build of this app: of the [FAMILY], and not [ours]. */
    fun isOtherBuild(pkg: String, ours: String): Boolean =
        pkg != ours && (pkg == FAMILY || pkg.startsWith("$FAMILY."))
}
