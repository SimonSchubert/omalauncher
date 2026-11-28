package dev.omalauncher.widgets.render

import dev.omalauncher.HostNative
import dev.omalauncher.widgets.lang.Bsp
import dev.omalauncher.widgets.lang.BspParts
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Mesh
import dev.omalauncher.widgets.lang.MeshKernel
import dev.omalauncher.widgets.lang.MeshKernels
import dev.omalauncher.widgets.lang.Meshes
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.ObjParts
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.WidgetError
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.util.Random

/**
 * cpp/mesh3d.c against the Kotlin it's a port of: built here for the machine the test runs on, both build the same
 * meshes, and must give the same corner normals, trees and OBJ meshes, to the bit. Skipped where there's no C compiler.
 */
class MeshNativeTest {
    /** The C, counting what it did rather than left to the Kotlin. */
    private class Kernel : MeshKernel {
        var normals = 0
        var trees = 0
        var objs = 0
        var objsLeft = 0

        override fun cornerNormals(
            points: FloatArray,
            tris: IntArray,
            faceNormals: FloatArray,
            areas: FloatArray,
            out: FloatArray,
        ) = MeshNative.cornerNormals(points, tris, faceNormals, areas, out).also { if (it) normals++ }

        override fun bsp(
            points: FloatArray,
            tris: IntArray,
            uvs: FloatArray?,
            faceNormals: FloatArray,
            radius: Float,
            limit: Int,
        ): BspParts? = NativeMeshKernel.bspParts(MeshNative.bsp(points, tris, uvs, faceNormals, radius, limit)).also { if (it != null) trees++ }

        override fun scanObj(text: String): ObjParts? = NativeMeshKernel.objParts(MeshNative.scanObj(text)).also { if (it != null) objs++ else objsLeft++ }
    }

    private val kernel = Kernel()

    @After fun noKernel() {
        MeshKernels.kernel = null
    }

    /** [make]'s result built by the Kotlin, then by the C. */
    private fun <T> both(make: () -> T): Pair<T, T> {
        MeshKernels.kernel = null
        val kotlin = make()
        MeshKernels.kernel = kernel
        try {
            return kotlin to make()
        } finally {
            MeshKernels.kernel = null
        }
    }

    private fun assertBits(
        name: String,
        a: FloatArray?,
        b: FloatArray?,
    ) {
        if (a == null || b == null) {
            assertEquals("$name: null", a == null, b == null)
            return
        }
        assertEquals("$name: size", a.size, b.size)
        for (i in a.indices) if (a[i].toRawBits() != b[i].toRawBits()) fail("$name[$i] is ${a[i]} in Kotlin, ${b[i]} in C")
    }

    private fun assertSameMesh(
        name: String,
        a: Mesh,
        b: Mesh,
    ) {
        assertBits("$name points", a.points, b.points)
        assertArrayEquals("$name tris", a.tris, b.tris)
        assertBits("$name uvs", a.uvs, b.uvs)
        assertArrayEquals("$name colors", a.colorIndex, b.colorIndex)
        assertEquals("$name palette", a.palette, b.palette)
        assertEquals("$name smooth", a.smooth, b.smooth)
    }

    private fun obj(text: String) = Meshes.parseObj(text, Null)

    /** Boxes turned every way through each other, with texture places and colors: a mesh that needs a tree, and cuts. */
    private fun boxes(
        rnd: Random,
        count: Int,
        uv: Boolean,
    ): String {
        val o = StringBuilder()
        if (uv) o.append("vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n")
        for (b in 0 until count) {
            val c = FloatArray(3) { (rnd.nextFloat() - 0.5f) * 3 }
            val s = FloatArray(3) { 0.2f + rnd.nextFloat() }
            val a = rnd.nextFloat() * 6.3f
            for (i in 0..7) {
                val x = ((i and 1) * 2 - 1) * s[0]
                val y = (((i shr 1) and 1) * 2 - 1) * s[1]
                val z = (((i shr 2) and 1) * 2 - 1) * s[2]
                o.append("v ${c[0] + x * Math.cos(a.toDouble()) - z * Math.sin(a.toDouble())} ${c[1] + y} ${c[2] + x * Math.sin(a.toDouble()) + z * Math.cos(a.toDouble())}\n")
            }
            o.append("usemtl m${b % 3}\n")
            for (q in listOf(listOf(1, 3, 4, 2), listOf(5, 6, 8, 7), listOf(1, 2, 6, 5), listOf(3, 7, 8, 4), listOf(1, 5, 7, 3), listOf(2, 4, 8, 6))) {
                o.append("f")
                q.forEachIndexed { k, p -> o.append(" ${p + 8 * b}").append(if (uv) "/${k + 1}" else "") }
                o.append("\n")
            }
        }
        return o.toString()
    }

    private val materials = Obj(mapOf("m0" to Str("#ff0000"), "m1" to Str("accent"), "a" to Str("#00ff00")))

    @Test fun sameCornerNormals() {
        val rnd = Random(7)
        val welded = StringBuilder()
        repeat(40) { welded.append("v ${rnd.nextInt(4)} ${rnd.nextInt(4) * 0.5} ${rnd.nextInt(3) - 1}\n") }
        repeat(400) { welded.append("f ${1 + rnd.nextInt(40)} ${1 + rnd.nextInt(40)} ${1 + rnd.nextInt(40)}\n") }
        val heights = Lst(List(31) { i -> Lst(List(31) { j -> Num(Math.sin(i * 0.5) * Math.cos(j * 0.4) * 2) }) })
        val makes =
            listOf<() -> Mesh>(
                { Meshes.sphere(48) },
                { Meshes.cylinder(24, false) },
                { Meshes.cylinder(24, true) },
                { Meshes.cube() },
                { Meshes.terrain(heights, Null) },
                { Meshes.parseObj(boxes(Random(3), 40, true), materials) },
                // Points at 0 and -0 are apart, as Kotlin's Position compares them; and a face with no area.
                { obj("v 0 0 0\nv -0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\nv 1 1 0\nf 1 3 4\nf 2 4 5\nf 1 5 3\nf 2 3 6\nf 1 1 3\nf 3 4 6\n") },
                // Welded points shared by many faces at random.
                { obj(welded.toString()) },
            )
        for ((i, make) in makes.withIndex()) {
            val before = kernel.normals
            // Read where they're made: they're worked out on first use.
            val (a, b) = both { make().cornerNormals }
            assertBits("mesh $i", a, b)
            assertEquals("mesh $i by C", before + 1, kernel.normals)
        }
    }

    @Test fun sameTrees() {
        val rnd = Random(11)
        var trees = 0
        // A few boxes, so most heaps cut into few enough faces for a tree; the many others cut into too many.
        val texts = List(40) { boxes(rnd, if (it % 4 == 0) 10 + rnd.nextInt(20) else 2 + rnd.nextInt(6), rnd.nextBoolean()) }
        for ((i, text) in texts.withIndex()) {
            val (ma, mb) = both { Meshes.parseObj(text, materials) }
            val before = kernel.trees
            val (a, b) = both { Bsp.build(if (MeshKernels.kernel == null) ma else mb) }
            assertEquals("mesh $i by C", before + 1, kernel.trees)
            assertEquals("mesh $i has a tree", a != null, b != null)
            if (a == null || b == null) continue
            trees++
            assertBits("mesh $i planes", a.planes, b.planes)
            assertArrayEquals("mesh $i nodes", a.nodes, b.nodes)
            assertSameMesh("mesh $i cut", a.mesh, b.mesh)
            assertBits("mesh $i cut normals", a.mesh.faceNormals, b.mesh.faceNormals)
        }
        assertTrue("only $trees trees", trees > 20)
        // Convex: no tree from either.
        val (sa, sb) = both { Bsp.build(Meshes.sphere(16)) }
        assertNull(sa)
        assertNull(sb)
        // A heap of triangles that cuts into more faces than are allowed: no tree from either.
        val soup = StringBuilder()
        repeat(9000) { soup.append("v ${rnd.nextFloat() * 10} ${rnd.nextFloat() * 10} ${rnd.nextFloat() * 10}\n") }
        repeat(3000) { soup.append("f ${3 * it + 1} ${3 * it + 2} ${3 * it + 3}\n") }
        val (ta, tb) = both { Bsp.build(obj(soup.toString())) }
        assertNull(ta)
        assertNull(tb)
    }

    /** Numbers written the ways OBJ files write them. */
    private fun number(rnd: Random): String {
        val x = (rnd.nextDouble() - 0.5) * Math.pow(10.0, rnd.nextInt(7) - 3.0)
        return when (rnd.nextInt(8)) {
            0 -> rnd.nextInt(100).toString()
            1 -> "%.6f".format(java.util.Locale.ROOT, x)
            2 -> "%e".format(java.util.Locale.ROOT, x)
            3 -> ".${rnd.nextInt(1000)}"
            4 -> "${rnd.nextInt(10)}."
            5 -> "+${rnd.nextInt(10)}.5E-2"
            6 -> "-0"
            else -> x.toString()
        }
    }

    private fun randomObj(rnd: Random): String {
        val o = StringBuilder()
        val nl = listOf("\n", "\r\n", "\r")[rnd.nextInt(3)]
        var points = 0
        var vts = 0
        val textured = rnd.nextInt(3)
        repeat(20 + rnd.nextInt(200)) {
            val lead = if (rnd.nextInt(8) == 0) " \t" else ""
            when (rnd.nextInt(12)) {
                0 -> o.append("# a comment ü")
                1 -> o.append("")
                2 -> o.append("usemtl ${listOf("m0", "m1", "a", "none", "")[rnd.nextInt(5)]}")
                3 -> o.append("s ${listOf("1", "off", "0", "2", "")[rnd.nextInt(5)]}")
                4, 5 -> {
                    vts++
                    o.append("${lead}vt ${number(rnd)}\t${number(rnd)}")
                }
                6 -> o.append("vn 0 1 0")
                in 7..9 -> {
                    points++
                    o.append("${lead}v ${number(rnd)} ${number(rnd)}  ${number(rnd)}" + (if (rnd.nextInt(5) == 0) " 1.0" else ""))
                }
                else -> {
                    if (points == 0) return@repeat
                    o.append("f")
                    repeat(if (rnd.nextInt(10) == 0) 2 else 3 + rnd.nextInt(4)) {
                        val p = 1 + rnd.nextInt(points)
                        o.append(" ").append(if (rnd.nextInt(4) == 0) (p - points - 1).toString() else p.toString())
                        val t = if (vts > 0) 1 + rnd.nextInt(vts + 1) else 1
                        when (if (textured == 0) 0 else if (textured == 1) 1 + rnd.nextInt(2) else rnd.nextInt(4)) {
                            1 -> o.append("/$t")
                            2 -> o.append("/${t - vts - 1}/1")
                            3 -> o.append("//1")
                        }
                    }
                }
            }
            o.append(nl)
        }
        return o.toString()
    }

    /** The mesh or the error each gives. */
    private fun parse(text: String): Any =
        try {
            Meshes.parseObj(text, materials)
        } catch (e: WidgetError) {
            e.message ?: ""
        }

    private fun assertSameParse(
        name: String,
        a: Any,
        b: Any,
    ) {
        if (a is Mesh && b is Mesh) assertSameMesh(name, a, b) else assertEquals(name, a.toString(), b.toString())
    }

    @Test fun sameObjMeshes() {
        val rnd = Random(5)
        var meshes = 0
        repeat(400) { i ->
            val text = randomObj(rnd)
            val before = kernel.objs
            val (a, b) = both { parse(text) }
            assertSameParse("obj $i:\n$text", a, b)
            assertEquals("obj $i by C", before + 1, kernel.objs)
            if (a is Mesh) meshes++
        }
        assertTrue("only $meshes meshes", meshes > 300)
    }

    /** What the C leaves to the Kotlin comes out the same, error or not. */
    @Test fun objsLeftToKotlin() {
        val left =
            listOf(
                "v 1f 2 3\nv 0 1 0\nv 0 0 1\nf 1 2 3\n",
                "v NaN 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 3\n",
                "v abc 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 3\n",
                "v 0x1p3 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 3\n",
                "v 1 0 0 # ü\nv 0 1 0\nv 0 0 1\nf 1 2 3\n",
                "v\u00a01 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 3\n",
                "v 1 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 x\n",
                "v 1 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 99999999999\n",
            )
        for ((i, text) in left.withIndex()) {
            val before = kernel.objsLeft
            val (a, b) = both { parse(text) }
            assertSameParse("left $i", a, b)
            assertEquals("left $i to Kotlin", before + 1, kernel.objsLeft)
        }
        // And what it reads, errors from the mesh's own checks included.
        val read =
            listOf(
                "v 1e999 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 3\n",
                "v 1 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 9\n",
                "v 1 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 3/1\n",
                "v 1 0 0\nv 0 1 0\nv 0 0 1\nvt 0.5 0.5\nf 1/-1 2/1 3/2147483647\n",
                "v 1 0 0\nv 0 1 0\nv 0 0 1\nf 1 2\n",
                "",
            )
        for ((i, text) in read.withIndex()) {
            val before = kernel.objs
            val (a, b) = both { parse(text) }
            assertSameParse("read $i", a, b)
            assertEquals("read $i by C", before + 1, kernel.objs)
        }
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun build() {
            HostNative.load("scene3d.c", "mesh3d.c")
            assertNotNull(MeshNative)
        }
    }
}
