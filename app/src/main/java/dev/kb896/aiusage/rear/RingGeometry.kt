package dev.kb896.aiusage.rear

import android.os.Bundle
import dev.kb896.aiusage.Panel

data class RingGeometry(val cx: Float, val cy: Float, val radius: Float, val stroke: Float)

data class PanelGeometry(val personal: RingGeometry, val work: RingGeometry) {

    companion object {
        /**
         * Lens centres, MEASURED on device f940914b (Xiaomi 17 Pro, 25098PN5AC) on
         * 2026-08-14 and confirmed visually by the user against the physical lenses.
         * Do not "correct" these back toward round numbers.
         */
        /**
         * Shared deliberately: the two lenses sit in one vertical column on the
         * physical panel, confirmed by the user. Keeping this a single constant means
         * a future nudge cannot desynchronise them — an earlier live-tuning pass did
         * exactly that, leaving one ring a pixel off the other's column.
         */
        private const val LENS_CX = 151f

        private const val PERSONAL_LENS_CY = 149f
        private const val WORK_LENS_CY = 424f

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
         * TWO CONSTRAINTS BOUND ANY CHANGE HERE, and they squeeze from both sides:
         *
         *   Outward — the panel edge, with room for the burn-in drift. The ring's
         *   outer edge is RING_OFFSET + LENS_RADIUS + RING_STROKE/2. The tightest
         *   margin is the upper lens at cy=147, and the drift swings +/-4px, so:
         *       LENS_RADIUS + RING_OFFSET + RING_STROKE/2  <=  147 - 4  =  143
         *   At offset 24 / stroke 28 the outer edge is 146 — it clipped at the top
         *   and bottom on every drift cycle. Offset 20 gives 142, a 5px margin.
         *
         *   Inward — the lens itself. The band's inner edge is
         *   LENS_RADIUS + RING_OFFSET - RING_STROKE/2, which must stay clear of 108
         *   or the ring disappears under the camera bump again. Offset 20 clears it
         *   by 6px.
         *
         * So the usable window is narrow: offset 20 with stroke 28 satisfies both.
         * If you widen the stroke, the offset must come down, and vice versa —
         * recompute both inequalities rather than adjusting one by eye.
         */
        private const val RING_OFFSET = 20f
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
