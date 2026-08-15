package dev.rgarjoaba.claudestatus.rear

import android.content.Context
import android.content.Intent

/**
 * Persisted ring geometry, and the one place that knows how to push it to the panel.
 *
 * Lives in the DEFAULT process. RearActivity runs in ":rear" and does not read these
 * preferences: SharedPreferences caches per process, so a value written here would not
 * reliably be visible there, and MODE_MULTI_PROCESS is deprecated and unreliable.
 * Instead the panel is *told* — [broadcast] sends the same `cx1/cy1/r1/...` extras that
 * PanelGeometry.fromExtras already understands, which is the mechanism the adb nudges
 * used during calibration. The panel keeps geometry in memory; this class owns the
 * durable copy and re-sends it whenever the panel is (re)placed.
 *
 * Defaults are PanelGeometry.DEFAULT's measured values, so a fresh install renders
 * exactly as the calibrated build did and the sliders open on the real numbers rather
 * than on zero.
 */
class GeometryStore(private val context: Context) {

    data class Values(
        val cx: Int,
        val personalCy: Int,
        val workCy: Int,
        val radius: Int,
        val stroke: Int,
    )

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Values {
        val p = prefs
        return Values(
            cx = p.getInt(KEY_CX, DEFAULT.cx),
            personalCy = p.getInt(KEY_CY1, DEFAULT.personalCy),
            workCy = p.getInt(KEY_CY2, DEFAULT.workCy),
            radius = p.getInt(KEY_R, DEFAULT.radius),
            stroke = p.getInt(KEY_STROKE, DEFAULT.stroke),
        )
    }

    fun save(v: Values) = prefs.edit()
        .putInt(KEY_CX, v.cx)
        .putInt(KEY_CY1, v.personalCy)
        .putInt(KEY_CY2, v.workCy)
        .putInt(KEY_R, v.radius)
        .putInt(KEY_STROKE, v.stroke)
        .apply()

    fun reset() = prefs.edit().clear().apply()

    /**
     * Pushes [v] to the panel.
     *
     * Carries NO `state` extra, deliberately. RearActivity's receiver applies geometry
     * from every ACTION_STATE broadcast but only re-renders the payload when `state` is
     * actually present — so a geometry-only nudge moves the rings without disturbing
     * whatever the panel is currently showing, and cannot knock it out of calibrate
     * mode. See RearActivity.receiver.
     *
     * setPackage scopes the broadcast to us; the receiver is exported (it has to be, the
     * shell sends to it) so an unscoped broadcast would be visible to any app.
     *
     * Both rings take the same cx and the same radius on purpose. They sit in one
     * vertical column on the physical panel, and an earlier live-tuning pass that
     * allowed them to drift apart left one ring a pixel off the other's column.
     */
    fun broadcast(v: Values) {
        context.sendBroadcast(
            Intent(RearActivity.ACTION_STATE).apply {
                setPackage(context.packageName)
                putExtra("cx1", v.cx)
                putExtra("cy1", v.personalCy)
                putExtra("r1", v.radius)
                putExtra("cx2", v.cx)
                putExtra("cy2", v.workCy)
                putExtra("r2", v.radius)
                putExtra("stroke", v.stroke)
            }
        )
    }

    /** Convenience for callers that just want the panel to match what is stored. */
    fun push() = broadcast(load())

    companion object {
        private const val PREFS = "geometry"
        private const val KEY_CX = "cx"
        private const val KEY_CY1 = "cy1"
        private const val KEY_CY2 = "cy2"
        private const val KEY_R = "radius"
        private const val KEY_STROKE = "stroke"

        /** PanelGeometry.DEFAULT, as integers. Keep these in step with it. */
        val DEFAULT = Values(cx = 150, personalCy = 144, workCy = 420, radius = 128, stroke = 25)

        /**
         * Slider bounds. Wide enough to be useful, tight enough that a stray drag
         * cannot put a ring somewhere it can never be seen — the panel is 904x572 and
         * the lens column sits inside the left ~300px.
         */
        val CX_RANGE = 0..300
        val CY1_RANGE = 0..300
        val CY2_RANGE = 280..572
        val RADIUS_RANGE = 60..200
        val STROKE_RANGE = 4..60
    }
}
