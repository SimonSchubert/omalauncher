package dev.omalauncher.widgets.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderCostTest {
    @Test fun loopsInsideLoopsMultiply() {
        assertEquals(0L, ShaderCost.turns("half4 main(float2 p) { return half4(1.0); }"))
        assertEquals(6L, ShaderCost.turns("half4 main(float2 p) { float a = 0.0; for (int i = 0; i < 6; i++) { a += 1.0; } return half4(a); }"))
        // 8 x 16, then 4 more beside them.
        val nested =
            """
            half4 main(float2 p) {
              float a = 0.0;
              for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 16; j++) a += 1.0;
              }
              for (int k = 0; k < 4; k++) { a += 1.0; }
              return half4(a);
            }
            """.trimIndent()
        assertEquals(132L, ShaderCost.turns(nested))
    }

    @Test fun constsStepsAndCallsCount() {
        val code =
            """
            const int STEPS = 2 * 5;
            // for (int i = 0; i < 100000; i++) in a comment counts nothing
            float wave(float x) {
              float a = 0.0;
              for (float t = 0.0; t <= 1.0; t += 0.25) a += sin(x + t);
              return a;
            }
            half4 main(float2 p) {
              float a = 0.0;
              for (int i = STEPS; i > 0; i -= 2) { a += wave(p.x); }
              return half4(a);
            }
            """.trimIndent()
        // wave takes 5 turns; main's loop takes 5, each calling it.
        assertEquals(25L, ShaderCost.turns(code))
        assertNull(ShaderCost.problem(code))
    }

    @Test fun aHangingShaderIsRefused() {
        // What froze the emulator: every loop is under AGSL's own bound, together it's 7e14 turns a pixel.
        val code =
            """
            half4 main(float2 p) {
              float a = 0.0;
              for (int i = 0; i < 90000; i++) {
                for (int j = 0; j < 90000; j++) { for (int k = 0; k < 90000; k++) { a += sin(p.x); } }
              }
              return half4(a);
            }
            """.trimIndent()
        assertTrue(ShaderCost.problem(code)!!.contains("turns for every pixel"))
        assertTrue(ShaderCost.problem(code.replace("90000", "11"))!!.contains("1,331 turns"))
        assertNull(ShaderCost.problem(code.replace("90000", "10")))
    }

    @Test fun loopsItCantCountAreRefused() {
        val code = "uniform float n; half4 main(float2 p) { for (int i = 0; i < int(n); i++) {} return half4(1.0); }"
        assertTrue(ShaderCost.problem(code)!!.contains("can't be counted"))
        // Nothing to count when there's no main().
        assertEquals(0L, ShaderCost.turns("float f() { return 1.0; }"))
    }
}
