package dev.kb896.aiusage.rear

/**
 * The Claude mascot's walk cycle, as flat rectangle data.
 *
 * GENERATED from docs/mascot/walk-8frame.frames.json — do not hand-edit; regenerate
 * from the source SVG instead. See docs/mascot/README.md for where it came from and why
 * this is rectangles rather than a bitmap or a video.
 *
 * Coordinates are normalised 0..1 against the union bounding box of ALL frames, not
 * per-frame. That matters: the frames are different heights (the raised-arm poses reach
 * above the source viewBox), and normalising each one independently would rescale the
 * mascot every frame, so it would pulse as it walked. One shared box keeps it the same
 * size and planted on the same ground line throughout.
 */
object MascotFrames {

    /** width / height of the shared bounding box, for callers sizing a slot. */
    const val ASPECT = 1.06612f

    /** x, y, w, h in 0..1, plus whether this rect is an eye (drawn as a hole). */
    class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val eye: Boolean)

    val FRAMES: List<List<Rect>> = listOf(
        // l001
        listOf(
            Rect(0.74419f, 0.90083f, 0.08527f, 0.09917f, false),
            Rect(0.58140f, 0.90083f, 0.08527f, 0.09917f, false),
            Rect(0.33333f, 0.90083f, 0.08527f, 0.09917f, false),
            Rect(0.17054f, 0.90083f, 0.08527f, 0.09917f, false),
            Rect(0.17054f, 0.36364f, 0.65891f, 0.53719f, false),
            Rect(0.82946f, 0.66116f, 0.17054f, 0.19008f, false),
            Rect(0.00000f, 0.66116f, 0.17054f, 0.19008f, false),
            Rect(0.66667f, 0.57025f, 0.08527f, 0.09091f, true),
            Rect(0.24806f, 0.57025f, 0.08527f, 0.09091f, true),
        ),
        // l002
        listOf(
            Rect(0.75969f, 0.75207f, 0.08527f, 0.24793f, false),
            Rect(0.59690f, 0.75207f, 0.08527f, 0.24793f, false),
            Rect(0.34884f, 0.79339f, 0.08527f, 0.20661f, false),
            Rect(0.18605f, 0.79339f, 0.08527f, 0.20661f, false),
            Rect(0.18605f, 0.25620f, 0.32558f, 0.53719f, false),
            Rect(0.51163f, 0.16529f, 0.33333f, 0.58678f, false),
            Rect(0.34884f, 0.20661f, 0.24806f, 0.17355f, false),
            Rect(0.81395f, 0.09091f, 0.17054f, 0.25620f, false),
            Rect(0.01550f, 0.43802f, 0.17054f, 0.19008f, false),
            Rect(0.68217f, 0.25620f, 0.08527f, 0.09091f, true),
            Rect(0.26357f, 0.34711f, 0.08527f, 0.09091f, true),
        ),
        // l003
        listOf(
            Rect(0.75969f, 0.68595f, 0.08527f, 0.22314f, false),
            Rect(0.59690f, 0.68595f, 0.08527f, 0.22314f, false),
            Rect(0.34884f, 0.72727f, 0.08527f, 0.20661f, false),
            Rect(0.18605f, 0.72727f, 0.08527f, 0.20661f, false),
            Rect(0.18605f, 0.19008f, 0.32558f, 0.53719f, false),
            Rect(0.51163f, 0.09917f, 0.33333f, 0.58678f, false),
            Rect(0.34884f, 0.14050f, 0.24806f, 0.17355f, false),
            Rect(0.81395f, 0.06612f, 0.06202f, 0.21488f, false),
            Rect(0.85271f, 0.00000f, 0.06202f, 0.24793f, false),
            Rect(0.88372f, 0.00000f, 0.06202f, 0.22314f, false),
            Rect(0.92248f, 0.00000f, 0.06202f, 0.19835f, false),
            Rect(0.01550f, 0.48760f, 0.17054f, 0.19008f, false),
            Rect(0.68217f, 0.19835f, 0.08527f, 0.09091f, true),
            Rect(0.26357f, 0.28099f, 0.08527f, 0.09091f, true),
        ),
        // l004
        listOf(
            Rect(0.75969f, 0.75207f, 0.08527f, 0.21488f, false),
            Rect(0.59690f, 0.75207f, 0.08527f, 0.21488f, false),
            Rect(0.34884f, 0.79339f, 0.08527f, 0.20661f, false),
            Rect(0.18605f, 0.79339f, 0.08527f, 0.20661f, false),
            Rect(0.18605f, 0.25620f, 0.32558f, 0.53719f, false),
            Rect(0.51163f, 0.16529f, 0.33333f, 0.58678f, false),
            Rect(0.34884f, 0.20661f, 0.24806f, 0.17355f, false),
            Rect(0.81395f, 0.09091f, 0.17054f, 0.25620f, false),
            Rect(0.01550f, 0.43802f, 0.17054f, 0.19008f, false),
            Rect(0.68217f, 0.25620f, 0.08527f, 0.09091f, true),
            Rect(0.26357f, 0.34711f, 0.08527f, 0.09091f, true),
        ),
        // l005
        listOf(
            Rect(0.74419f, 0.90083f, 0.08527f, 0.09917f, false),
            Rect(0.58140f, 0.90083f, 0.08527f, 0.09917f, false),
            Rect(0.33333f, 0.90083f, 0.08527f, 0.09917f, false),
            Rect(0.17054f, 0.90083f, 0.08527f, 0.09917f, false),
            Rect(0.17054f, 0.36364f, 0.65891f, 0.53719f, false),
            Rect(0.82946f, 0.66116f, 0.17054f, 0.19008f, false),
            Rect(0.00000f, 0.66116f, 0.17054f, 0.19008f, false),
            Rect(0.66667f, 0.57025f, 0.08527f, 0.09091f, true),
            Rect(0.24806f, 0.57025f, 0.08527f, 0.09091f, true),
        ),
        // l006
        listOf(
            Rect(0.15504f, 0.75207f, 0.08527f, 0.24793f, false),
            Rect(0.31783f, 0.75207f, 0.08527f, 0.24793f, false),
            Rect(0.56589f, 0.79339f, 0.08527f, 0.20661f, false),
            Rect(0.72868f, 0.79339f, 0.08527f, 0.20661f, false),
            Rect(0.48837f, 0.25620f, 0.32558f, 0.53719f, false),
            Rect(0.15504f, 0.16529f, 0.33333f, 0.58678f, false),
            Rect(0.40310f, 0.20661f, 0.24806f, 0.17355f, false),
            Rect(0.01550f, 0.09091f, 0.17054f, 0.25620f, false),
            Rect(0.81395f, 0.43802f, 0.17054f, 0.19008f, false),
            Rect(0.23256f, 0.25620f, 0.08527f, 0.09091f, true),
            Rect(0.65116f, 0.34711f, 0.08527f, 0.09091f, true),
        ),
        // l007
        listOf(
            Rect(0.15504f, 0.68595f, 0.08527f, 0.22314f, false),
            Rect(0.31783f, 0.68595f, 0.08527f, 0.22314f, false),
            Rect(0.56589f, 0.72727f, 0.08527f, 0.20661f, false),
            Rect(0.72868f, 0.72727f, 0.08527f, 0.20661f, false),
            Rect(0.48837f, 0.19008f, 0.32558f, 0.53719f, false),
            Rect(0.15504f, 0.09917f, 0.33333f, 0.58678f, false),
            Rect(0.40310f, 0.14050f, 0.24806f, 0.17355f, false),
            Rect(0.12403f, 0.06612f, 0.06202f, 0.21488f, false),
            Rect(0.08527f, 0.00000f, 0.06202f, 0.24793f, false),
            Rect(0.05426f, 0.00000f, 0.06202f, 0.22314f, false),
            Rect(0.01550f, 0.00000f, 0.06202f, 0.19835f, false),
            Rect(0.81395f, 0.48760f, 0.17054f, 0.19008f, false),
            Rect(0.23256f, 0.19835f, 0.08527f, 0.09091f, true),
            Rect(0.65116f, 0.28099f, 0.08527f, 0.09091f, true),
        ),
        // l008
        listOf(
            Rect(0.15504f, 0.75207f, 0.08527f, 0.21488f, false),
            Rect(0.31783f, 0.75207f, 0.08527f, 0.21488f, false),
            Rect(0.56589f, 0.79339f, 0.08527f, 0.20661f, false),
            Rect(0.72868f, 0.79339f, 0.08527f, 0.20661f, false),
            Rect(0.48837f, 0.25620f, 0.32558f, 0.53719f, false),
            Rect(0.15504f, 0.16529f, 0.33333f, 0.58678f, false),
            Rect(0.40310f, 0.20661f, 0.24806f, 0.17355f, false),
            Rect(0.01550f, 0.09091f, 0.17054f, 0.25620f, false),
            Rect(0.81395f, 0.43802f, 0.17054f, 0.19008f, false),
            Rect(0.23256f, 0.25620f, 0.08527f, 0.09091f, true),
            Rect(0.65116f, 0.34711f, 0.08527f, 0.09091f, true),
        ),
    )
}
