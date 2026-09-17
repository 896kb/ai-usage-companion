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
            float dx = min(p.x, res.x - p.x);
            float dy = min(p.y, res.y - p.y);
            float d = min(dx, dy);
            float g = 1.0 - smoothstep(0.0, edge, d);
            float per = (p.x + p.y) / (res.x + res.y);
            float wave = 0.65 + 0.35 * sin(6.2831 * (per - t * 0.12));
            float breath = 0.78 + 0.22 * sin(t * 1.4);
            float a = g * g * wave * breath * strength;
            return half4(tint.rgb, a);
        }
    """

    /** the glow's reach inward from each edge, in panel pixels */
    const val EDGE = 30f

    val shader: RuntimeShader by lazy { RuntimeShader(SOURCE) }
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
