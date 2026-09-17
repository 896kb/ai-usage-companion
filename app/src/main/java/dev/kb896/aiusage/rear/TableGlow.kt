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
        uniform float strength;
        layout(color) uniform half4 tint;
        half4 main(float2 p) {
            float dt = p.y;
            float dr = res.x - p.x;
            float db = res.y - p.y;
            float dl = p.x;
            float d = min(min(dt, dr), min(db, dl));
            // the rim: full at the edge, gone `edge` pixels in
            float rim = 1.0 - clamp(d / edge, 0.0, 1.0);
            rim *= rim;
            // where along the frame this point's nearest edge is, clockwise from the top-left
            float L = 2.0 * (res.x + res.y);
            float s;
            if (d == dt) s = p.x;
            else if (d == dr) s = res.x + p.y;
            else if (d == db) s = res.x + res.y + (res.x - p.x);
            else s = 2.0 * res.x + res.y + (res.y - p.y);
            // the light: a head once round the frame every LAP seconds, trailing a tail
            float head = fract(t / LAP) * L;
            float behind = mod(head - s + L, L);
            float tail = exp(-behind / (TAIL * L));
            float a = rim * (BASE + (1.0 - BASE) * tail) * strength;
            return half4(tint.rgb * a, a);
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
    fun frame(width: Float, height: Float, seconds: Float, color: Int, strength: Float): Paint {
        shader.setFloatUniform("res", width, height)
        shader.setFloatUniform("t", seconds)
        shader.setFloatUniform("edge", EDGE)
        shader.setFloatUniform("strength", strength)
        shader.setColorUniform("tint", color)
        return paint
    }
}
