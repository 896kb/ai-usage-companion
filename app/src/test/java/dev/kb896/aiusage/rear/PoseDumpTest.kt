package dev.kb896.aiusage.rear

import org.junit.Test

/**
 * Not an assertion — a viewer. Writes the hand-authored poses out as plain numbers so
 * they can be rendered and LOOKED at, which is the only way to check artwork.
 * Skipped unless a destination is given, so it costs nothing in a normal run.
 */
class PoseDumpTest {
    @Test fun dump() {
        val out = System.getenv("POSE_DUMP") ?: return
        val sb = StringBuilder()
        // EVERY clip, not just the authored ones. The stills generator used to parse
        // MascotFrames.kt with a regex, which worked only while every clip was a literal
        // table — the hand-authored poses are computed, and it silently could not see
        // them. Dumping through the same list the panel draws from is the only source
        // that cannot drift.
        for (clip in MascotFrames.CLIPS) {
            sb.append("clip\t${clip.name}\t${clip.aspect}\t${clip.scale}\t${clip.frames.size}\n")
            for ((i, frame) in clip.frames.withIndex())
                for (r in frame)
                    sb.append("r\t${clip.name}\t$i\t${r.x}\t${r.y}\t${r.w}\t${r.h}\t${r.color}\n")
        }
        java.io.File(out).writeText(sb.toString())
    }
}
