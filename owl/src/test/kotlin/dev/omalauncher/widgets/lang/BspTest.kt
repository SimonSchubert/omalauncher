package dev.omalauncher.widgets.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class BspTest {
    private fun v(vararg xs: Double) = Lst(xs.map(::Num))

    /** Boxes, each [x, y, z, w, h, d] standing on y, as one mesh of closed boxes, faces counterclockwise from outside. */
    private fun boxes(vararg bs: DoubleArray): Mesh {
        val corners = listOf(listOf(-1, 0, -1), listOf(1, 0, -1), listOf(1, 0, 1), listOf(-1, 0, 1), listOf(-1, 1, -1), listOf(1, 1, -1), listOf(1, 1, 1), listOf(-1, 1, 1))
        val sides = listOf(listOf(4, 7, 6, 5), listOf(3, 2, 6, 7), listOf(1, 0, 4, 5), listOf(2, 1, 5, 6), listOf(0, 3, 7, 4), listOf(0, 1, 2, 3))
        val points = ArrayList<Value>()
        val faces = ArrayList<Value>()
        bs.forEachIndexed { i, b ->
            for (c in corners) points += v(b[0] + c[0] * b[3] / 2, b[1] + c[1] * b[4], b[2] + c[2] * b[5] / 2)
            for (s in sides) faces += Lst(s.map { Num((it + 8 * i).toDouble()) })
        }
        return Meshes.mesh(Lst(points), Lst(faces), Null)
    }

    /** voxel rally 1.0's first car: a body with the wheels poking into it, whose wheels popped in and out as it turned. */
    private val car =
        boxes(
            doubleArrayOf(0.0, 0.3, 0.0, 2.0, 0.6, 4.0),
            doubleArrayOf(0.0, 0.9, -0.35, 1.6, 0.6, 1.9),
            doubleArrayOf(0.98, 0.0, 1.25, 0.45, 0.72, 0.8),
            doubleArrayOf(-0.98, 0.0, 1.25, 0.45, 0.72, 0.8),
            doubleArrayOf(0.98, 0.0, -1.25, 0.45, 0.72, 0.8),
            doubleArrayOf(-0.98, 0.0, -1.25, 0.45, 0.72, 0.8),
            doubleArrayOf(0.0, 0.9, -1.8, 0.15, 0.4, 0.2),
            doubleArrayOf(0.0, 1.3, -1.8, 2.1, 0.16, 0.45),
        )

    private fun camera(eye: FloatArray) = Camera.of(Obj(mapOf("eye" to v(eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble()), "look" to v(0.0, 0.4, 0.0), "fov" to Num(60.0))))

    /** Where the ray from [o] along [d] meets triangle [t] (9 floats), or null. */
    private fun hit(
        o: FloatArray,
        d: FloatArray,
        t: FloatArray,
    ): Float? {
        val e1 = floatArrayOf(t[3] - t[0], t[4] - t[1], t[5] - t[2])
        val e2 = floatArrayOf(t[6] - t[0], t[7] - t[1], t[8] - t[2])
        val p = floatArrayOf(d[1] * e2[2] - d[2] * e2[1], d[2] * e2[0] - d[0] * e2[2], d[0] * e2[1] - d[1] * e2[0])
        val det = e1[0] * p[0] + e1[1] * p[1] + e1[2] * p[2]
        if (abs(det) < 1e-9f) return null
        val s = floatArrayOf(o[0] - t[0], o[1] - t[1], o[2] - t[2])
        val u = (s[0] * p[0] + s[1] * p[1] + s[2] * p[2]) / det
        if (u < 0f || u > 1f) return null
        val q = floatArrayOf(s[1] * e1[2] - s[2] * e1[1], s[2] * e1[0] - s[0] * e1[2], s[0] * e1[1] - s[1] * e1[0])
        val w = (d[0] * q[0] + d[1] * q[1] + d[2] * q[2]) / det
        if (w < 0f || u + w > 1f) return null
        val dist = (e2[0] * q[0] + e2[1] * q[1] + e2[2] * q[2]) / det
        return if (dist > 0f) dist else null
    }

    /**
     * Rays through a grid over the view: how many show a different face, painted in [order], than a depth buffer
     * would (the nearest face the ray meets), by more than a hair.
     */
    private fun wrongPixels(
        cam: Camera,
        order: List<FloatArray>,
    ): Int {
        val o = floatArrayOf(cam.ex, cam.ey, cam.ez)
        var wrong = 0
        for (i in 0 until 64) {
            for (j in 0 until 64) {
                val a = (i / 63f - 0.5f) * 1.1f
                val b = (j / 63f - 0.5f) * 1.1f
                val d = FloatArray(3) { cam.f[it] + cam.r[it] * a + cam.u[it] * b }
                var painted = Float.NaN
                var nearest = Float.MAX_VALUE
                for (t in order) {
                    val h = hit(o, d, t) ?: continue
                    painted = h
                    nearest = minOf(nearest, h)
                }
                if (!painted.isNaN() && painted > nearest + 1e-3f) wrong++
            }
        }
        return wrong
    }

    /** [mesh]'s faces in the world at [at], turned by [turn] and stretched by [scale]. */
    private fun world(
        mesh: Mesh,
        at: FloatArray,
        turn: FloatArray,
        scale: FloatArray,
    ): List<FloatArray> {
        val r = Meshes.rotation(turn[0], turn[1], turn[2], FloatArray(9))
        return (0 until mesh.triangles).map { t ->
            FloatArray(9).also { out ->
                for (k in 0..2) {
                    val p = 3 * mesh.tris[3 * t + k]
                    val x = mesh.points[p] * scale[0]
                    val y = mesh.points[p + 1] * scale[1]
                    val z = mesh.points[p + 2] * scale[2]
                    for (c in 0..2) out[3 * k + c] = r[3 * c] * x + r[3 * c + 1] * y + r[3 * c + 2] * z + at[c]
                }
            }
        }
    }

    private fun eyes() =
        (0 until 16).map { i ->
            val a = i * Math.PI / 8 + 0.3
            floatArrayOf((7 * cos(a)).toFloat(), if (i % 2 == 0) 2.5f else 5f, (7 * sin(a)).toFloat())
        }

    @Test fun convexMeshesNeedNoTree() {
        assertNull(Meshes.cube().bsp)
        assertNull(Meshes.sphere(12).bsp)
        assertNull(Meshes.cylinder(12, false).bsp)
        assertNull(Meshes.plane(4).bsp)
        assertNotNull(car.bsp)
    }

    /** Sorting faces by the depth of their middles, as scenes did, paints the car wrong from some sides. */
    @Test fun depthSortAloneGetsOverlappingBoxesWrong() {
        val faces = world(car, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        val bad =
            eyes().sumOf { eye ->
                val cam = camera(eye)
                val front = faces.filter { t -> facesEye(t, eye) }
                wrongPixels(cam, front.sortedByDescending { t -> (0..2).sumOf { k -> ((t[3 * k] - cam.ex) * cam.f[0] + (t[3 * k + 1] - cam.ey) * cam.f[1] + (t[3 * k + 2] - cam.ez) * cam.f[2]).toDouble() } })
            }
        assertTrue("the old sort should show wrong faces somewhere, got $bad", bad > 0)
    }

    /** The tree's order paints what a depth buffer would, from every side. */
    @Test fun treeOrderPaintsOverlappingBoxesRight() {
        val bsp = car.bsp!!
        val faces = world(bsp.mesh, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        val order = IntArray(bsp.mesh.triangles)
        for (eye in eyes()) {
            bsp.order(eye[0], eye[1], eye[2], order)
            assertEquals(order.toSet().size, order.size)
            val drawn = order.map { faces[it] }.filter { facesEye(it, eye) }
            assertEquals("from ${eye.toList()}", 0, wrongPixels(camera(eye), drawn))
        }
    }

    /** End to end: a scene draws a moved, turned and stretched car in an order a depth buffer agrees with. */
    @Test fun sceneDrawsTheTreeOrderOfAMovedModel() {
        val at = floatArrayOf(1f, 0.2f, -0.5f)
        val turn = floatArrayOf(0f, 37f, 0f)
        val scale = floatArrayOf(1.3f, 0.8f, 1.1f)
        val faces = world(car.bsp!!.mesh, at, turn, scale)
        for (eye in eyes()) {
            val cam = camera(eye)
            val scene =
                Scene3d().apply {
                    begin(cam, 400f, 400f, floatArrayOf(0f, 1f, 0f), 0.5f, null)
                    add(car, at, turn, scale, 0xffffffff.toInt(), null, Float.NaN, false, false, null, 0f, 0f)
                    finish()
                }
            // Each drawn triangle, matched to the world face that projects onto it.
            val screen =
                faces.map { t ->
                    (0..2).map { k -> cam.project(t[3 * k], t[3 * k + 1], t[3 * k + 2], 400f, 400f)!! }
                }
            val drawn =
                (0 until scene.triangles).map { k ->
                    val x = (scene.verts[6 * k] + scene.verts[6 * k + 2] + scene.verts[6 * k + 4]) / 3
                    val y = (scene.verts[6 * k + 1] + scene.verts[6 * k + 3] + scene.verts[6 * k + 5]) / 3
                    faces[screen.indices.minByOrNull { i -> abs(screen[i].sumOf { it.first.toDouble() } / 3 - x) + abs(screen[i].sumOf { it.second.toDouble() } / 3 - y) }!!]
                }
            assertEquals("from ${eye.toList()}", 0, wrongPixels(cam, drawn))
        }
    }

    private fun facesEye(
        t: FloatArray,
        eye: FloatArray,
    ): Boolean {
        val ux = t[3] - t[0]
        val uy = t[4] - t[1]
        val uz = t[5] - t[2]
        val vx = t[6] - t[0]
        val vy = t[7] - t[1]
        val vz = t[8] - t[2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        return nx * (eye[0] - t[0]) + ny * (eye[1] - t[1]) + nz * (eye[2] - t[2]) > 0f
    }
}
