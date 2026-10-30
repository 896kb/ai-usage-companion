package dev.kb896.aiusage.data

import dev.kb896.aiusage.Panel
import kotlinx.serialization.SerialName
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
/**
 * How the back's glass answers what is behind it. NONE keeps one tint; GLOBAL reacts to
 * the whole background's brightness; SHARD lets each piece react to what is right behind
 * it, as the back always has.
 */
@Serializable
enum class GlassReactivity { NONE, GLOBAL, SHARD }

/**
 * The back's glass, chosen in Glass settings: one [color] for every piece, at [intensity]
 * (0 clear to 1, half opaque), reacting as [reactivity] says. Until it is chosen
 * (PanelConfig.glassStyle null) each piece keeps its own built-in tint.
 */
@Serializable
data class GlassStyle(
    val color: Int = 0xFFFFFFFF.toInt(),
    val intensity: Float = DEFAULT_INTENSITY,
    val reactivity: GlassReactivity = GlassReactivity.SHARD,
) {
    /** The colour at its intensity: up to half opaque. */
    fun tint(): Int =
        (color and 0x00FFFFFF) or (Math.round(intensity.coerceIn(0f, 1f) * MAX_ALPHA) shl 24)

    companion object {
        /** The most a piece of glass is ever tinted: half opaque. */
        const val MAX_ALPHA = 127.5f
        /** The main glass's built-in tint (Glass.TINT, 0x26 white) as an intensity. */
        const val DEFAULT_INTENSITY = 38f / MAX_ALPHA
        val DEFAULT = GlassStyle()
    }
}

/**
 * The App Portal's grid on the back: [rows] by [columns] apps a page. Pinned apps past one
 * page go on further pages, swiped sideways. Fewer cells are bigger cells (PortalSheet.layout).
 * Stored by name; [R3C4] — three rows of four — is the grid the portal was born with.
 */
@Serializable
enum class PortalGrid(val rows: Int, val columns: Int) {
    R3C4(3, 4), R3C3(3, 3), R2C3(2, 3), R2C2(2, 2);

    val perPage: Int get() = rows * columns

    /** As the front's choice says it: rows × columns. */
    val label: String get() = "$rows × $columns"
}

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
    /**
     * The real footage cut past the frame on each side, as a share of the panel — what
     * parallax moves into without enlarging the clip. 0 for clips cut before parallax.
     */
    val margin: Float = 0f,
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
     * The second ring's settings while one connected account has both — see RingOwners.
     * Its own, not the second account's, and kept while a second account has its ring back.
     */
    val pairedRing: RingSpec = RingSpec(metric = RingMetric.UTILIZATION),
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
     * [showCamera] and [showClose] are the USAGE page's (the readout's corners); these are
     * the INFO page's — the clock page — each page its own, so either, both or neither
     * shows them. Off by default. The colours are the info page's own, falling back to
     * the usage page's (see [cameraInfoTint], [closeInfoTint]).
     */
    val showCameraInfo: Boolean = false,
    val showCloseInfo: Boolean = false,
    val cameraInfoColor: Int? = null,
    val closeInfoColor: Int? = null,
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
     * Weather on the panel's glass: [Weather.OFF], [Weather.RAIN], [Weather.STORM] or [Weather.SNOW].
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
     * animation, the backdrop faded to black — and sleeps only in a pocket (pocketMode). A double tap
     * on it raises the unlock slider; the swipe brings the full panel back.
     */
    val alwaysOn: Boolean = false,
    /** In always-on, the battery level above the clock. */
    val aodBattery: Boolean = false,
    /**
     * Pocket mode: the rear proximity sensor covered while the back is lit — a pocket, a
     * bag — switches the back off at once, and uncovered it comes back as it was, the
     * slider up. Whenever the back is lit, not only in always-on, where it began. Off by
     * default: on a phone whose sensor reads covered in the hand, it is the panel going
     * black for no visible reason. See PocketWatch.
     *
     * STORED AS aodPocket, its name from when it was an always-on sub-switch: renaming the
     * key would have quietly turned it off for everybody who had turned it on.
     */
    @SerialName("aodPocket")
    val pocketMode: Boolean = false,
    /**
     * "Match the front notifications": a notification that leaves the front leaves the
     * back's list too, the list is brought in line with the front whenever the relay
     * connects, and a card swiped away on the back is cleared on the front. Off, the back
     * keeps its own list, as it always did.
     */
    val matchFrontNotifications: Boolean = false,
    /** In always-on, the clock on the clock page; off, it fades out with the mode. */
    val aodClock: Boolean = true,
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
    /** The media page's one colour, from before each part had its own: what they all start in. */
    val mediaColor: Int? = null,
    /**
     * The media page's parts, each coloured on its own: the playback ring and what goes
     * with it (times, scrubber, its icons), the volume ring and what goes with it, the
     * three transport buttons, the artist and the title. Null: the one media colour.
     */
    val mediaPlaybackColor: Int? = null,
    val mediaVolumeColor: Int? = null,
    val mediaButtonsColor: Int? = null,
    val mediaArtistColor: Int? = null,
    val mediaTitleColor: Int? = null,
    /** The lyrics round the record; null: the title's colour, which they had before. */
    val mediaLyricsColor: Int? = null,
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

    /** The usage page's corner row: its time, battery (and [showDate]), each with a colour. */
    val showClock: Boolean = false,
    val showBattery: Boolean = false,
    /**
     * The clock page's own big clock, date line and battery over the clock — apart from the
     * corner row's switches above, which they once shared. Null until set, and then they
     * behave as the shared switches did: see [infoClockOn], [infoDateOn], [infoBatteryOn].
     * Their colours fall back to the corner row's ([infoClockTint] and the rest).
     */
    val infoClock: Boolean? = null,
    val infoDate: Boolean? = null,
    val infoBattery: Boolean? = null,
    val infoClockColor: Int? = null,
    val infoDateColor: Int? = null,
    val infoBatteryColor: Int? = null,
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
    /**
     * Depth for the background (a picture, a video, the shader): it shifts a little as the
     * phone tilts, and as the panel's pages scroll. See Parallax.
     */
    val parallaxTilt: Boolean = false,
    val parallaxScroll: Boolean = false,
    /** The back's glass, once chosen in Glass settings; null keeps each piece's built-in tint. */
    val glassStyle: GlassStyle? = null,
    /** The Shader background's choice. Only the front screen's silk, for now. */
    val shader: String = "silk",
    /** Appearance per account, by index. Short lists are fine — a missing entry is
     *  the same as an entry of all-nulls: use the built-ins. */
    val accounts: List<AccountStyle> = listOf(AccountStyle(), AccountStyle()),
    /**
     * App Portal: the apps pinned to the portal's grid on the back, in order, by package.
     * Packages rather than components: the launcher activity is asked for at launch time,
     * so an app that renames its main activity in an update still opens.
     */
    val portalApps: List<String> = emptyList(),
    /** The portal frame's colour (see PortalFrame). Default the original Electric Border's green. */
    val portalFrameColor: Int = 0xFF28FF85.toInt(),
    /**
     * App Portal: a density of its own for an app on the back, by package, in dpi. Some apps
     * are unreadable at the rear's default density; the shell applies an entry only while
     * that app is the guest on top, and the display goes back to its default otherwise.
     * No entry means the default. See RearDensity for the bounds.
     */
    val portalDpi: Map<String, Int> = emptyMap(),
    /** App Portal: how many apps a page of the back's grid holds. See PortalGrid. */
    val portalGrid: PortalGrid = PortalGrid.R3C4,
    /** App Portal: the frame round a guest app on the back (PortalFrame). On, as it always was. */
    val portalBorder: Boolean = true,
    /**
     * App Portal: the apps' names under their icons on the back. Off, the icons grow into
     * the names' room (PortalSheet.icon).
     */
    val portalAppNames: Boolean = true,
    /**
     * App Portal: apps kept right of the cameras on the rear ("Keep right of the cameras" in
     * the app's sheet on the front and under the DPI slider on the back), by package: each runs on
     * a private display (PrivateDisplay). Every other guest has the whole of display 1. Empty:
     * all fullscreen. Replaces the opt-out portalFullscreen, which
     * an older config may still carry — ignored.
     */
    val portalConfined: Set<String> = emptySet(),
    /**
     * App Portal: the "Send to back" tile is in Quick Settings, as far as the app was last
     * told — the add-tile request's answer, and PortalTileService's own added / removed /
     * listening. Ticks the Portal tab's second step (PortalGuide).
     */
    val portalTileAdded: Boolean = false,
    /** App Portal: the sheet has been opened on the back at least once (IRearEvents.onPortalOpened). The third step. */
    val portalSheetSeen: Boolean = false,
    /** App Portal: the Portal tab's guide card is down for good — finished, or dismissed early. */
    val portalGuideDone: Boolean = false,
) {

    /** This account's appearance, or an all-defaults one if it has no entry. */
    /** [clockColor] or the shared extras colour. Same for the two below. */
    val clockTint: Int get() = clockColor ?: extrasColor
    /** The rings as drawn: what the user set, or the phone's own default while untouched. */
    val effectiveRingLayout: RingLayout get() = if (ringLayout == RingLayout()) RingLayout.forDevice() else ringLayout
    val batteryTint: Int get() = batteryColor ?: extrasColor
    val dateTint: Int get() = dateColor ?: extrasColor
    val weatherTint: Int get() = weatherColor ?: infoDateTint
    val infoClockTint: Int get() = infoClockColor ?: clockTint
    val infoDateTint: Int get() = infoDateColor ?: dateTint
    val infoBatteryTint: Int get() = infoBatteryColor ?: batteryTint
    /** The clock page's big clock: always there until switched off. */
    val infoClockOn: Boolean get() = infoClock ?: true
    /** The clock page's date line: the Date switch's until set. */
    val infoDateOn: Boolean get() = infoDate ?: showDate
    /** The battery over the big clock: until set, only with no readout and the Battery switch on. */
    fun infoBatteryOn(noReadout: Boolean): Boolean = infoBattery ?: (noReadout && showBattery)
    val tableTint: Int get() = tableColor ?: clockTint
    val cameraTint: Int get() = cameraColor ?: extrasColor
    val cameraInfoTint: Int get() = cameraInfoColor ?: cameraTint
    val closeInfoTint: Int get() = closeInfoColor ?: closeColor
    val notificationTint: Int get() = notificationColor ?: extrasColor
    val mediaTint: Int get() = mediaColor ?: extrasColor
    val mediaPlaybackTint: Int get() = mediaPlaybackColor ?: mediaTint
    val mediaVolumeTint: Int get() = mediaVolumeColor ?: mediaTint
    val mediaButtonsTint: Int get() = mediaButtonsColor ?: mediaTint
    val mediaArtistTint: Int get() = mediaArtistColor ?: mediaTint
    val mediaTitleTint: Int get() = mediaTitleColor ?: mediaTint
    val mediaLyricsTint: Int get() = mediaLyricsColor ?: mediaTitleTint
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
            add(clockTint); add(batteryTint); add(dateTint); add(infoClockTint); add(infoDateTint); add(infoBatteryTint); add(weatherTint); add(tableTint)
            add(cameraTint); add(cameraInfoTint); add(closeInfoTint); add(notificationTint); add(notificationGround); add(mediaTint)
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

        /**
         * Lenient so a newer payload cannot crash an older :rear process mid-install — nor
         * wipe the config: a value an older build does not know (a new enum name, say) is
         * read as that field's default (coerceInputValues), not the whole config's.
         */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

        fun encode(config: PanelConfig): String = json.encodeToString(serializer(), config)

        /** Never throws. A config that cannot be read is a config that was not set. */
        fun decode(raw: String?): PanelConfig =
            if (raw.isNullOrBlank()) DEFAULT
            else try { json.decodeFromString(serializer(), raw) } catch (e: Exception) { DEFAULT }
    }
}
