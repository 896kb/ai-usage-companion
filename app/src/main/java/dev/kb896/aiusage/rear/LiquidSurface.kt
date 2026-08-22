package dev.kb896.aiusage.rear

/**
 * The charging cell's liquid, as a surface rather than as drops.
 *
 * Drops were the second attempt and metaballs the third. Both were the wrong shape of
 * answer: blobs merging and pulling apart is what you want for something being poured
 * THROUGH the air, and this is a vessel with liquid sitting in it. However well a heap of
 * circles is packed and however smoothly its field is thresholded, the eye keeps finding
 * the lumps, because a still liquid in a glass does not have any.
 *
 * What it has is a surface: one line, smooth, that ripples when disturbed and settles
 * flat when left alone. That is a height field — a row of columns, each with a height and
 * a velocity, pulled toward rest by a spring and passing their movement to their
 * neighbours. It is the standard way to do water in a container and it is convincing for
 * the same reason the drops were not: it models the thing you can actually see.
 *
 * THE COLUMNS RUN ALONG GRAVITY'S OWN HORIZONTAL, not the cell's. That is what keeps the
 * full turn the drops gave: the surface is by construction perpendicular to gravity, so
 * rotating the phone rotates the whole sheet of liquid, and the waves run along whichever
 * direction is level at the time.
 */
class LiquidSurface(private val columns: Int = COLUMNS) {

    /** Displacement of each column from the flat surface, and how fast it is moving. */
    private val height = FloatArray(columns)
    private val speed = FloatArray(columns)
    private val spread = FloatArray(columns)

    private var idle = 0f

    /** Which way was down last time, so a turn can be felt as a disturbance. */
    private var lastDownX = 0f
    private var lastDownY = 1f

    fun heightAt(i: Int): Float = height[i.coerceIn(0, columns - 1)]

    val size: Int get() = columns

    fun calm() {
        height.fill(0f)
        speed.fill(0f)
        idle = 0f
    }

    /**
     * Advance the surface, given which way is down now.
     *
     * [downX] and [downY] are a unit vector. The disturbance comes from how far that has
     * TURNED since the last step: a phone held still leaves the surface alone, and a
     * phone being tilted pushes the liquid toward the new downhill, which is what sloshing
     * is. Nothing here needs to know the angle — only that it changed.
     */
    fun step(dt: Float, downX: Float, downY: Float) {
        val t = dt.coerceIn(0f, MAX_STEP)
        if (t <= 0f) return

        // How much the world turned under the liquid, as a signed amount along the
        // surface. A cross product: positive when gravity swung one way, negative the
        // other, zero when it did not move.
        val turn = lastDownX * downY - lastDownY * downX
        lastDownX = downX
        lastDownY = downY
        if (Math.abs(turn) > TURN_DEADBAND) {
            // Tipped INTO the low side and away from the high one, which is the shape a
            // real surface takes the instant a glass is tilted — it does not stay flat
            // and then move, it leans first and levels afterwards.
            for (i in 0 until columns) {
                val across = i / (columns - 1f) - 0.5f
                speed[i] += turn * across * TURN_FORCE
            }
        }

        // A LIVING SURFACE. Left exactly alone, a height field settles to a dead flat
        // line, and a dead flat line is a coloured rectangle — the one thing this whole
        // approach was meant to avoid. Real liquid in a held glass is never quite still,
        // so a slow travelling ripple is fed in continuously. Small enough that it reads
        // as the liquid being alive rather than as something happening to it.
        idle += t
        for (i in 0 until columns) {
            val phase = idle * IDLE_SPEED + i * IDLE_WAVELENGTH
            speed[i] += Math.sin(phase.toDouble()).toFloat() * IDLE_FORCE * t
        }

        for (i in 0 until columns) {
            // A spring back to flat. This is what makes it settle rather than wander.
            speed[i] += -SPRING * height[i] * t
            speed[i] *= DAMP
            height[i] += speed[i] * t
        }

        // Pass movement to the neighbours, which is what turns a poke into a wave that
        // travels rather than a single column bobbing on its own.
        repeat(PASSES) {
            for (i in 0 until columns) {
                if (i > 0) {
                    spread[i - 1] = SPREAD * (height[i] - height[i - 1])
                    speed[i - 1] += spread[i - 1]
                }
                if (i < columns - 1) {
                    spread[i] = SPREAD * (height[i] - height[i + 1])
                    speed[i + 1] += spread[i]
                }
            }
            for (i in 0 until columns) {
                if (i > 0) height[i - 1] += spread[i - 1] * MAX_STEP
                if (i < columns - 1) height[i + 1] += spread[i] * MAX_STEP
            }
        }
    }

    /** Push the surface at a point, 0..1 across it. For the liquid arriving. */
    fun splash(at: Float, force: Float) {
        val i = (at.coerceIn(0f, 1f) * (columns - 1)).toInt()
        speed[i] += force
        if (i > 0) speed[i - 1] += force * 0.5f
        if (i < columns - 1) speed[i + 1] += force * 0.5f
    }

    companion object {
        /**
         * How many columns the surface is made of.
         *
         * Enough that the line reads as a curve rather than a chain of segments, few
         * enough that the whole step is trivial. The cell is under a hundred units wide,
         * so forty puts a column every two and a half units.
         */
        const val COLUMNS = 40

        /** How hard the surface is pulled back to flat. Higher is stiffer, and choppier. */
        const val SPRING = 26f

        /** Kept per step. Below one, or a disturbance never dies. */
        const val DAMP = 0.972f

        /** How much of a column's movement passes to its neighbours each pass. */
        const val SPREAD = 0.22f

        /** Passes per step. Two makes a wave travel visibly without going unstable. */
        const val PASSES = 2

        /** How strongly a turn of the phone leans the surface. */
        const val TURN_FORCE = 900f

        /** Below this much turn, the phone counts as still. Keeps sensor noise out. */
        const val TURN_DEADBAND = 0.002f

        /** The idle ripple: how fast it travels, how long it is, how hard it pushes. */
        const val IDLE_SPEED = 3.1f
        const val IDLE_WAVELENGTH = 0.55f
        const val IDLE_FORCE = 34f

        const val MAX_STEP = 1f / 30f
    }
}
