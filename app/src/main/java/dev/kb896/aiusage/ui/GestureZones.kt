package dev.kb896.aiusage.ui

import kotlin.math.roundToInt

/**
 * Where the App Portal's gestures land on the back, for the front's sketch of it
 * (GestureSketchView): the 17 Pro's rear display at its own 904×572, the camera column
 * on its left (x < 296), and the touch zones the shell reads.
 *
 * The zones copy the shell's, as drawn — PortalPull's bottom band, EdgeGestures' right
 * edge (where the bottom band wins the corner), ControlCenter's top edge over the left half
 * of the usable area — but are their own numbers, not the shell's constants: this is a
 * picture to learn from, and must not move when the shell's geometry is tuned or is made
 * per model. Pure, so the layout is checked on the JVM.
 */
object GestureZones {

    const val WIDTH = 904f
    const val HEIGHT = 572f
    /** The camera column's right edge: the screen is live left of it, but under the lenses. */
    const val CAMERAS = 296f
    /** How deep the edge bands are — the shell's EDGE. */
    private const val EDGE = 48f
    /** The right edge's Back band starts here — the shell's BACK_LEFT. */
    private const val BACK_LEFT = 842f

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        /** [other] lies wholly inside, edges included. */
        fun holds(other: Box) = other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
        /** Some area in common; boxes that only touch do not overlap. */
        fun overlaps(other: Box) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    data class Lens(val x: Float, val y: Float, val r: Float)

    enum class Kind { PORTAL, BACK, CONTROL_CENTRE, DOUBLE_TAP }

    /** Which way the finger goes, drawn as an arrow in the zone; NONE for a tap. */
    enum class Arrow { UP, DOWN, LEFT, NONE }

    data class Zone(val kind: Kind, val box: Box, val label: String, val arrow: Arrow)

    val cameras = Box(0f, 0f, CAMERAS, HEIGHT)

    /** Three lenses down the column's middle, as the phone's are. */
    val lenses: List<Lens> = listOf(106f, 286f, 466f).map { Lens(CAMERAS / 2f, it, 68f) }

    private val usableMidX = CAMERAS + (WIDTH - CAMERAS) / 2f

    val zones: List<Zone> = listOf(
        Zone(Kind.PORTAL, Box(CAMERAS, HEIGHT - EDGE, WIDTH, HEIGHT), "Portal", Arrow.UP),
        Zone(Kind.BACK, Box(BACK_LEFT, 0f, WIDTH, HEIGHT - EDGE), "Back", Arrow.LEFT),
        Zone(Kind.CONTROL_CENTRE, Box(CAMERAS, 0f, usableMidX, EDGE), "Control centre", Arrow.DOWN),
        // The open middle of the usable area, between the edge bands.
        ((CAMERAS + BACK_LEFT) / 2f).let { cx ->
            Zone(Kind.DOUBLE_TAP, Box(cx - 80f, HEIGHT / 2f - 80f, cx + 80f, HEIGHT / 2f + 80f), "Double tap", Arrow.NONE)
        },
    )

    /** The sketch's scale and offset in a view: as big as fits, centred in what is left over. */
    data class Fit(val scale: Float, val dx: Float, val dy: Float) {
        fun x(px: Float) = dx + px * scale
        fun y(px: Float) = dy + px * scale
        fun box(b: Box) = Box(x(b.left), y(b.top), x(b.right), y(b.bottom))
    }

    fun fit(width: Float, height: Float): Fit {
        val scale = minOf(width / WIDTH, height / HEIGHT)
        return Fit(scale, (width - WIDTH * scale) / 2f, (height - HEIGHT * scale) / 2f)
    }

    /** A view [width] wide shows the sketch whole at this height. */
    fun heightFor(width: Int): Int = (width * HEIGHT / WIDTH).roundToInt()
}
