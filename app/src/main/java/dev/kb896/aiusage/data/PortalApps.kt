package dev.kb896.aiusage.data

/**
 * Edits to [PanelConfig.portalApps]. Pure, so the front's rows and the back's grid agree
 * on what a tap did without either knowing about the other.
 */
object PortalApps {

    /** Pinned at the end, or unpinned if it already was. */
    fun toggled(pinned: List<String>, pkg: String): List<String> =
        if (pkg in pinned) pinned.filter { it != pkg } else pinned + pkg

    /** One place up; the first stays first, an unknown package changes nothing. */
    fun movedUp(pinned: List<String>, pkg: String): List<String> {
        val i = pinned.indexOf(pkg)
        if (i <= 0) return pinned
        return pinned.toMutableList().apply { removeAt(i); add(i - 1, pkg) }
    }

    /**
     * What the grid shows: the pinned apps still installed, each once, in pinned order.
     * An uninstalled app is not removed from the config here — reinstalling it should
     * bring its place back.
     */
    fun present(pinned: List<String>, installed: Set<String>): List<String> =
        pinned.filter { it in installed }.distinct()
}
