package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.VideoPlayMode

enum class VideoAction {
    DROP, SLEEP, FREEZE, LOOP, RESTART_LOOP, PLAY_ONCE, KEEP,
    /** Yoyo: forward from where it is to its end, and rest there. */
    TO_END,
    /** Yoyo: backward from where it is to its start, and rest there. */
    TO_START,
    PING_PONG, RESTART_PING_PONG,
}

/**
 * What a video background's player should be doing, from the panel's state alone — kept
 * apart from the player so every case can be pinned by a test.
 *
 * DROP lets go of everything (no video any more); SLEEP only the decoder, keeping the last
 * frame to show when the display lights again — a dark display, or a panel nobody can see
 * (the front's preview with the app in the background). FREEZE holds the frame where it
 * is: always-on, the double tap's dim, a linear run behind the slider.
 *
 * [fresh] is the panel coming back — woken, unlocked, the dim called off, always-on left: a
 * linear run starts over, cross-fading from the frame it had stopped on, while a loop or a
 * ping-pong carries on from where it was. [changed] is the video itself changed — a new
 * clip, chosen, its way of playing or its ease switched — and starts even those over. A
 * yoyo only ever knows where it is going: to its end while the panel is open, back to its
 * start while it is not. KEEP is an open linear run between those moments: playing or
 * holding its last frame. [mode] is the way the clip can play (VideoConfig.playable).
 */
object VideoState {
    fun decide(
        isVideo: Boolean, hasClip: Boolean, attached: Boolean, displayAwake: Boolean, visible: Boolean,
        aod: Boolean, paused: Boolean, locked: Boolean, mode: VideoPlayMode, preview: Boolean,
        fresh: Boolean, changed: Boolean,
    ): VideoAction {
        if (!isVideo || !hasClip) return VideoAction.DROP
        if (!attached || !displayAwake || !visible) return VideoAction.SLEEP
        // The preview shows each way going round: what plays once, round and round; what
        // goes there and back, there and back.
        if (preview) return when (mode) {
            VideoPlayMode.LINEAR, VideoPlayMode.LINEAR_LOOP ->
                if (changed) VideoAction.RESTART_LOOP else VideoAction.LOOP
            VideoPlayMode.YOYO, VideoPlayMode.PING_PONG ->
                if (changed) VideoAction.RESTART_PING_PONG else VideoAction.PING_PONG
        }
        return when (mode) {
            VideoPlayMode.YOYO -> if (aod || paused || locked) VideoAction.TO_START else VideoAction.TO_END
            VideoPlayMode.LINEAR_LOOP -> when {
                aod || paused -> VideoAction.FREEZE
                changed -> VideoAction.RESTART_LOOP
                else -> VideoAction.LOOP
            }
            VideoPlayMode.PING_PONG -> when {
                aod || paused -> VideoAction.FREEZE
                changed -> VideoAction.RESTART_PING_PONG
                else -> VideoAction.PING_PONG
            }
            VideoPlayMode.LINEAR -> when {
                aod || paused || locked -> VideoAction.FREEZE
                fresh -> VideoAction.PLAY_ONCE
                else -> VideoAction.KEEP
            }
        }
    }

    /**
     * A player that was only just [created] has drawn nothing, so leaving it be would leave
     * the panel on its bare ground: it shows its frame instead.
     */
    fun afterOpen(action: VideoAction, created: Boolean): VideoAction =
        if (created && action == VideoAction.KEEP) VideoAction.FREEZE else action

    /**
     * The ease each pass of the preview runs with. The preview goes round what the back plays
     * once — a linear run, a yoyo's legs — so each pass is eased as that is. A loop and a
     * ping-pong have no ends to ease: on the back their eases soften only stops and wakes.
     */
    fun passEase(preview: Boolean, mode: VideoPlayMode, easeIn: Boolean, easeOut: Boolean): Pair<Boolean, Boolean> =
        if (preview && (mode == VideoPlayMode.LINEAR || mode == VideoPlayMode.YOYO)) easeIn to easeOut else false to false
}
