package dev.rgarjoaba.claudestatus.rear

import android.os.Bundle
import dev.rgarjoaba.claudestatus.Panel

data class RingGeometry(val cx: Float, val cy: Float, val radius: Float, val stroke: Float)

data class PanelGeometry(val personal: RingGeometry, val work: RingGeometry) {

    companion object {
        /**
         * Lens centres, MEASURED on device f940914b (Xiaomi 17 Pro, 25098PN5AC) on
         * 2026-08-14 and confirmed visually by the user against the physical lenses.
         * Do not "correct" these back toward round numbers.
         */
        private const val LENS_CX = 148f
        private const val PERSONAL_LENS_CY = 147f
        private const val WORK_LENS_CY = 425f

        /**
         * The lens radius, also measured — the calibration ruler was aligned to the
         * lens *outline*, so this is the edge of the camera itself.
         */
        private const val LENS_RADIUS = 108f

        /**
         * How far outside the lens edge the ring's centreline sits.
         *
         * UNVERIFIED. Drawing at LENS_RADIUS put the ring directly on the camera bump,
         * where it was invisible at any colour or thickness — the calibration ruler
         * measured where the lens *is*, not where a ring around it should go. These
         * values are a first estimate and need a device pass to settle.
         *
         * With RING_STROKE = 28 the band spans roughly 118..146, i.e. clear of the
         * lens edge at 108 and sitting on the surrounding glass.
         */
        private const val RING_OFFSET = 24f
        private const val RING_STROKE = 28f

        private const val RING_RADIUS = LENS_RADIUS + RING_OFFSET

        val DEFAULT = PanelGeometry(
            personal = RingGeometry(LENS_CX, PERSONAL_LENS_CY, RING_RADIUS, RING_STROKE),
            work = RingGeometry(LENS_CX, WORK_LENS_CY, RING_RADIUS, RING_STROKE),
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
