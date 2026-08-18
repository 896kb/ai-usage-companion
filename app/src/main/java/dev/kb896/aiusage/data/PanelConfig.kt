package dev.kb896.aiusage.data

import dev.kb896.aiusage.Panel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which window a ring reports on. */
@Serializable
enum class RingWindow { SESSION, WEEKLY_ALL, WEEKLY_SCOPED }

/** What about that window the ring shows. */
@Serializable
enum class RingMetric { TIME_TO_RESET, UTILIZATION }

/**
 * What one ring reports.
 *
 * @param account index of the account this ring belongs to
 */
@Serializable
data class RingSpec(
    val account: Int = 0,
    val window: RingWindow = RingWindow.SESSION,
    val metric: RingMetric = RingMetric.TIME_TO_RESET,
)

/**
 * Where the rings sit on the panel, in panel units.
 *
 * These are measured against one specific phone's rear display and lens positions. They
 * are configurable because a different model in the same family has a different panel
 * and different lens centres, and nothing but a human eye on the actual device can
 * supply those numbers.
 *
 * Both rings share [cx], [radius] and [stroke] deliberately. The lenses are in a single
 * vertical column, so independent X or radius per ring can only ever make them disagree
 * — and two concentric rings that do not match read as a rendering fault rather than a
 * setting.
 */
@Serializable
data class RingLayout(
    val cx: Int = 150,
    val cyFirst: Int = 144,
    val cySecond: Int = 420,
    val radius: Int = 128,
    val stroke: Int = 25,
) {
    companion object {
        /**
         * Bounds for the controls that edit these.
         *
         * Generous rather than tight. They exist to stop a slider producing a value that
         * cannot be drawn at all, not to keep the rings where they look right on one
         * particular phone — the whole reason these are adjustable is that a different
         * rear display puts the lenses somewhere else.
         */
        val CX_RANGE = 0..300
        val CY_FIRST_RANGE = 0..300
        val CY_SECOND_RANGE = 280..572
        val RADIUS_RANGE = 60..200
        val STROKE_RANGE = 4..60
    }
}

/**
 * Bottom art. [imagePath] null means the built-in mark.
 *
 * Pan and zoom are fractions of the art REGION, not of the panel, so they survive a
 * change to where that region is.
 */
@Serializable
data class ArtConfig(
    val imagePath: String? = null,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
)

/**
 * Everything about the panel a user may change.
 *
 * Every field is defaulted, and the defaults are the values that were previously
 * compiled in, so a device with no stored config draws exactly what it drew before this
 * existed. That is not a convenience — it is what makes the config safe to add to a
 * payload a running panel is already consuming.
 *
 * This is the FULL surface, not just the part wired up first. Landing the whole schema
 * at once means later work adds controls rather than migrating configs already written
 * by a shipped build.
 *
 * Colours are per ACCOUNT, and are not named primary/secondary. Those names encode a
 * two-ness that is a current limitation rather than a decision: the panel is intended to
 * scroll more than two accounts, and "secondary" means nothing for the fourth.
 */
@Serializable
data class PanelConfig(
    val title: String = "CLAUDE USAGE",
    val background: Int = Panel.Palette.BG,
    /**
     * Ramp colours from mild to spent, ascending. Positions stay fixed and only the
     * colours are chosen: a user dragging stop positions can invert the ramp and make a
     * full bar look emptier than a half one.
     */
    val rampColors: List<Int> = Panel.RAMP_STOPS.map { it.second },
    val rings: List<RingSpec> = listOf(RingSpec(account = 0), RingSpec(account = 1)),
    val ringLayout: RingLayout = RingLayout(),
    /** Window kinds top to bottom within each account block, by wire name. */
    val barOrder: List<String> = listOf("session", "weekly_all", "weekly_scoped"),
    val showClock: Boolean = false,
    val showBattery: Boolean = false,
    val art: ArtConfig = ArtConfig(),
) {
    companion object {
        val DEFAULT = PanelConfig()

        /** Lenient so a newer payload cannot crash an older :rear process mid-install. */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(config: PanelConfig): String = json.encodeToString(serializer(), config)

        /** Never throws. A config that cannot be read is a config that was not set. */
        fun decode(raw: String?): PanelConfig =
            if (raw.isNullOrBlank()) DEFAULT
            else try { json.decodeFromString(serializer(), raw) } catch (e: Exception) { DEFAULT }
    }
}
