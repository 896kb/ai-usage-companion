package dev.kb896.aiusage.rear

enum class VideoAction { RELEASE, PAUSE, LOOP, HOLD_FIRST, PLAY_ONCE, KEEP }

/**
 * What a video background's player should be doing, from the panel's state alone — kept
 * apart from the player so every case can be pinned by a test. KEEP is an unlocked,
 * non-looping video between unlocks: playing its one run or holding its last frame,
 * which only the player knows and nothing here should disturb.
 */
object VideoState {
    fun decide(
        isVideo: Boolean, hasClip: Boolean, attached: Boolean, displayAwake: Boolean,
        aod: Boolean, locked: Boolean, loop: Boolean, preview: Boolean, justUnlocked: Boolean,
    ): VideoAction = when {
        !isVideo || !hasClip || !attached || !displayAwake -> VideoAction.RELEASE
        aod -> VideoAction.PAUSE
        loop || preview -> VideoAction.LOOP
        locked -> VideoAction.HOLD_FIRST
        justUnlocked -> VideoAction.PLAY_ONCE
        else -> VideoAction.KEEP
    }
}
