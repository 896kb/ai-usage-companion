package dev.kb896.aiusage.rear

import android.os.Bundle
import dev.kb896.aiusage.Panel

data class RingGeometry(val cx: Float, val cy: Float, val radius: Float, val stroke: Float)

/**
 * Where the two rings are drawn, in panel units.
 *
 * Built from [dev.kb896.aiusage.data.RingLayout], which is the persisted, user-editable
 * form. This type is the drawing form: floats, and one entry per ring.
 */
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
        private const val LENS_CX = 150f

        private const val PERSONAL_LENS_CY = 144f
        private const val WORK_LENS_CY = 420f

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
         * CURRENT VALUES (cx 150, personal cy 144, work cy 420, radius 128, stroke 25)
         * were chosen by eye on the device and are the user's, not a derivation. They
         * satisfy the inward constraint comfortably — inner edge 115.5 clears the lens
         * by 7.5px — and miss the outward one by half a pixel: the outer edge is 140.5
         * against a 140 budget at the upper ring (cy 144 minus the 4px drift). So at
         * the extreme of the drift cycle the top of the upper ring can lose half a
         * pixel. That is accepted deliberately: it is invisible, and it was measured
         * against the physical lenses, which beats the arithmetic. Do not "fix" it by
         * moving the ring off the lens it is drawn around.
         *
         * If you change the stroke or radius, recompute BOTH inequalities rather than
         * adjusting one by eye — and note the sliders (GeometryStore) can now move all
         * of this at runtime, so these constants are only the starting point.
         */
        private const val RING_OFFSET = 20f
        private const val RING_STROKE = 25f

        private const val RING_RADIUS = LENS_RADIUS + RING_OFFSET

        /** The drawing form of a stored [dev.kb896.aiusage.data.RingLayout]. */
        fun of(layout: dev.kb896.aiusage.data.RingLayout) = PanelGeometry(
            personal = RingGeometry(layout.cx.toFloat(), layout.cyFirst.toFloat(),
                layout.radius.toFloat(), layout.stroke.toFloat()),
            work = RingGeometry(layout.cx.toFloat(), layout.cySecond.toFloat(),
                layout.radius.toFloat(), layout.stroke.toFloat()),
        )

        val DEFAULT = PanelGeometry(
            personal = RingGeometry(LENS_CX, PERSONAL_LENS_CY, RING_RADIUS, RING_STROKE),
            work = RingGeometry(LENS_CX, WORK_LENS_CY, RING_RADIUS, RING_STROKE),
        )

    }
}
