package dev.kb896.aiusage.data

import dev.kb896.aiusage.Panel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A face for the panel's text other than its theme's own.
 *
 * Either one the app ships ([bundled], by its PanelType id) or files on the phone — a
 * system family's, or one imported into the app's own folder. The files are named here,
 * weights and all, rather than looked up again by the family's name: the rear process
 * builds the face from them directly, without scanning every font on the phone first.
 */
@Serializable
data class PanelFont(
    /** The family's own name, as the list shows it. */
    val name: String,
    val bundled: String? = null,
    val files: List<PanelFontFile> = emptyList(),
)

/** One file of a [PanelFont]: [index] into a collection, [variable] when it has a weight axis. */
@Serializable
data class PanelFontFile(
    val path: String,
    val index: Int = 0,
    val weight: Int = 400,
    val variable: Boolean = false,
)

/** Which window a ring reports on. */
@Serializable
enum class RingWindow { SESSION, WEEKLY_ALL, WEEKLY_SCOPED }

/**
 * How the media row draws the cover.
 *
 * [SQUARE] is the cover as the player supplied it. [VINYL] cuts it to a disc and turns
 * it while the track plays — a record rather than a thumbnail, and it stops dead when
 * the music does, which makes it a second readout of the transport state rather than
 * decoration that happens to move.
 */
enum class MediaArt { SQUARE, VINYL }

/**
 * What about that window the ring shows.
 *
 * [NONE] is a real choice, not an absence of one. The rings are drawn concentric with
 * the phone's camera lenses, which is lovely on a panel that wants them and clutter on
 * one that does not — a bare lens surround is a legitimate look, and there was no way
 * to ask for it.
 *
 * Appended rather than inserted: these are stored by name, and the settings list maps
 * position to entry, so order is load-bearing in two directions.
 */
@Serializable
enum class RingMetric { TIME_TO_RESET, UTILIZATION, NONE }

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

        /**
         * The rings for the phone this runs on, in panel units (the 17 Pro's 904x572;
         * the panel is scaled to the display it is on — see RearDialView.fitX).
         *
         * The 17 Pro Max ("popsicle"): Xiaomi's own rear-screen app keeps one geometry
         * per device, the width of the lens column — 104.5dp on the Pro, 105.2dp on the
         * Pro Max, both about 296px at the rear displays' 450dpi. So the lenses are the
         * same pixel size on both, and the Pro Max's display is simply wider and taller
         * round them; scaling the Pro's rings up would put them 12px too far right and
         * 8% too big. These are the Pro's pixels divided by the Pro Max's scale, with
         * the lens column centred on its taller display. A starting point measured from
         * the app, not from a Pro Max — Settings → Ring layout takes it from there.
         */
        fun forDevice(): RingLayout = when (android.os.Build.DEVICE) {
            "popsicle" -> RingLayout(cx = 139, cyFirst = 154, cySecond = 418, radius = 121, stroke = 24)
            else -> RingLayout()
        }
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
/** What the panel's background is. See PanelConfig.effectiveBackdrop. */
@Serializable
enum class BackdropKind { COLOR, IMAGE, VIDEO, SHADER }

/**
 * How a video background plays. LINEAR runs once, forward, when the panel opens and holds
 * its last frame. YOYO runs forward when the panel opens and back to its start when it is
 * locked, double-tapped or goes into always-on. LINEAR_LOOP goes round and round; PING_PONG
 * goes forward and back, forward and back. Going backwards plays the reversed copy.
 */
@Serializable
enum class VideoPlayMode { LINEAR, YOYO, LINEAR_LOOP, PING_PONG }

/**
 * A video background: the original the user picked (kept as a document URI the app holds
 * permission for, so it can be edited again), the clip made from it for the panel, and how
 * it plays. The clip is already cropped, scaled to the rear display and trimmed — the
 * trim and crop are kept to reopen the editor where it was left.
 */
@Serializable
data class VideoConfig(
    val originalUri: String? = null,
    val clipPath: String? = null,
    /** The clip played backwards, for YOYO's return and PING_PONG; null for clips cut before it existed. */
    val reversePath: String? = null,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = 0,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
    /** As the picture's: how much of the video shows over the panel's ground colour. */
    val opacity: Float = 1f,
    /** The old switch, before there were four ways to play: read only through [mode]. */
    val loop: Boolean = true,
    /** How it plays; null in configs saved before the choice. See [mode]. */
    val playback: VideoPlayMode? = null,
    /** The old switches, before the eases had lengths: read only through [easeInWallMs] / [easeOutWallMs]. */
    val easeIn: Boolean = false,
    val easeOut: Boolean = false,
    /**
     * How long, on screen, it takes to speed up from slow and to slow into a stop — at a
     * run's ends and at every stop and start. 0 for none, up to [MAX_EASE_MS]; null in
     * configs saved before there were lengths. See EaseCurve.
     */
    val easeInMs: Int? = null,
    val easeOutMs: Int? = null,
    /** The clip's middle frame's brightness, GlassLuminance.SIDE squared cells, row by row. */
    val luminance: List<Float> = emptyList(),
) {
    val easeInWallMs: Int get() = easeInMs ?: if (easeIn) DEFAULT_EASE_MS else 0
    val easeOutWallMs: Int get() = easeOutMs ?: if (easeOut) DEFAULT_EASE_MS else 0

    /** How it was asked to play: the choice, or the old switch's meaning. */
    val mode: VideoPlayMode
        get() = playback ?: if (loop) VideoPlayMode.LINEAR_LOOP else VideoPlayMode.LINEAR

    companion object {
        /** What an ease that was simply on lasted: a second of clip, about two on screen. */
        const val DEFAULT_EASE_MS = 2000
        const val MAX_EASE_MS = 2500
    }

    /** How it can play: going backwards needs the reversed copy, and without it the nearest forward way. */
    val playable: VideoPlayMode
        get() = when {
            reversePath != null -> mode
            mode == VideoPlayMode.YOYO -> VideoPlayMode.LINEAR
            mode == VideoPlayMode.PING_PONG -> VideoPlayMode.LINEAR_LOOP
            else -> mode
        }
}

@Serializable
data class ArtConfig(
    val imagePath: String? = null,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val zoom: Float = 1f,
    /**
     * How much of the picture shows, 0..1, over the panel's ground colour — the colour
     * behind it. 1 is the picture alone, as it always was. Only the background's picture
     * reads it; the artwork is always whole.
     */
    val opacity: Float = 1f,
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
    /**
     * The whole app's look. See PanelTheme — it decides the face, the glyphs and whether
     * surfaces refract, and those are not separable settings.
     *
     * FIRST FIELD, and default GLASS: a new install starts on glass. Existing panels keep
     * theirs — configs are stored with their defaults written out (encodeDefaults), so a
     * phone that has saved anything since themes existed has its theme on record. It is
     * the BACK's theme only: the front screen is always glass (see UiTheme).
     */
    val theme: PanelTheme = PanelTheme.GLASS,
    /** The panel's face, when it is not the theme's own; null is the theme's. See PanelType. */
    val font: PanelFont? = null,
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
    /**
     * A camera button in the bottom-right, which puts the system camera on this display.
     *
     * Off by default. It is the only control on a surface that is otherwise a readout —
     * everything else here is something to look at, and a panel that reacts to being
     * touched is a surprise unless it was asked for.
     */
    val showCamera: Boolean = true,

    /**
     * A close button on the left, which ripples the readout away and leaves the mascot
     * walking the bottom of the panel.
     *
     * Not a dismissal — the panel stays alive, drawing almost nothing. Killing it would
     * mean the app could not bring it back on its own, which is the failure this whole
     * project is built around avoiding.
     */
    val showClose: Boolean = false,
    val closeColor: Int = Panel.Palette.WORK,
    /**
     * The masthead's colour. Separate from the accounts' accents because it names the
     * panel rather than any account on it.
     */
    val titleColor: Int = Panel.Palette.WORK,

    /**
     * Per-extra colours, each falling back to [extrasColor] when unset.
     *
     * Nullable overrides rather than three plain colours, so a config saved before these
     * existed keeps whatever [extrasColor] it was given instead of silently reverting to
     * the default green. The fallback is resolved at read time — see [clockTint] and its
     * neighbours.
     */
    val clockColor: Int? = null,
    val batteryColor: Int? = null,
    val dateColor: Int? = null,
    val cameraColor: Int? = null,
    /** The weather line's colour; null means "whatever the date is", which it was born with. */
    val weatherColor: Int? = null,
    /** The table lights' colour; null for the clock's. */
    val tableColor: Int? = null,
    /** Table lights at all: the margins glowing when the phone lies on its face. On by default, as they always were. */
    val showTableLights: Boolean = true,

    /** The date, under the clock. Off by default, like the other two. */
    val showDate: Boolean = false,

    /**
     * Weather on the panel's glass: [Weather.OFF], [Weather.RAIN] or [Weather.SNOW].
     *
     * A STRING RATHER THAN A BOOLEAN PAIR, because it is one choice with three answers
     * and two switches would let somebody ask for rain and snow at once. Stored as the
     * shader's own names so the setting reads the same in the file as it does in the code.
     */
    val weather: String = "off",
    /** What was typed in the Place row. Kept verbatim so the field shows it back. */
    val weatherPlace: String = "",
    /** The geocoder's answer for [weatherPlace] as "name, country"; empty when unresolved. */
    val weatherPlaceLabel: String = "",
    val weatherLat: Double? = null,
    val weatherLon: Double? = null,
    /** Ask the phone where it is before each fetch. Needs the coarse permission. */
    val weatherUseDevice: Boolean = false,

    /**
     * Walk the panel through itself, on a loop, with nobody touching it.
     *
     * FOR FILMING, and that is the whole of its design. The panel lives on the back of a
     * phone, so showing what it does means either a hand in every shot or a surface that
     * demonstrates itself — and a hand reaching across a 904-unit display hides most of
     * what it came to show. On a loop it can be filmed in one take from a tripod.
     *
     * It also holds the display awake, because a demo that lets the screen doze after
     * thirty seconds is a demo that stops halfway through the first take.
     */
    val demoMode: Boolean = false,

    /**
     * Hold the rear display awake for as long as the panel is up.
     *
     * OFF BY DEFAULT, and that default is a measurement rather than a taste. Held awake
     * with music playing, the panel costs about 110% of one core, sustained — it is the
     * single largest thing this app does. Released, MIUI drops the rear screen into its
     * own always-on doze after ten seconds (see subscreen_display_time), where the panel
     * stops drawing entirely; the last frame stays lit and nothing is spent keeping it
     * there.
     *
     * On is for people who would rather the readout kept moving than the battery lasted,
     * which was the original behaviour and is a perfectly reasonable thing to want.
     */
    val keepPanelAwake: Boolean = false,
    /**
     * Always-on: instead of going dark, the panel settles into a dim, frozen page — no
     * animation, the backdrop faded to black — and sleeps only in a pocket. A double tap
     * on it raises the unlock slider; the swipe brings the full panel back.
     */
    val alwaysOn: Boolean = false,
    /** In always-on, the battery level above the clock. */
    val aodBattery: Boolean = false,
    /**
     * In always-on, let the display go when the rear proximity sensor reads covered — a
     * pocket, a bag. Off by default: on a phone whose sensor reads covered in the hand,
     * it is the panel going black for no visible reason. See RearActivity.pocketWatch.
     */
    val aodPocket: Boolean = false,
    /**
     * Show the camera tip — how to close the camera from the back — the next time the
     * panel's camera button is pressed. On until it has been swiped through once; the
     * Advanced switch turns it back on for the next time.
     */
    val cameraTip: Boolean = true,
    /** In always-on, the date line on the clock page (outside it, the Date switch decides). */
    val aodDate: Boolean = true,
    /** The weather line on the clock page — the reading and the place. Off leaves only the weather on the glass. */
    val showWeatherLine: Boolean = true,
    /** In always-on, the weather line — independent of [showWeatherLine], as the date's is of the Date switch. */
    val aodWeather: Boolean = true,
    /**
     * The whole panel drawn as if on a curved tube — scanlines, a shadow mask, a
     * vignette, the picture bent outwards — the terminal's look on 896.ro. Off by
     * default: it is a look, and it bends the picture. See CrtScreen.
     */
    val crt: Boolean = false,

    /**
     * Mirror other apps' notifications onto the panel.
     *
     * Off by default, and [showNotificationText] is a SEPARATE opt-in, because this
     * display faces away from its owner: everything on it is shown to whoever is behind
     * them rather than to them. Choosing to see that a message arrived is a different
     * decision from choosing to broadcast what it said.
     */
    val showNotifications: Boolean = false,
    val showNotificationText: Boolean = false,
    val notificationColor: Int? = null,

    /**
     * The strip's own ground, falling back to the panel's background.
     *
     * Separate because the strip is a thing laid OVER the panel — giving it a ground of
     * its own is how it stops reading as a hole cut in the readout. Null rather than a
     * copy of [background], so changing the panel's colour carries the strip with it
     * until someone deliberately parts them.
     */
    val notificationBg: Int? = null,

    /**
     * Media controls in place of the bottom artwork, while something is playing.
     *
     * Off by default like everything else that changes the panel, and for one reason
     * beyond consistency: this one TAKES A REGION the user has already decorated. The
     * artwork slot accepts a custom image, and quietly covering someone's picture the
     * first time they play a song is not a feature arriving, it is a picture going
     * missing.
     *
     * It also rides on notification access, granted for a different feature entirely
     * (see Setup's step and MediaWatcher's note). Turning on a media player as a side
     * effect of agreeing to mirror notifications is not something the user asked for.
     */
    val showMedia: Boolean = false,
    val mediaColor: Int? = null,
    val mediaArt: MediaArt = MediaArt.SQUARE,


    /**
     * Synced lyrics, curved around the record on the media page.
     *
     * A SEPARATE opt-in from [showMedia], and the only setting in this file that is one
     * for a reason beyond taste: it is the single feature in the app that sends anything
     * about the user anywhere. Switching it on means each new track's title, artist,
     * album and length go to lrclib.net — see LyricsApi, which carries the rest of that
     * argument, and LyricsCache, which makes it once per recording rather than once per
     * play.
     *
     * So it cannot ride in on "show media", the way it would if it were merely another
     * part of the music row. Somebody who wanted a play button on the back of their phone
     * did not thereby ask to tell a stranger what they listen to. Off is the default and
     * off means no request is ever built.
     */
    val showLyrics: Boolean = false,

    val showClock: Boolean = false,
    val showBattery: Boolean = false,
    /** Colour for both extras. Defaults to the panel's green rather than DIM: they are
     *  small and sit alone in a corner, and dim made them read as disabled. */
    val extrasColor: Int = Panel.Palette.WORK,
    val art: ArtConfig = ArtConfig(),
    /**
     * A picture behind the whole readout, cropped the same way the art is.
     *
     * The same shape of setting as [art] and deliberately the same type: both are "an
     * image, panned and zoomed into a region", and the region differing does not make
     * them different ideas. Null path means the flat [background] colour, which is what
     * every panel had before this existed.
     */
    val backdrop: ArtConfig = ArtConfig(),
    /** Which background the panel has; null in configs saved before there was a choice. See [effectiveBackdrop]. */
    val backdropKind: BackdropKind? = null,
    val video: VideoConfig = VideoConfig(),
    /** The Shader background's choice. Only the front screen's silk, for now. */
    val shader: String = "silk",
    /** Appearance per account, by index. Short lists are fine — a missing entry is
     *  the same as an entry of all-nulls: use the built-ins. */
    val accounts: List<AccountStyle> = listOf(AccountStyle(), AccountStyle()),
) {

    /** This account's appearance, or an all-defaults one if it has no entry. */
    /** [clockColor] or the shared extras colour. Same for the two below. */
    val clockTint: Int get() = clockColor ?: extrasColor
    /** The rings as drawn: what the user set, or the phone's own default while untouched. */
    val effectiveRingLayout: RingLayout get() = if (ringLayout == RingLayout()) RingLayout.forDevice() else ringLayout
    val batteryTint: Int get() = batteryColor ?: extrasColor
    val dateTint: Int get() = dateColor ?: extrasColor
    val weatherTint: Int get() = weatherColor ?: dateTint
    val tableTint: Int get() = tableColor ?: clockTint
    val cameraTint: Int get() = cameraColor ?: extrasColor
    val notificationTint: Int get() = notificationColor ?: extrasColor
    val mediaTint: Int get() = mediaColor ?: extrasColor
    val notificationGround: Int get() = notificationBg ?: background

    /**
     * The background in force. A config from before the choice existed had a picture or a
     * colour, and opens on whichever it had.
     */
    val effectiveBackdrop: BackdropKind
        get() = backdropKind ?: if (backdrop.imagePath != null) BackdropKind.IMAGE else BackdropKind.COLOR

    /**
     * Every colour the panel is drawn in, once each, in a stable order — the picker's
     * palette: "make this match that" is most of what choosing a colour is, and these are
     * the things there are to match. Resolved as drawn (an unset colour is the one it falls
     * back to), opaque, and told apart by their RGB alone.
     */
    fun usedColours(): List<Int> {
        val all = buildList {
            add(background)
            add(titleColor); add(closeColor); add(extrasColor)
            add(clockTint); add(batteryTint); add(dateTint); add(weatherTint); add(tableTint)
            add(cameraTint); add(notificationTint); add(notificationGround); add(mediaTint)
            addAll(rampColors)
            accounts.forEachIndexed { i, a ->
                add(a.accent ?: if (i == 0) Panel.Palette.PERSONAL else Panel.Palette.WORK)
                a.mascotBody?.let(::add)
                a.mascotAccent?.let(::add)
            }
        }
        return all.map { it or (0xFF shl 24) }.distinct()
    }

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
