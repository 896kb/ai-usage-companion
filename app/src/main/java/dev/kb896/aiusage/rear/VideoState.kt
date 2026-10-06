package dev.kb896.aiusage.rear

enum class VideoAction { DROP, SLEEP, FREEZE, LOOP, RESTART_LOOP, PLAY_ONCE, KEEP }

/**
 * What a video background's player should be doing, from the panel's state alone — kept
 * apart from the player so every case can be pinned by a test.
 *
 * DROP lets go of everything (no video any more); SLEEP only the decoder, keeping the last
 * frame to show when the display lights again — a dark display, or a panel nobody can see
 * (the front's preview with the app in the background). FREEZE holds the frame where it is: always-on,
 * the double tap's dim, a non-looping video behind the slider. [fresh] is the panel coming
 * back — woken, unlocked, the dim called off, always-on left, a new clip — and starts the
 * video over, cross-fading from the frame it had stopped on. KEEP is an open, non-looping
 * video between those moments: playing its one run or holding its last frame.
 */
object VideoState {
    fun decide(
        isVideo: Boolean, hasClip: Boolean, attached: Boolean, displayAwake: Boolean, visible: Boolean,
        aod: Boolean, paused: Boolean, locked: Boolean, loop: Boolean, preview: Boolean, fresh: Boolean,
    ): VideoAction = when {
        !isVideo || !hasClip -> VideoAction.DROP
        !attached || !displayAwake || !visible -> VideoAction.SLEEP
        preview -> VideoAction.LOOP
        aod || paused -> VideoAction.FREEZE
        loop -> if (fresh) VideoAction.RESTART_LOOP else VideoAction.LOOP
        locked -> VideoAction.FREEZE
        fresh -> VideoAction.PLAY_ONCE
        else -> VideoAction.KEEP
    }

    /**
     * A player that was only just [created] has drawn nothing, so leaving it be would leave
     * the panel on its bare ground: it shows its frame instead.
     */
    fun afterOpen(action: VideoAction, created: Boolean): VideoAction =
        if (created && action == VideoAction.KEEP) VideoAction.FREEZE else action

    /**
     * The ease a loop's passes run with. The front's preview loops a video that plays once
     * on the back, so each pass is eased as that one run is — the preview is there to show
     * how it will look. A video set to loop has no ends to ease.
     */
    fun loopEase(preview: Boolean, loop: Boolean, easeIn: Boolean, easeOut: Boolean): Pair<Boolean, Boolean> =
        if (preview && !loop) easeIn to easeOut else false to false
}
