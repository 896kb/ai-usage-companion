package dev.kb896.aiusage.rear

import android.graphics.Paint
import android.graphics.RuntimeShader

/**
 * TABLE MODE'S GLOW. The phone set flat on its face, rear display up, is a phone left to
 * be looked at from across a desk; the margins breathe with a soft light in the clock's
 * ink, a brightening that travels once round the edge every few seconds. An AGSL shader,
 * like the glass: the light is a function of the distance to the nearest edge, so it
 * costs one full-panel pass and nothing else.
 */
object TableGlow {
    private const val SOURCE = """
        uniform float2 res;
        uniform float t;
        uniform float edge;
        uniform float radius;
        uniform float strength;
        layout(color) uniform half4 tint;
        half4 main(float2 p) {
            // the frame is the display's own rounded rectangle: straight runs between
            // corner arcs of the display's radius
            float r = radius;
            float2 half = res * 0.5;
            float2 a = p - half;
            float2 h = half - r;
            float2 q = abs(a) - h;
            float top = res.x - 2.0 * r;
            float side = res.y - 2.0 * r;
            float arc = 1.5707963 * r;
            float d;
            float s;
            if (q.x > 0.0 && q.y > 0.0) {
                // in a corner: the nearest edge is the arc round that corner's centre
                float2 v = a - sign(a) * h;
                d = r - length(v);
                float ang = atan(v.y, v.x);
                if (a.x > 0.0 && a.y < 0.0) s = top + (ang + 1.5707963) / 1.5707963 * arc;
                else if (a.x > 0.0) s = top + arc + side + ang / 1.5707963 * arc;
                else if (a.y > 0.0) s = 2.0 * top + 2.0 * arc + side + (ang - 1.5707963) / 1.5707963 * arc;
                else s = 2.0 * top + 3.0 * arc + 2.0 * side + (ang + 3.1415927) / 1.5707963 * arc;
            } else {
                float dt = p.y;
                float dr = res.x - p.x;
                float db = res.y - p.y;
                float dl = p.x;
                d = min(min(dt, dr), min(db, dl));
                if (d == dt) s = p.x - r;
                else if (d == dr) s = top + arc + (p.y - r);
                else if (d == db) s = 2.0 * top + 2.0 * arc + side + (res.x - r - p.x) - top;
                else s = 2.0 * top + 3.0 * arc + side + (res.y - r - p.y);
            }
            // the rim: full at the edge, gone `edge` pixels in
            float rim = 1.0 - clamp(d / edge, 0.0, 1.0);
            rim *= rim;
            // the light: a head once round the frame every LAP seconds, trailing a tail
            float L = 2.0 * top + 2.0 * side + 4.0 * arc;
            float head = fract(t / LAP) * L;
            float behind = mod(head - s + L, L);
            float tail = exp(-behind / (TAIL * L));
            float a1 = rim * (BASE + (1.0 - BASE) * tail) * strength;
            return half4(tint.rgb * a1, a1);
        }
    """

    /** the glow's reach inward from each edge, in panel pixels */
    const val EDGE = 30f
    /** seconds per lap, the tail as a share of the frame, the rim's resting share */
    private const val LAP = 4.0f
    private const val TAIL = 0.12f
    private const val BASE = 0.10f

    val shader: RuntimeShader by lazy {
        RuntimeShader(
            SOURCE.replace("LAP", LAP.toString()).replace("TAIL", TAIL.toString()).replace("BASE", BASE.toString()),
        )
    }
    val paint: Paint by lazy { Paint().apply { shader = TableGlow.shader } }

    /** The paint set for one frame: [strength] 0..1, [seconds] the breathing clock. */
    fun frame(width: Float, height: Float, seconds: Float, color: Int, strength: Float, radius: Float): Paint {
        shader.setFloatUniform("res", width, height)
        shader.setFloatUniform("radius", radius.coerceIn(1f, minOf(width, height) / 2f))
        shader.setFloatUniform("t", seconds)
        shader.setFloatUniform("edge", EDGE)
        shader.setFloatUniform("strength", strength)
        shader.setColorUniform("tint", color)
        return paint
    }
}
