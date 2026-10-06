package dev.kb896.aiusage.rear

enum class VideoAction { DROP, SLEEP, FREEZE, LOOP, RESTART_LOOP, PLAY_ONCE, KEEP }

/**
 * What a video background's player should be doing, from the panel's state alone — kept
 * apart from the player so every case can be pinned by a test.
 *
 * DROP lets go of everything (no video any more); SLEEP only the decoder, keeping the last
 * frame to show when the display lights again. FREEZE holds the frame where it is: always-on,
 * the double tap's dim, a non-looping video behind the slider. [fresh] is the panel coming
 * back — woken, unlocked, the dim called off, always-on left, a new clip — and starts the
 * video over, cross-fading from the frame it had stopped on. KEEP is an open, non-looping
 * video between those moments: playing its one run or holding its last frame.
 */
object VideoState {
    fun decide(
        isVideo: Boolean, hasClip: Boolean, attached: Boolean, displayAwake: Boolean,
        aod: Boolean, paused: Boolean, locked: Boolean, loop: Boolean, preview: Boolean, fresh: Boolean,
    ): VideoAction = when {
        !isVideo || !hasClip -> VideoAction.DROP
        !attached || !displayAwake -> VideoAction.SLEEP
        preview -> VideoAction.LOOP
        aod || paused -> VideoAction.FREEZE
        loop -> if (fresh) VideoAction.RESTART_LOOP else VideoAction.LOOP
        locked -> VideoAction.FREEZE
        fresh -> VideoAction.PLAY_ONCE
        else -> VideoAction.KEEP
    }
}
