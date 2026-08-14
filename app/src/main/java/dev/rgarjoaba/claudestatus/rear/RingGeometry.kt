package dev.rgarjoaba.claudestatus.rear

import android.os.Bundle
import dev.rgarjoaba.claudestatus.Panel

data class RingGeometry(val cx: Float, val cy: Float, val radius: Float, val stroke: Float)

data class PanelGeometry(val personal: RingGeometry, val work: RingGeometry) {

    companion object {
        /** Estimates. Replaced with measured values at the end of Task 5. */
        val DEFAULT = PanelGeometry(
            personal = RingGeometry(cx = 148f, cy = 152f, radius = 108f, stroke = 17f),
            work = RingGeometry(cx = 148f, cy = 420f, radius = 108f, stroke = 17f),
        )

        /**
         * Overlays adb-pushed intent extras onto [base] so ring placement can be
         * dialled in without a rebuild: cx1 cy1 r1 cx2 cy2 r2 stroke.
         */
        fun fromExtras(extras: Bundle?, base: PanelGeometry): PanelGeometry {
            if (extras == null) return base
            fun f(key: String, fallback: Float) =
                if (extras.containsKey(key)) extras.getInt(key).toFloat() else fallback
            val stroke = f("stroke", base.personal.stroke)
            return PanelGeometry(
                personal = RingGeometry(
                    f("cx1", base.personal.cx), f("cy1", base.personal.cy),
                    f("r1", base.personal.radius), stroke),
                work = RingGeometry(
                    f("cx2", base.work.cx), f("cy2", base.work.cy),
                    f("r2", base.work.radius), stroke),
            )
        }
    }
}
