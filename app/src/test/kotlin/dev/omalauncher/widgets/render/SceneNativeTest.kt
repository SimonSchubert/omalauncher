package dev.omalauncher.widgets.render

import dev.omalauncher.widgets.lang.Camera
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.MAX_SCENE_TRIANGLES
import dev.omalauncher.widgets.lang.Mesh
import dev.omalauncher.widgets.lang.Meshes
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Scene3d
import dev.omalauncher.widgets.lang.SceneKernel
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.WidgetError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * cpp/scene3d.c against the Kotlin it's a port of: built here for the machine the test runs on, both are given the
 * same scenes and must stage the same triangles, to the bit. Skipped where there's no C compiler.
 */
class SceneNativeTest {
    private class Kernel : SceneKernel {
        val scene = SceneNative.create()

        override fun reset() = SceneNative.reset(scene)

        override fun add(
            v: FloatArray,
            color: Int,
            flags: Int,
            texIndex: Int,
            triangles: Int,
            points: FloatArray,
            tris: IntArray,
            faceNormals: FloatArray,
            cornerNormals: FloatArray?,
            uvs: FloatArray?,
            colorIndex: IntArray?,
            palette: IntArray?,
            order: IntArray?,
        ) = SceneNative.add(scene, v, color, flags, texIndex, triangles, points, tris, faceNormals, cornerNormals, uvs, colorIndex, palette, order)

        override fun finish(
            verts: FloatArray,
            texs: FloatArray,
            colors: IntArray,
            batches: IntArray,
        ) = SceneNative.finish(scene, verts, texs, colors, batches)
    }

    private class Model(
        val mesh: Mesh,
        val at: FloatArray,
        val turn: FloatArray,
        val scale: FloatArray,
        val color: Int,
        val palette: IntArray?,
        val ambient: Float,
        val smooth: Boolean,
        val twoSided: Boolean,
        val texture: Any?,
    )

    private fun v(vararg d: Double) = Lst(d.map(::Num))

    /** Two boxes through each other: not convex, so the scene orders its faces with a tree. */
    private fun cross(): Mesh {
        val obj = StringBuilder()
        for (b in 0..1) {
            for (i in 0..7) {
                val x = (i and 1) * (if (b == 0) 2.0 else 0.6) - (if (b == 0) 1.0 else 0.3)
                val y = ((i shr 1) and 1) * (if (b == 0) 0.6 else 2.0) - (if (b == 0) 0.3 else 1.0)
                obj.append("v $x $y ${((i shr 2) and 1) - 0.5}\n")
            }
        }
        for (b in 0..1) {
            for (q in listOf(listOf(1, 2, 4, 3), listOf(5, 7, 8, 6), listOf(1, 5, 6, 2), listOf(3, 4, 8, 7), listOf(1, 3, 7, 5), listOf(2, 6, 8, 4))) {
                obj.append("f ${q.joinToString(" ") { (it + 8 * b).toString() }}\n")
            }
        }
        return Meshes.parseObj(obj.toString(), Null)
    }

    private val meshes by lazy {
        val heights = Lst(List(31) { i -> Lst(List(31) { j -> Num(Math.sin(i * 0.5) * Math.cos(j * 0.4) * 2) }) })
        val colors = Lst(List(30) { i -> Lst(List(30) { j -> Str(if ((i + j) % 2 == 0) "#336633" else "#553311") }) })
        listOf(Meshes.cube(), Meshes.plane(4), Meshes.sphere(24), Meshes.cylinder(12, false), Meshes.cylinder(12, true), Meshes.terrain(heights, colors), cross())
    }

    private fun draw(
        s: Scene3d,
        cam: Camera,
        fog: Int?,
        models: List<Model>,
    ) {
        s.begin(cam, 411f, 890f, floatArrayOf(0.4f, 1f, 0.6f), 0.35f, fog)
        for (m in models) s.add(m.mesh, m.at, m.turn, m.scale, m.color, m.palette, m.ambient, m.smooth, m.twoSided, m.texture, 64f, 48f)
        s.finish()
    }

    private fun assertSame(
        name: String,
        a: Scene3d,
        b: Scene3d,
    ) {
        assertEquals("$name: triangles", a.triangles, b.triangles)
        assertEquals("$name: batches", a.batches, b.batches)
        for (i in 0 until a.batches) {
            assertEquals("$name: batch $i starts", a.batchStart[i], b.batchStart[i])
            assertEquals("$name: batch $i count", a.batchCount[i], b.batchCount[i])
            assertEquals("$name: batch $i texture", a.batchTexture[i], b.batchTexture[i])
        }
        for (i in 0 until 6 * a.triangles) {
            if (a.verts[i].toRawBits() != b.verts[i].toRawBits()) fail("$name: vertex ${i / 2} is ${a.verts[i]} in Kotlin, ${b.verts[i]} in C")
            // An untextured face's are a leftover times zero in Kotlin, which can be -0.
            if (a.texs[i] != b.texs[i]) fail("$name: texture place ${i / 2} is ${a.texs[i]} in Kotlin, ${b.texs[i]} in C")
        }
        for (i in 0 until 3 * a.triangles) if (a.colors[i] != b.colors[i]) fail("$name: color $i is ${a.colors[i].toUInt().toString(16)} in Kotlin, ${b.colors[i].toUInt().toString(16)} in C")
    }

    @Test fun sameTrianglesAsKotlin() {
        val rnd = Random(22)
        val textures = listOf(Any(), Any())
        val kotlin = Scene3d()
        val c = Scene3d(Kernel())
        var drawn = 0
        var cut = false
        for (round in 0 until 300) {
            fun spread(by: Float) = (rnd.nextFloat() - 0.5f) * by
            // Every few rounds from in among the models, where faces cross the near plane.
            val eye = if (round % 4 == 0) v(spread(6f).toDouble(), 1.0, -20.0 + spread(6f)) else v(spread(10f).toDouble(), 2.0 + rnd.nextFloat() * 6, 10.0)
            val cam = Camera.of(Obj(mapOf("eye" to eye, "look" to v(spread(8f).toDouble(), 0.0, -22.0), "fov" to Num(40.0 + rnd.nextInt(50)), "far" to Num(if (round % 3 == 0) 60.0 else 400.0))))
            val models =
                List(1 + rnd.nextInt(60)) {
                    val mesh = meshes[rnd.nextInt(meshes.size)]
                    val s = 0.3f + rnd.nextFloat() * 3
                    Model(
                        mesh,
                        floatArrayOf(spread(40f), spread(4f), -20f + spread(40f)),
                        floatArrayOf(spread(360f), spread(360f), spread(360f)),
                        when (rnd.nextInt(5)) {
                            0 -> floatArrayOf(s, s * 3, s)
                            1 -> floatArrayOf(-s, s, s)
                            else -> floatArrayOf(s, s, s)
                        },
                        rnd.nextInt(),
                        if (mesh.palette.isEmpty() || rnd.nextInt(4) == 0) null else IntArray(mesh.palette.size) { rnd.nextInt() },
                        if (rnd.nextBoolean()) Float.NaN else rnd.nextFloat(),
                        rnd.nextBoolean(),
                        rnd.nextInt(3) == 0,
                        if (rnd.nextBoolean()) textures[rnd.nextInt(2)] else null,
                    )
                }
            val fog = if (rnd.nextBoolean()) rnd.nextInt() else null
            draw(kotlin, cam, fog, models)
            draw(c, cam, fog, models)
            assertSame("round $round", kotlin, c)
            drawn += kotlin.triangles
            cut = cut || round % 4 == 0 && kotlin.triangles > 0
        }
        // The scenes showed something, so the comparison meant something.
        assertTrue("only $drawn triangles drawn", drawn > 100_000)
        assertTrue(cut)
    }

    /** Depths that are the same keep the order their models came in, however many there are (the sort changes at a size). */
    @Test fun sameOrderForEqualDepths() {
        val cam = Camera.of(Obj(mapOf("eye" to v(0.0, 0.0, 5.0), "look" to v(0.0, 0.0, 0.0))))
        val plane = Meshes.plane(1)
        for (count in listOf(3, 40, 500)) {
            val models = List(count) { Model(plane, floatArrayOf(0f, 0f, 0f), floatArrayOf(90f, 0f, 0f), floatArrayOf(1f, 1f, 1f), 0xff000000.toInt() or it, null, 1f, false, true, null) }
            val kotlin = Scene3d()
            val c = Scene3d(Kernel())
            draw(kotlin, cam, null, models)
            draw(c, cam, null, models)
            assertEquals(2 * count, kotlin.triangles)
            assertSame("$count planes", kotlin, c)
        }
    }

    @Test fun tooManyTrianglesIsAnError() {
        val cam = Camera.of(Obj(mapOf("eye" to v(0.0, 30.0, 0.1), "look" to v(0.0, 0.0, 0.0))))
        val plane = Meshes.plane(128)
        val one = Model(plane, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 10f, 10f), -1, null, 1f, false, true, null)
        assertTrue(2 * plane.triangles > MAX_SCENE_TRIANGLES)
        for (scene in listOf(Scene3d(), Scene3d(Kernel()))) {
            try {
                draw(scene, cam, null, listOf(one, one))
                fail("drew ${scene.triangles} triangles")
            } catch (e: WidgetError) {
                assertTrue(e.message!!.contains("$MAX_SCENE_TRIANGLES"))
            }
            // And the next frame is drawn again.
            draw(scene, cam, null, listOf(one))
            assertEquals(plane.triangles, scene.triangles)
        }
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun build() {
            val source = File("src/main/cpp/scene3d.c")
            assertTrue("run from the app module: $source", source.isFile)
            val include = File(System.getProperty("java.home"), "include")
            val platform = include.listFiles { f -> f.isDirectory && File(f, "jni_md.h").isFile }?.firstOrNull()
            assumeTrue("no JDK headers in $include", File(include, "jni.h").isFile && platform != null)
            val out = File.createTempFile("scene3d", if (System.getProperty("os.name").startsWith("Mac")) ".dylib" else ".so").apply { deleteOnExit() }
            val log =
                try {
                    val p =
                        ProcessBuilder("cc", "-O2", "-Wall", "-ffp-contract=off", "-shared", "-fPIC", "-I$include", "-I$platform", "-o", out.path, source.path, "-lm")
                            .redirectErrorStream(true)
                            .start()
                    val text = p.inputStream.bufferedReader().readText()
                    if (p.waitFor() != 0) fail("scene3d.c doesn't build:\n$text")
                    text
                } catch (e: java.io.IOException) {
                    null
                }
            assumeTrue("no C compiler", log != null)
            System.load(out.path)
            assertNotNull(SceneNative)
        }
    }
}
