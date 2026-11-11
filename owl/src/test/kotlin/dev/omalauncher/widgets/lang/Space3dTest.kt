package dev.omalauncher.widgets.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class Space3dTest {
    private fun v(vararg xs: Double) = Lst(xs.map(::Num))

    private fun call(
        name: String,
        vararg args: Value,
    ): Value = Builtins.all.getValue(name).impl(Args(args.toList(), null))

    private fun camera(
        eye: Lst = v(0.0, 0.0, 5.0),
        look: Lst = v(0.0, 0.0, 0.0),
        far: Double = 1000.0,
    ) = Camera.of(Obj(mapOf("eye" to eye, "look" to look, "fov" to Num(60.0), "far" to Num(far))))

    /** A scene on a 400 x 400 canvas, lit from the camera's side, with [models] added in order. */
    private fun scene(
        cam: Camera = camera(),
        ambient: Float = 0f,
        fog: Int? = null,
        models: Scene3d.() -> Unit,
    ) = Scene3d().apply {
        begin(cam, 400f, 400f, floatArrayOf(0f, 0f, 1f), ambient, fog)
        models()
        finish()
    }

    private fun Scene3d.model(
        mesh: Mesh,
        at: FloatArray = floatArrayOf(0f, 0f, 0f),
        turn: FloatArray = floatArrayOf(0f, 0f, 0f),
        scale: Float = 1f,
        color: Int = 0xffffffff.toInt(),
        twoSided: Boolean = false,
        smooth: Boolean = mesh.smooth,
    ) = add(mesh, at, turn, floatArrayOf(scale, scale, scale), color, null, Float.NaN, smooth, twoSided, null, 0f, 0f)

    /** Each closed shape's faces point out of it: its normals lead away from the middle. */
    @Test fun primitivesFaceOutward() {
        for ((name, m) in listOf("cube" to Meshes.cube(), "sphere" to Meshes.sphere(16), "cylinder" to Meshes.cylinder(12, false), "cone" to Meshes.cylinder(12, true))) {
            assertTrue(name, m.triangles > 0)
            for (t in 0 until m.triangles) {
                var cx = 0f
                var cy = 0f
                var cz = 0f
                for (k in 0..2) {
                    val p = 3 * m.tris[3 * t + k]
                    cx += m.points[p]
                    cy += m.points[p + 1]
                    cz += m.points[p + 2]
                }
                val d = cx * m.faceNormals[3 * t] + cy * m.faceNormals[3 * t + 1] + cz * m.faceNormals[3 * t + 2]
                assertTrue("$name triangle $t faces in", d > 0f)
            }
        }
        assertEquals(12, Meshes.cube().triangles)
        // Its smallest box holds it: one unit across.
        assertEquals(sqrt(0.75f), Meshes.cube().radius, 1e-5f)
        assertEquals(0.5f, Meshes.sphere(24).radius, 1e-5f)
    }

    @Test fun groundFacesUp() {
        for (m in listOf(Meshes.plane(4), Meshes.terrain(Lst(List(5) { i -> v(*DoubleArray(6) { j -> (i * j % 3).toDouble() }) }), Null))) {
            for (t in 0 until m.triangles) assertTrue(m.faceNormals[3 * t + 1] > 0f)
        }
        assertEquals(32, Meshes.plane(4).triangles)
        assertEquals(2 * 4 * 5, Meshes.terrain(Lst(List(5) { v(*DoubleArray(6)) }), Null).triangles)
    }

    @Test fun terrainColorsItsCells() {
        val m = Meshes.terrain(Lst(List(3) { v(0.0, 0.0, 0.0) }), Lst(listOf(Lst(listOf(Str("#ff0000"), Str("#00ff00"))), Lst(listOf(Str("#ff0000"))))))
        assertEquals(2, m.palette.size)
        assertEquals(listOf(0, 0, 1, 1, 0, 0, -1, -1), m.colorIndex!!.toList())
    }

    @Test fun meshFromListsFansPolygons() {
        val quad = Meshes.mesh(Lst(listOf(v(0.0, 0.0, 0.0), v(1.0, 0.0, 0.0), v(1.0, 1.0, 0.0), v(0.0, 1.0, 0.0))), Lst(listOf(v(0.0, 1.0, 2.0, 3.0))), Null)
        assertEquals(listOf(0, 1, 2, 0, 2, 3), quad.tris.toList())
        assertEquals(1f, quad.faceNormals[2], 1e-6f)
        try {
            Meshes.mesh(Lst(listOf(v(0.0, 0.0, 0.0))), Lst(listOf(v(0.0, 1.0, 2.0))), Null)
            fail("a face past the points")
        } catch (e: WidgetError) {
            assertTrue(e.message!!, e.message!!.contains("names point 1"))
        }
    }

    @Test fun parseObjReadsPointsFacesTexturesAndMaterials() {
        val obj =
            """
            # a square and a triangle
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            usemtl hull
            f 1/1/1 2/2/1 3/3/1 4/4/1
            usemtl glass
            s 1
            f -4/1 -3/2 -2/3
            """.trimIndent()
        val m = Meshes.parseObj(obj, Obj(mapOf("hull" to Str("#ff0000"))))
        assertEquals(3, m.triangles)
        assertEquals(listOf(0, 1, 2, 0, 2, 3, 0, 1, 2), m.tris.toList())
        assertTrue(m.smooth)
        // v flipped: OBJ's 0 is the image's bottom.
        assertEquals(listOf(0f, 1f, 1f, 1f, 1f, 0f), m.uvs!!.copyOfRange(0, 6).toList())
        // glass has no color given: the model's.
        assertEquals(listOf(0, 0, -1), m.colorIndex!!.toList())
        assertEquals(0, Meshes.parseObj("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3", Null).uvs?.size ?: 0)
    }

    @Test fun meshesHaveALimit() {
        try {
            Meshes.terrain(Lst(List(300) { v(*DoubleArray(300)) }), Null)
            fail("too big")
        } catch (e: WidgetError) {
            assertTrue(e.message!!.contains("$MAX_MESH_TRIANGLES"))
        }
    }

    /** A cube in front of the camera: its three near faces are culled away to one, centered on the canvas. */
    @Test fun sceneDrawsTheFacesThatShow() {
        val s = scene { model(Meshes.cube()) }
        // Straight on, only the front face shows: two triangles.
        assertEquals(2, s.triangles)
        for (i in 0 until 6 step 2) {
            assertTrue(abs(s.verts[i] - 200f) < 40f)
            assertTrue(abs(s.verts[i + 1] - 200f) < 40f)
        }
        // Lit straight on, at full strength.
        assertEquals(0xffffffff.toInt(), s.colors[0])
        // Turned, three faces show; two-sided, all six.
        assertEquals(6, scene { model(Meshes.cube(), turn = floatArrayOf(30f, 40f, 0f)) }.triangles)
        assertEquals(12, scene { model(Meshes.cube(), twoSided = true) }.triangles)
    }

    @Test fun sceneSkipsWhatsOutOfView() {
        assertEquals(0, scene { model(Meshes.cube(), at = floatArrayOf(0f, 0f, 10f)) }.triangles)
        assertEquals(0, scene { model(Meshes.cube(), at = floatArrayOf(100f, 0f, 0f)) }.triangles)
        assertEquals(0, scene(camera(far = 20.0)) { model(Meshes.cube(), at = floatArrayOf(0f, 0f, -100f)) }.triangles)
    }

    @Test fun sceneSortsFarToNear() {
        val s =
            scene {
                model(Meshes.cube(), at = floatArrayOf(0f, 0f, 2f), color = 0xffff0000.toInt())
                model(Meshes.cube(), at = floatArrayOf(0f, 0f, -5f), color = 0xff00ff00.toInt())
            }
        assertEquals(4, s.triangles)
        // The far green cube first, the near red one over it.
        assertEquals(0xff00ff00.toInt(), s.colors[0])
        assertEquals(0xffff0000.toInt(), s.colors[3 * 3])
        assertEquals(1, s.batches)
    }

    /** A floor running under the camera is cut at the near plane, not projected through it. */
    @Test fun sceneCutsAtTheNearPlane() {
        val cam = camera(eye = v(0.0, 1.0, 0.0), look = v(0.0, 1.0, -1.0))
        val s = scene(cam) { model(Meshes.plane(1), scale = 20f) }
        assertTrue(s.triangles >= 2)
        for (i in 0 until s.triangles * 6) assertTrue("vertex $i at ${s.verts[i]}", s.verts[i].isFinite() && abs(s.verts[i]) < 1e5f)
        // The floor is below the eye: everything drawn is in the lower half.
        for (i in 0 until s.triangles * 3) assertTrue(s.verts[2 * i + 1] >= 199.9f)
    }

    @Test fun lightAndFogShadeFaces() {
        // Lit from the side: the front face gets only the ambient.
        val side =
            Scene3d().apply {
                begin(camera(), 400f, 400f, floatArrayOf(1f, 0f, 0f), 0.25f, null)
                model(Meshes.cube())
                finish()
            }
        assertEquals(0xff404040.toInt(), side.colors[0])
        // Fogged toward black from halfway to far: a face at 5 of 8 is a quarter of the way in.
        val fogged = scene(camera(far = 8.0), fog = 0xff000000.toInt()) { model(Meshes.cube(), at = floatArrayOf(0f, 0f, -0.5f)) }
        val c = fogged.colors[0] and 0xff
        assertTrue("$c", c in 180..200)
    }

    @Test fun smoothShadingVariesAcrossASphere() {
        val s = scene { model(Meshes.sphere(16)) }
        val shades = (0 until s.triangles * 3).map { s.colors[it] and 0xff }.toSet()
        assertTrue(shades.size > 10)
    }

    @Test fun tooManyTrianglesStops() {
        try {
            scene { repeat(10) { model(Meshes.sphere(128), twoSided = true) } }
            fail("over the limit")
        } catch (e: WidgetError) {
            assertTrue(e.message!!.contains("$MAX_SCENE_TRIANGLES"))
        }
    }

    @Test fun projectFindsWherePointsShow() {
        val cam = Obj(mapOf("eye" to v(0.0, 0.0, 5.0)))
        val mid = call("project", v(0.0, 0.0, 0.0), cam, Num(400.0), Num(300.0)) as Obj
        assertEquals(200.0, mid.fields["x"]!!.num()!!, 1e-4)
        assertEquals(150.0, mid.fields["y"]!!.num()!!, 1e-4)
        assertEquals(5.0, mid.fields["depth"]!!.num()!!, 1e-4)
        val right = call("project", v(1.0, 1.0, 0.0), cam, Num(400.0), Num(300.0)) as Obj
        assertTrue(right.fields["x"]!!.num()!! > 200 && right.fields["y"]!!.num()!! < 150)
        assertNull((call("project", v(0.0, 0.0, 10.0), cam, Num(400.0), Num(300.0)) as? Obj))
    }

    @Test fun vectorMath() {
        fun nums(x: Value) = (x as Lst).items.map { Math.round(it.num()!! * 1000) / 1000.0 }
        // A quarter turn around y takes +z to +x, as model's turn does.
        assertEquals(listOf(1.0, 0.0, 0.0), nums(call("turn", v(0.0, 0.0, 1.0), v(0.0, 90.0, 0.0))))
        assertEquals(listOf(0.0, 0.0, 1.0), nums(call("cross", v(1.0, 0.0, 0.0), v(0.0, 1.0, 0.0))))
        assertEquals(32.0, call("dot", v(1.0, 2.0, 3.0), v(4.0, 5.0, 6.0)).num()!!, 0.0)
        assertEquals(listOf(0.6, 0.8), nums(call("norm", v(3.0, 4.0))))
        assertEquals(5.0, call("dist", v(1.0, 1.0), v(4.0, 5.0)).num()!!, 1e-9)
        assertEquals(5.0, call("dist", v(3.0, 4.0, 0.0)).num()!!, 1e-9)
        assertTrue(call("cube") is Mesh)
    }

    private fun compileError(src: String): String =
        try {
            Compiler.compile(src)
            fail("expected a compile error")
            ""
        } catch (e: CompileError) {
            e.message!!
        }

    private val game =
        """
        app "t" {
          meta { owl: 22 }
          state a = 0
          let ship = cube()
          Canvas {
            scene({eye: [0, 2, 6], look: [0, 0, 0], far: 50}, light: [1, 1, 1], fog: background) {
              model(ship, at: [0, 0, 0], turn: [0, a, 0], color: accent)
              for i in range(3) { model(sphere(8), at: [i, 0, 0], scale: 0.5, smooth: false) }
            }
            text("hi", 10, 10)
          }
        }
        """.trimIndent()

    @Test fun scenesCompileAtOwl22() {
        Compiler.compile(game)
        assertTrue(compileError(game.replace("owl: 22", "owl: 21")).contains("needs owl 22"))
    }

    @Test fun scenesHoldOnlyModels() {
        assertTrue(compileError(game.replace("text(\"hi\", 10, 10)", "model(ship)")).contains("goes inside scene"))
        assertTrue(compileError(game.replace("for i in", "circle(1, 2, 3)\n for i in")).contains("a scene holds model"))
        assertTrue(compileError(game.replace("color: accent", "radius: 4")).contains("model takes"))
        assertTrue(compileError(game.replace("fog: background", "at: [1, 2, 3]")).contains("scene takes"))
        assertTrue(compileError(game.replace("text(\"hi\", 10, 10)", "text(\"hi\", 10, 10, turn: 4)")).contains("an option of scene"))
    }

    /** onTouch hands over every finger down, for a game's two thumbs (owl 22). */
    @Test fun onTouchGivesTheFingers() {
        val src = "app \"t\" {\n meta { owl: 22 }\n state fingers = []\n Canvas { fill: true; onTouch: fingers = touches }\n Text { text: len(fingers) } }"
        Compiler.compile(src)
        assertTrue(compileError(src.replace("owl: 22", "owl: 21")).contains("needs owl 22"))
    }

    @Test fun shaderImagesAndArraysNeedOwl22() {
        val src = "widget {\n meta { owl: 21 }\n Shader { code: \"\"\"\n uniform shader tex;\n half4 main(float2 p) { return tex.eval(p / size); }\n\"\"\" }\n}"
        assertTrue(compileError(src).contains("needs owl 22"))
        Compiler.compile(src.replace("owl: 21", "owl: 22"))
        val arrays = "widget {\n meta { owl: 21 }\n Shader { code: \"\"\"\n uniform float3 dots[4];\n half4 main(float2 p) { return half4(dots[0], 1); }\n\"\"\" }\n}"
        assertTrue(compileError(arrays).contains("needs owl 22"))
        assertEquals(null, ShaderCost.problem("uniform shader tex;\nhalf4 main(float2 p) { return tex.eval(p / size); }"))
    }
}
