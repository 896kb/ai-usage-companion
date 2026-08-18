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
 * There is no account field. A ring belongs to the account in the same position — ring
 * one to the first account, ring two to the second — so reordering the accounts carries
 * the rings with them and there is nothing to keep in step by hand.
 *
 * That does cost something worth naming: both rings can no longer be pointed at one
 * account. It was possible and nobody asked for it, and it required a dropdown on the
 * main screen whose meaning ("which account does this ring belong to") was the hardest
 * thing on that screen to explain.
 */
@Serializable
data class RingSpec(
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
 * One account's appearance. Identity — which credential, which slug — is not here.
 *
 * Every field is nullable and null means "use the built-in", NOT "blank". That is what
 * lets a default change later and reach everyone who never overrode it; storing the
 * resolved value at first write would freeze today's defaults into every config ever
 * saved.
 *
 * @param accent drives this account's ring, heading, row labels and plan tag together
 * @param mascotBody the mascot's body colour
 * @param mascotAccent its hardware — headband, dumbbells, flag pole
 */
@Serializable
data class AccountStyle(
    val label: String? = null,
    val accent: Int? = null,
    val mascotBody: Int? = null,
    val mascotAccent: Int? = null,
    val showMascot: Boolean = true,
    /**
     * Which windows this account shows, in this order. Null means "whatever the panel's
     * default is" — the same null-means-inherit rule as the colours above, so a later
     * change to that default reaches anyone who never chose for themselves.
     *
     * Per account rather than per panel because the accounts are not alike: a scoped
     * weekly limit matters on the account that actually uses that model and is noise on
     * the other.
     */
    val barOrder: List<String>? = null,
)

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
    val rings: List<RingSpec> = listOf(RingSpec(), RingSpec()),
    /**
     * The order accounts appear in, top to bottom, as indices into Account.entries.
     *
     * Stored as an order rather than by reordering the accounts list itself, because
     * every persisted store — tokens, history, alert thresholds — keys on the account's
     * own identity. Shuffling that list would renumber those keys and orphan them, which
     * is the same trap that made labels unusable as keys.
     */
    val accountOrder: List<Int> = emptyList(),
    val ringLayout: RingLayout = RingLayout(),
    /** Default window order, for accounts that have not chosen their own. */
    val barOrder: List<String> = listOf("session", "weekly_all", "weekly_scoped"),
    val showClock: Boolean = false,
    val showBattery: Boolean = false,
    /** Colour for both extras. Defaults to the panel's green rather than DIM: they are
     *  small and sit alone in a corner, and dim made them read as disabled. */
    val extrasColor: Int = Panel.Palette.WORK,
    val art: ArtConfig = ArtConfig(),
    /** Appearance per account, by index. Short lists are fine — a missing entry is
     *  the same as an entry of all-nulls: use the built-ins. */
    val accounts: List<AccountStyle> = listOf(AccountStyle(), AccountStyle()),
) {

    /** This account's appearance, or an all-defaults one if it has no entry. */
    fun styleFor(index: Int): AccountStyle = accounts.getOrElse(index) { AccountStyle() }

    /**
     * The windows this account shows, in order.
     *
     * NEVER EMPTY. An account with every bar switched off is a heading, a mascot and
     * nothing to stand on — indistinguishable on the panel from an account whose data
     * failed to load. The UI stops you turning the last one off; this is the backstop
     * for a config edited by hand or written by an older build.
     */
    fun barsFor(index: Int): List<String> {
        val chosen = styleFor(index).barOrder ?: barOrder
        return chosen.ifEmpty { barOrder }
    }

    /**
     * The account list with one entry edited, GROWN if it does not reach that far.
     *
     * A stored config can be shorter than the number of accounts — it was written when
     * there were fewer, or by hand. Editing account 1 of a one-entry list must add the
     * second rather than silently do nothing, which is what a plain map would do.
     */
    fun withStyle(index: Int, edit: (AccountStyle) -> AccountStyle): List<AccountStyle> {
        val grown = accounts.toMutableList()
        while (grown.size <= index) grown.add(AccountStyle())
        grown[index] = edit(grown[index])
        return grown
    }

    /**
     * Account indices in display order, with any the order does not mention appended.
     *
     * Tolerating a partial or stale order matters: one written when there were two
     * accounts must not hide a third, and a duplicate or out-of-range entry must not
     * drop a real account off the panel.
     */
    fun orderedAccounts(count: Int): List<Int> {
        val seen = LinkedHashSet<Int>()
        accountOrder.filter { it in 0 until count }.forEach { seen.add(it) }
        (0 until count).forEach { seen.add(it) }
        return seen.toList()
    }

    /** The ring list with one entry edited, grown if short — same reasoning. */
    fun withRing(index: Int, edit: (RingSpec) -> RingSpec): List<RingSpec> {
        val grown = rings.toMutableList()
        while (grown.size <= index) grown.add(RingSpec())
        grown[index] = edit(grown[index])
        return grown
    }

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
