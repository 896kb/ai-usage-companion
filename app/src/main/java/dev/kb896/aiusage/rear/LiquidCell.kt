package dev.kb896.aiusage.rear

/**
 * The charging cell's liquid, as droplets rather than as a line.
 *
 * The first version drew the surface as a straight edge tilted by the gravity reading.
 * That is fine while the phone is roughly upright and a lie the moment it is not: a
 * surface defined as "a line across the glass" cannot pour into a corner, cannot sit on
 * a wall when the phone is on its side, and has to be clamped to stop it becoming a
 * diagonal slash. What it models is a picture of a liquid, not a liquid.
 *
 * These are drops with velocities, falling in whatever direction gravity actually points
 * and piling up against whichever wall is downhill. Turn the phone through a full circle
 * and they go round with it, because nothing here knows which wall is the floor —
 * there is only a direction, and four walls that stop things.
 *
 * Deliberately NOT a fluid solver. There is no pressure term and no surface tension; what
 * makes a heap of circles read as liquid is that they are packed, they settle, and they
 * lose energy. Anything more would be a physics engine running for five seconds on a
 * panel nobody is holding still.
 */
class LiquidCell(private val capacity: Int = MAX_DROPS) {

    /** One drop. Position and velocity in the cell's own units. */
    class Drop(var x: Float, var y: Float, var vx: Float, var vy: Float) {
        /** Where it was before this step — see [step] for why velocity is read back. */
        var px = x
        var py = y
    }

    private val drops = ArrayList<Drop>(capacity)

    /** How many drops are in play. Grows as the cell fills. */
    val size: Int get() = drops.size

    fun forEach(action: (Drop) -> Unit) = drops.forEach(action)

    fun clear() = drops.clear()

    /**
     * Bring the population to [want], spawning at the top of the cell.
     *
     * New drops arrive with a little sideways spread rather than in a column: a stack of
     * drops falling down one line lands as a tower, and a tower of circles reads as a
     * bar chart rather than as something poured in.
     */
    fun fillTo(want: Int, w: Float, h: Float, seed: Int) {
        var n = seed
        fun rnd(): Float {
            // A tiny deterministic generator rather than Math.random, so a given frame
            // sequence is reproducible and a test can say what it expects.
            n = n * 1_103_515_245 + 12_345
            return ((n ushr 16) and 0x7FFF) / 32_767f
        }
        while (drops.size < want.coerceAtMost(capacity)) {
            // IT WELLS UP FROM THE FLOOR rather than raining in from above.
            //
            // Drops used to spawn over the rim and fall. That is a picture of a glass
            // being poured into, and this is a battery filling: the level should rise,
            // not be dropped in. Spawning just under the floor and letting the walls put
            // each one back inside means the pile is pushed upward by what arrives
            // beneath it, which is what filling looks like from the outside.
            //
            // It also removes the jam that pouring had — nothing has to fall past
            // anything, so nothing lands on a crowd that has not cleared yet.
            drops.add(Drop(w * (0.1f + 0.8f * rnd()),
                           h + RADIUS * (0.5f + 2f * rnd()),
                           (rnd() - 0.5f) * 8f,
                           0f))
        }
        while (drops.size > want) drops.removeAt(drops.size - 1)
    }

    /**
     * Advance by [dt] seconds under a gravity of ([gx], [gy]) inside a [w] by [h] box.
     *
     * The step is capped rather than trusted. A frame that arrives late — and on an
     * always-on panel that has just been woken, one will — would otherwise move every
     * drop further than the box is wide, and they would pass through the walls in a
     * single tick and be clamped into a corner all at once.
     */
    fun step(dt: Float, gx: Float, gy: Float, w: Float, h: Float) {
        val t = dt.coerceIn(0f, MAX_STEP)
        if (t <= 0f) return
        for (d in drops) {
            d.px = d.x
            d.py = d.y
            d.vx += gx * t
            d.vy += gy * t
            d.vx *= DRAG
            d.vy *= DRAG
            d.x += d.vx * t
            d.y += d.vy * t
        }
        // SEPARATION IS A POSITION FIX AND NOTHING ELSE. It does not become velocity.
        //
        // Reading velocity back from where a drop ended up — the textbook
        // position-based formulation — inflates a packed cell: every drop is being shoved
        // by four neighbours, that shove is read as speed, and the speed pushes it
        // further next frame. Measured offline, sixty-six drops in a glass 540 tall
        // spread across the whole of it and floated with a mean height of 228 while
        // gravity pointed correctly downward. They never reached the floor at all.
        //
        // With the correction kept out of the velocity, the same sixty-six settle into a
        // heap resting ON the floor, spanning 358 to 529 — which is what a sixth of a
        // litre in a tall glass should look like.
        repeat(PASSES) {
            separate()
            for (d in drops) bounds(d, w, h)
        }
        // CONTACT DAMPING: a drop that could not move loses its speed.
        //
        // This is what the position read-back was doing for free, and losing it is the
        // price of not having separation inflate the pile. Without it a buried drop goes
        // on gaining velocity from gravity for as long as the cell exists — measured at
        // over a thousand units per second after fifteen seconds — and the instant the
        // heap shifts, that stored energy fires it across the glass.
        //
        // Blocked is measured rather than guessed: compare how far the drop actually
        // travelled this step against how far its velocity said it would. A drop in free
        // fall matches; one wedged in a heap does not move at all.
        for (d in drops) {
            val wantX = d.vx * t
            val wantY = d.vy * t
            val gotX = d.x - d.px
            val gotY = d.y - d.py
            if (Math.abs(wantX) > 0.01f && gotX / wantX < BLOCKED) d.vx *= BLOCKED_DAMP
            if (Math.abs(wantY) > 0.01f && gotY / wantY < BLOCKED) d.vy *= BLOCKED_DAMP
        }
    }

    /**
     * Push overlapping drops apart.
     *
     * This is the whole of what makes a heap look like liquid rather than a pile of
     * coins: without it every drop falls to the same lowest point and the cell shows one
     * dense blob at the bottom. With it they spread across the floor and stack.
     *
     * Half the correction goes to each drop, which conserves the pair's centre and stops
     * a crowd from drifting sideways under its own separation.
     */
    private fun separate() {
        val n = drops.size
        // BUCKETED, because the drops got small and therefore numerous. Comparing every
        // pair is O(n²): fine at a hundred drops, and at two hundred and forty it is
        // fifty-seven thousand checks four times a frame, which is not. Two drops can
        // only touch if they are within a diameter, so only the nine buckets around each
        // one need looking at.
        grid.clear()
        for (i in 0 until n) {
            val d = drops[i]
            grid.getOrPut(key(d.x, d.y)) { ArrayList(4) }.add(i)
        }
        for (i in 0 until n) {
            val a = drops[i]
            val cx = Math.floor((a.x / CELL).toDouble()).toInt()
            val cy = Math.floor((a.y / CELL).toDouble()).toInt()
            for (ox in -1..1) for (oy in -1..1) {
                val bucket = grid[(cx + ox) * 73_856_093 xor (cy + oy) * 19_349_663]
                    ?: continue
                for (j in bucket) {
                    if (j <= i) continue
                    val b = drops[j]
                    var dx = b.x - a.x
                    var dy = b.y - a.y
                    val d2 = dx * dx + dy * dy
                    val min = RADIUS * 2f
                    if (d2 >= min * min || d2 <= 0.0001f) continue
                    val d = Math.sqrt(d2.toDouble()).toFloat()
                    val push = (min - d) * 0.5f
                    dx /= d
                    dy /= d
                    a.x -= dx * push
                    a.y -= dy * push
                    b.x += dx * push
                    b.y += dy * push
                }
            }
        }
    }

    private val grid = HashMap<Int, ArrayList<Int>>()

    private fun key(x: Float, y: Float): Int {
        val cx = Math.floor((x / CELL).toDouble()).toInt()
        val cy = Math.floor((y / CELL).toDouble()).toInt()
        return cx * 73_856_093 xor cy * 19_349_663
    }

    /**
     * Keep a drop inside the box, losing energy on the way.
     *
     * Restitution well under a half, because drops that bounce look like hail. What is
     * wanted is the moment of arrival — a short jostle as they land — and then stillness.
     */
    private fun bounds(d: Drop, w: Float, h: Float) {
        if (d.x < RADIUS) { d.x = RADIUS; d.vx = -d.vx * BOUNCE }
        if (d.x > w - RADIUS) { d.x = w - RADIUS; d.vx = -d.vx * BOUNCE }
        if (d.y > h - RADIUS) { d.y = h - RADIUS; d.vy = -d.vy * BOUNCE; d.vx *= FLOOR_DRAG }
        if (d.y < RADIUS && d.y > -RADIUS) { d.y = RADIUS; d.vy = -d.vy * BOUNCE }
        // The TOP is open while filling, so a drop spawned above the cell can fall in.
        // Once it is inside it is kept inside: gravity can point upward — the phone is
        // upside down — and nothing should climb out of the glass.
        val ceiling = -RADIUS * HEADROOM
        if (d.y < ceiling) { d.y = ceiling; d.vy = 0f }
    }

    companion object {
        /**
         * How many drops a full cell holds.
         *
         * Separation is O(n²) and runs every frame, so this is a real budget rather than
         * a taste: 110 drops is about six thousand pair checks a frame, which is nothing,
         * and four hundred would be eighty thousand, which is not.
         */
        const val MAX_DROPS = 240

        /**
         * How big each drop is, in cell units.
         *
         * Sized so that a FULL cell actually looks full. Round drops pack at roughly
         * eighty per cent, so a hundred and ten of them have to cover about four fifths
         * of a glass 97 units wide and 540 tall — which puts the radius near eleven. At
         * nine they covered a third of it and a battery at 100% read as half empty.
         */
        const val RADIUS = 5.5f

        /** Bucket side for the separation grid: one diameter, so neighbours are adjacent. */
        const val CELL = RADIUS * 2f

        /** The downward push a new drop gets, in cell units per second. */
        const val SPAWN_FALL = 90f

        /**
         * How far above the cell a drop may be, in radii.
         *
         * Both the spawn height and the ceiling, so they cannot drift apart: drops are
         * made inside this band and nothing is ever allowed above it. It is generous
         * because they need room to clear the spawn point before the next arrives — see
         * fillTo — and it is a ceiling at all because gravity can point upward when the
         * phone is upside down, and nothing should climb out of the glass.
         */
        const val HEADROOM = 12f

        /** Velocity kept per step. Just under one, so a slosh dies down. */
        const val DRAG = 0.995f


        /** Kept on a wall bounce, and the sideways damping once on the floor. */
        const val BOUNCE = 0.28f
        const val FLOOR_DRAG = 0.86f

        /** Below this fraction of the movement it asked for, a drop counts as blocked. */
        const val BLOCKED = 0.5f

        /** What a blocked drop keeps. Low: a heap should go quiet, not wobble. */
        const val BLOCKED_DAMP = 0.25f

        /** The largest step that will be integrated, in seconds. */
        const val MAX_STEP = 1f / 30f

        /**
         * How many times the pairs are pushed apart per step.
         *
         * Four is where a full cell stops floating. It is a settling rate rather than a
         * correctness knob — more passes means a stiffer pile that compacts faster — and
         * it multiplies the O(n²) work, which is the reason it is not simply twenty.
         */
        const val PASSES = 4
    }
}
