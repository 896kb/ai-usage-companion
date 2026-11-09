package dev.kb896.aiusage.rear

/**
 * The digital clock's figures, HH:MM:SS, and in twelve hours the American way (PanelConfig.
 * clock12h, the owner 2026-11-09): 1 to 12, no leading zero — a blank in its place, [BLANK],
 * which the layout gives no width — and AM or PM as the [marker] after them.
 *
 * Always eight characters, so every figure keeps its index: the seconds are 5 to 7 and each
 * slot's wheel is keyed by where it stands.
 */
class ClockFigures(val figures: String, val marker: String?) {
    companion object {
        const val BLANK = ' '

        fun of(hour: Int, minute: Int, second: Int, twelve: Boolean): ClockFigures {
            if (!twelve) return ClockFigures("%02d:%02d:%02d".format(hour, minute, second), null)
            val h = (hour % 12).let { if (it == 0) 12 else it }
            return ClockFigures("%2d:%02d:%02d".format(h, minute, second), if (hour < 12) "AM" else "PM")
        }
    }
}
