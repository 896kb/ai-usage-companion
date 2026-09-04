package dev.kb896.aiusage

import android.os.Build

/**
 * The hardware facts that differ between the phones this panel runs on — the secondary
 * display it lives on, that display's pixel size and camera cutout, and the tail of its
 * physical id as SurfaceFlinger prints it.
 *
 * These used to be `const val`s on [Panel], true for exactly one device (the Xiaomi 17
 * Pro). The panel now also targets the Galaxy Z Flip cover screen, which is a different
 * size, a different shape, and a different physical id — so the numbers move to a profile
 * chosen at runtime by [DeviceProfiles.current], and [Panel] reads through to it.
 *
 * NOT device-specific and therefore still on [Panel]: the palette and the utilisation
 * ramp. Those are the design, not the hardware.
 */
interface DeviceProfile {
    /** Logical id of the display the panel is placed on. Both known devices use 1. */
    val displayId: Int

    /** The panel display's size in pixels, in the orientation the panel draws. */
    val width: Int
    val height: Int

    /**
     * Camera cutout as a left-edge strip: x in [0, cutoutRight) is cutout, the rest is
     * live. This models the Xiaomi's twin lenses down the left side. The Flip's cutout is
     * a bottom-right corner instead, which this single number cannot describe — it is 0
     * there for now, and a richer cutout shape is part of the Flip layout work, not this
     * structural split.
     */
    val cutoutRight: Int

    /**
     * Suffix of the panel display's physical id as it appears in
     * `dumpsys SurfaceFlinger` / `Display <id> (...) HWC layers:`. The keep-alive matches
     * on the tail rather than the full 19-digit value — see RearUserService.panelWhere().
     */
    val physicalIdSuffix: String

    /**
     * Whether the panel draws the lens rings — the arcs concentric with the phone's rear
     * camera lenses. They only make sense on a display the lenses poke through, like the
     * Xiaomi's, where a ring frames each lens. The Flip's cover screen has no lens under
     * it to encircle, so it draws none — the bars already carry every reading the rings
     * did. Gated at the device level, so config can never turn them back on there.
     */
    val hasRings: Boolean
}

/** Xiaomi 17 Pro (`25098PN5AC`) rear display. The values the panel was first built for. */
object XiaomiRearProfile : DeviceProfile {
    override val displayId = 1
    override val width = 904
    override val height = 572
    override val cutoutRight = 296
    // physical id local:4630946949513469332
    override val physicalIdSuffix = "332"
    override val hasRings = true
}

/**
 * Galaxy Z Flip 6 (`SM-F741B`) cover screen. Measured on device 2026-09-04 via
 * `cmd display get-displays` / `dumpsys SurfaceFlinger --display-id`: display 1, 748x720,
 * density 340, installOrientation ROTATION_90, camera cutout in the bottom-right corner,
 * physical id 4630946638498144900.
 */
object Flip6CoverProfile : DeviceProfile {
    override val displayId = 1
    override val width = 748
    override val height = 720
    // The Flip's cutout is a bottom-right corner, not a left strip — see cutoutRight's doc.
    override val cutoutRight = 0
    override val physicalIdSuffix = "900"
    // No lens under the cover to frame — the bars carry the readings instead.
    override val hasRings = false
}

object DeviceProfiles {
    /**
     * The profile for the device this build is running on, chosen by model.
     *
     * The default is the Xiaomi profile — it is what an unknown device gets, and it is
     * what plain-JVM and Robolectric unit tests get (where `Build.MODEL` is null or
     * "robolectric"), so the existing geometry tests keep asserting the values they were
     * written against.
     *
     * Flip 7 (`SM-F766B`, cover ~948x1048) slots in here as a third profile once one can
     * be measured — the wiring is already in place.
     */
    val current: DeviceProfile = when (Build.MODEL) {
        "SM-F741B" -> Flip6CoverProfile
        else -> XiaomiRearProfile
    }
}
