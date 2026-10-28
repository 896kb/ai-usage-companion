package dev.kb896.aiusage.data

/**
 * Edits to [PanelConfig.portalApps]. Pure, so the front's rows and the back's grid agree
 * on what a tap did without either knowing about the other.
 */
object PortalApps {

    /**
     * The most apps that can be pinned: four pages of the biggest grid, and as many icons
     * as go to the shell in one call (PortalAppsSnapshot.ICON_BUDGET).
     */
    const val MAX = 48

    /**
     * [pkg] pinned at the end ([on]) or unpinned. Pinning past [max] changes nothing — the
     * picker then puts its switch back off and says why; pinning twice, unpinning what is
     * not pinned, change nothing either.
     */
    fun pinned(pinned: List<String>, pkg: String, on: Boolean, max: Int = MAX): List<String> = when {
        !on -> pinned.filter { it != pkg }
        pkg in pinned || pinned.size >= max -> pinned
        else -> pinned + pkg
    }

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
