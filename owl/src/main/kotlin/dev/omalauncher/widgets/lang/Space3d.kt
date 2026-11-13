package dev.omalauncher.widgets.lang

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** The most triangles one mesh may have. */
const val MAX_MESH_TRIANGLES = 65_536

/** The most triangles one scene draws in a frame, after culling. */
const val MAX_SCENE_TRIANGLES = 60_000

/**
 * Triangle [t]'s normal, counterclockwise seen from outside, into [out]: the cross product of its edges, not made unit.
 * Returns its length, which is twice the triangle's area.
 */
private fun faceCross(
    points: FloatArray,
    tris: IntArray,
    t: Int,
    out: FloatArray,
): Float {
    val a = 3 * tris[3 * t]
    val b = 3 * tris[3 * t + 1]
    val c = 3 * tris[3 * t + 2]
    val ux = points[b] - points[a]
    val uy = points[b + 1] - points[a + 1]
    val uz = points[b + 2] - points[a + 2]
    val vx = points[c] - points[a]
    val vy = points[c + 1] - points[a + 1]
    val vz = points[c + 2] - points[a + 2]
    out[0] = uy * vz - uz * vy
    out[1] = uz * vx - ux * vz
    out[2] = ux * vy - uy * vx
    return sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
}

/**
 * A 3D model for a Canvas's `scene(camera) { model(mesh, ...) }` (owl 22): points, triangles and what colors
 * them, built once by cube(), sphere(), parseObj() and the like and drawn many times. Opaque to widgets, and not
 * a list, so it isn't held to MAX_LIST's 1000 items. Compared by identity: the same let gives the same mesh until
 * what it reads changes.
 *
 * Space is right-handed, as in OBJ files and OpenGL: x right, y up, z toward the viewer of the default camera.
 */
class Mesh internal constructor(
    /** x, y, z of each point. */
    val points: FloatArray,
    /** Three point indices per triangle, counterclockwise seen from outside. */
    val tris: IntArray,
    /** u, v of each triangle's corners, six per triangle, 0 to 1 across an image with v down; null without. */
    val uvs: FloatArray?,
    /** Each triangle's own color as an index into [palette], -1 for the model's; null when none has one. */
    val colorIndex: IntArray?,
    /** The face colors, as values: a theme name follows the theme, resolved as the mesh is drawn. */
    val palette: List<Value>,
    /** Whether light shades it smoothly across neighbouring faces, as on a sphere, rather than face by face. */
    val smooth: Boolean,
) : Value {
    val triangles get() = tris.size / 3

    /** The unit normal of each triangle, three per triangle; zero for a degenerate one. */
    val faceNormals = FloatArray(tris.size)

    /** Each triangle's size, for weighing its normal in [cornerNormals]: twice its area. */
    private val areas = FloatArray(triangles)

    /** The bounding sphere, for skipping a model that's out of view. */
    val cx: Float
    val cy: Float
    val cz: Float
    val radius: Float

    init {
        val n = FloatArray(3)
        for (t in 0 until triangles) {
            val l = faceCross(points, tris, t, n)
            areas[t] = l
            if (l > 0f) {
                faceNormals[3 * t] = n[0] / l
                faceNormals[3 * t + 1] = n[1] / l
                faceNormals[3 * t + 2] = n[2] / l
            }
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until points.size / 3) {
            minX = minOf(minX, points[3 * i])
            maxX = maxOf(maxX, points[3 * i])
            minY = minOf(minY, points[3 * i + 1])
            maxY = maxOf(maxY, points[3 * i + 1])
            minZ = minOf(minZ, points[3 * i + 2])
            maxZ = maxOf(maxZ, points[3 * i + 2])
        }
        if (points.isEmpty()) {
            cx = 0f
            cy = 0f
            cz = 0f
            radius = 0f
        } else {
            cx = (minX + maxX) / 2
            cy = (minY + maxY) / 2
            cz = (minZ + maxZ) / 2
            var r = 0f
            for (i in 0 until points.size / 3) {
                val dx = points[3 * i] - cx
                val dy = points[3 * i + 1] - cy
                val dz = points[3 * i + 2] - cz
                r = maxOf(r, dx * dx + dy * dy + dz * dz)
            }
            radius = sqrt(r)
        }
    }

    /**
     * Normals at each triangle's corners, nine per triangle, for smooth light: the faces that meet at a corner's
     * point (by position, so seams of a texture don't show), averaged by area, leaving out those turned over 60
     * degrees from the corner's own face, so a cylinder's rim and a cube's edges stay sharp.
     */
    val cornerNormals: FloatArray by lazy {
        val out = FloatArray(tris.size * 3)
        val at = HashMap<Position, MutableList<Int>>()
        fun key(p: Int) = Position(points[3 * p], points[3 * p + 1], points[3 * p + 2])
        for (t in 0 until triangles) for (k in 0..2) at.getOrPut(key(tris[3 * t + k])) { ArrayList(6) } += t
        for (t in 0 until triangles) {
            for (k in 0..2) {
                var nx = 0f
                var ny = 0f
                var nz = 0f
                for (o in at.getValue(key(tris[3 * t + k]))) {
                    val d = faceNormals[3 * o] * faceNormals[3 * t] + faceNormals[3 * o + 1] * faceNormals[3 * t + 1] + faceNormals[3 * o + 2] * faceNormals[3 * t + 2]
                    if (d < 0.5f) continue
                    val w = areas[o]
                    nx += faceNormals[3 * o] * w
                    ny += faceNormals[3 * o + 1] * w
                    nz += faceNormals[3 * o + 2] * w
                }
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                val j = 9 * t + 3 * k
                if (l > 0f) {
                    out[j] = nx / l
                    out[j + 1] = ny / l
                    out[j + 2] = nz / l
                } else {
                    out[j] = faceNormals[3 * t]
                    out[j + 1] = faceNormals[3 * t + 1]
                    out[j + 2] = faceNormals[3 * t + 2]
                }
            }
        }
        out
    }

    override fun toString() = "<mesh of $triangles triangles>"

    private data class Position(
        val x: Float,
        val y: Float,
        val z: Float,
    )
}

/** Collects a mesh's points and triangles; see the builders in [Meshes]. */
internal class MeshBuilder {
    private var points = FloatArray(96)
    private var np = 0
    private var tris = IntArray(96)
    private var nt = 0
    private var uvs: FloatArray? = null
    private var colors: IntArray? = null
    val palette = ArrayList<Value>()
    private val paletteIndex = HashMap<Value, Int>()

    val pointCount get() = np

    fun point(
        x: Float,
        y: Float,
        z: Float,
    ): Int {
        if (3 * np + 3 > points.size) points = points.copyOf(points.size * 2)
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) throw WidgetError("a point of a mesh isn't a number")
        points[3 * np] = x
        points[3 * np + 1] = y
        points[3 * np + 2] = z
        return np++
    }

    /** The palette index of [color], -1 for none. */
    fun color(color: Value?): Int {
        if (color == null || color == Null) return -1
        return paletteIndex.getOrPut(color) { palette.size.also { palette += color } }
    }

    fun tri(
        a: Int,
        b: Int,
        c: Int,
        uv: FloatArray? = null,
        color: Int = -1,
    ) {
        if (nt >= MAX_MESH_TRIANGLES) throw WidgetError("a mesh of more than $MAX_MESH_TRIANGLES triangles")
        if (a !in 0 until np || b !in 0 until np || c !in 0 until np) throw WidgetError("a face names point ${listOf(a, b, c).first { it !in 0 until np }}, but the mesh has ${np} points")
        if (3 * nt + 3 > tris.size) tris = tris.copyOf(tris.size * 2)
        tris[3 * nt] = a
        tris[3 * nt + 1] = b
        tris[3 * nt + 2] = c
        if (uv != null) {
            val u = uvs ?: FloatArray(tris.size * 2).also { uvs = it }
            if (6 * nt + 6 > u.size) uvs = u.copyOf(tris.size * 2)
            System.arraycopy(uv, 0, uvs!!, 6 * nt, 6)
        }
        if (color >= 0 || colors != null) {
            val cs = colors ?: IntArray(tris.size / 3).also { it.fill(-1); colors = it }
            if (nt >= cs.size) colors = cs.copyOf(tris.size / 3).also { it.fill(-1, cs.size) }
            colors!![nt] = color
        }
        nt++
    }

    /**
     * The mesh. With [orient] each triangle is turned to face away from the origin (a convex shape around it),
     * or with [up] to face up; the builders below use it so a mistake in one can't turn a face inside out.
     */
    fun build(
        smooth: Boolean,
        orient: Boolean = false,
        up: Boolean = false,
    ): Mesh {
        val t = tris.copyOf(3 * nt)
        val uv = uvs?.let { u -> FloatArray(6 * nt).also { System.arraycopy(u, 0, it, 0, minOf(u.size, 6 * nt)) } }
        if (orient || up) {
            val n = FloatArray(3)
            for (i in 0 until nt) {
                val a = 3 * t[3 * i]
                val b = 3 * t[3 * i + 1]
                val c = 3 * t[3 * i + 2]
                faceCross(points, t, i, n)
                val facing = if (up) n[1] else n[0] * (points[a] + points[b] + points[c]) + n[1] * (points[a + 1] + points[b + 1] + points[c + 1]) + n[2] * (points[a + 2] + points[b + 2] + points[c + 2])
                if (facing < 0f) {
                    val s = t[3 * i + 1]
                    t[3 * i + 1] = t[3 * i + 2]
                    t[3 * i + 2] = s
                    if (uv != null) {
                        val su = uv[6 * i + 2]
                        val sv = uv[6 * i + 3]
                        uv[6 * i + 2] = uv[6 * i + 4]
                        uv[6 * i + 3] = uv[6 * i + 5]
                        uv[6 * i + 4] = su
                        uv[6 * i + 5] = sv
                    }
                }
            }
        }
        return Mesh(points.copyOf(3 * np), t, uv, colors?.copyOf(nt), palette.toList(), smooth)
    }
}

/** The 3D builtins (owl 22): meshes, and the vector math a game does with them. */
object Meshes {
    /** What an OBJ line's fields are split on. */
    private val WHITESPACE = Regex("\\s+")

    private fun n(d: Double) = Num(d)

    private fun vec(v: List<Double>) = Lst(v.map(::Num))

    /** [v] as numbers: a list's, or a map's x, y and z. */
    fun numbers(v: Value): List<Double> =
        when (v) {
            is Lst -> v.items.map { it.num() ?: 0.0 }
            is Obj -> listOfNotNull(v.fields["x"], v.fields["y"], v.fields["z"]).map { it.num() ?: 0.0 }
            else -> emptyList()
        }

    /** [v] as x, y, z into [out], missing ones from [default]. */
    fun vec3(
        v: Value?,
        default: Float,
        out: FloatArray,
    ): FloatArray {
        out[0] = default
        out[1] = default
        out[2] = default
        when (v) {
            is Lst -> for (i in 0 until minOf(3, v.items.size)) out[i] = v.items[i].num()?.toFloat() ?: default
            is Obj -> {
                v.fields["x"]?.num()?.let { out[0] = it.toFloat() }
                v.fields["y"]?.num()?.let { out[1] = it.toFloat() }
                v.fields["z"]?.num()?.let { out[2] = it.toFloat() }
            }
            null, Null -> {}
            else -> v.num()?.toFloat()?.let { out.fill(it) }
        }
        return out
    }

    private fun segments(
        a: Args,
        i: Int,
        default: Int,
    ) = a.int(i, default).coerceIn(3, 128)

    fun cube(): Mesh {
        val b = MeshBuilder()
        // Each face's outward normal, and right and up on it as seen from outside (right x up = normal).
        val faces =
            arrayOf(
                floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
                floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f),
                floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
                floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
                floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
                floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            )
        for (f in faces) {
            fun corner(
                r: Float,
                u: Float,
            ) = b.point(
                (f[0] + r * f[3] + u * f[6]) / 2,
                (f[1] + r * f[4] + u * f[7]) / 2,
                (f[2] + r * f[5] + u * f[8]) / 2,
            )
            val bl = corner(-1f, -1f)
            val br = corner(1f, -1f)
            val tr = corner(1f, 1f)
            val tl = corner(-1f, 1f)
            b.tri(bl, br, tr, floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f))
            b.tri(bl, tr, tl, floatArrayOf(0f, 1f, 1f, 0f, 0f, 0f))
        }
        return b.build(smooth = false, orient = true)
    }

    /** A square of n x n cells on the ground (y 0, facing up), one unit across; an image repeats once per cell. */
    fun plane(cells: Int): Mesh {
        val b = MeshBuilder()
        val n = cells.coerceIn(1, 128)
        for (i in 0..n) for (j in 0..n) b.point(j.toFloat() / n - 0.5f, 0f, i.toFloat() / n - 0.5f)
        for (i in 0 until n) {
            for (j in 0 until n) {
                val a = i * (n + 1) + j
                val c = a + n + 2
                b.tri(a, a + n + 1, c, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f))
                b.tri(a, c, a + 1, floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f))
            }
        }
        return b.build(smooth = false, up = true)
    }

    /** A sphere one unit across, of [n] segments around and n / 2 rings. */
    fun sphere(n: Int): Mesh {
        val b = MeshBuilder()
        val rings = maxOf(2, n / 2)
        for (i in 0..rings) {
            val th = PI * i / rings
            for (j in 0..n) {
                val ph = 2 * PI * j / n
                b.point((sin(th) * cos(ph) / 2).toFloat(), (cos(th) / 2).toFloat(), (sin(th) * sin(ph) / 2).toFloat())
            }
        }
        for (i in 0 until rings) {
            for (j in 0 until n) {
                val a = i * (n + 1) + j
                val d = a + 1
                val bb = a + n + 1
                val c = bb + 1
                val u0 = j.toFloat() / n
                val u1 = (j + 1f) / n
                val v0 = i.toFloat() / rings
                val v1 = (i + 1f) / rings
                if (i < rings - 1) b.tri(a, bb, c, floatArrayOf(u0, v0, u0, v1, u1, v1))
                if (i > 0) b.tri(a, c, d, floatArrayOf(u0, v0, u1, v1, u1, v0))
            }
        }
        return b.build(smooth = true, orient = true)
    }

    /** A cylinder one unit across and one high, standing on y; [cone] narrows it to a point at the top. */
    fun cylinder(
        n: Int,
        cone: Boolean,
    ): Mesh {
        val b = MeshBuilder()
        val ring = { y: Float, r: Float -> IntArray(n + 1) { j -> b.point((cos(2 * PI * j / n) * r).toFloat(), y, (sin(2 * PI * j / n) * r).toFloat()) } }
        val bottom = ring(-0.5f, 0.5f)
        if (cone) {
            for (j in 0 until n) {
                // A tip of its own per side: the side's light then follows its own slope up to the tip.
                val tip = b.point(0f, 0.5f, 0f)
                val u0 = j.toFloat() / n
                val u1 = (j + 1f) / n
                b.tri(bottom[j], tip, bottom[j + 1], floatArrayOf(u0, 1f, (u0 + u1) / 2, 0f, u1, 1f))
            }
        } else {
            val top = ring(0.5f, 0.5f)
            for (j in 0 until n) {
                val u0 = j.toFloat() / n
                val u1 = (j + 1f) / n
                b.tri(bottom[j], top[j], top[j + 1], floatArrayOf(u0, 1f, u0, 0f, u1, 0f))
                b.tri(bottom[j], top[j + 1], bottom[j + 1], floatArrayOf(u0, 1f, u1, 0f, u1, 1f))
            }
        }
        // Caps of their own points, so their edge stays sharp.
        val caps = if (cone) listOf(-0.5f) else listOf(-0.5f, 0.5f)
        for (y in caps) {
            val c = b.point(0f, y, 0f)
            val r = ring(y, 0.5f)
            for (j in 0 until n) {
                val a0 = 2 * PI * j / n
                val a1 = 2 * PI * (j + 1) / n
                b.tri(
                    c,
                    r[j],
                    r[j + 1],
                    floatArrayOf(0.5f, 0.5f, (0.5 + cos(a0) / 2).toFloat(), (0.5 + sin(a0) / 2).toFloat(), (0.5 + cos(a1) / 2).toFloat(), (0.5 + sin(a1) / 2).toFloat()),
                )
            }
        }
        return b.build(smooth = true, orient = true)
    }

    /**
     * Ground from a grid of heights, rows of numbers: one unit between points, centered on the origin, the first
     * row at the back (-z). [colors] are the cells', rows of one fewer each way; an image repeats once per cell.
     */
    fun terrain(
        heights: Value,
        colors: Value,
    ): Mesh {
        val rows = (heights as? Lst)?.items?.map { r -> (r as? Lst)?.items ?: emptyList() } ?: throw WidgetError("terrain takes rows of heights, like [[0, 1], [1, 2]]")
        if (rows.size < 2 || rows.any { it.size != rows[0].size } || rows[0].size < 2) throw WidgetError("terrain takes at least two rows of the same length, two or more heights each")
        val r = rows.size
        val c = rows[0].size
        if (2L * (r - 1) * (c - 1) > MAX_MESH_TRIANGLES) throw WidgetError("a terrain of more than $MAX_MESH_TRIANGLES triangles")
        val b = MeshBuilder()
        for (i in 0 until r) for (j in 0 until c) b.point(j - (c - 1) / 2f, rows[i][j].num()?.toFloat() ?: 0f, i - (r - 1) / 2f)
        val cells = (colors as? Lst)?.items
        for (i in 0 until r - 1) {
            val row = (cells?.getOrNull(i) as? Lst)?.items
            for (j in 0 until c - 1) {
                val color = b.color(row?.getOrNull(j))
                val a = i * c + j
                b.tri(a, a + c, a + c + 1, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), color)
                b.tri(a, a + c + 1, a + 1, floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f), color)
            }
        }
        return b.build(smooth = true, up = true)
    }

    /** A mesh of [points] ([x, y, z] or {x, y, z}) and [faces], lists of three or more point indices, counterclockwise seen from outside. */
    fun mesh(
        points: Value,
        faces: Value,
        colors: Value,
    ): Mesh {
        val b = MeshBuilder()
        for (p in (points as? Lst)?.items ?: throw WidgetError("mesh takes a list of points, like [[0, 0, 0], [1, 0, 0], [0, 1, 0]]")) {
            val v = numbers(p)
            b.point(v.getOrElse(0) { 0.0 }.toFloat(), v.getOrElse(1) { 0.0 }.toFloat(), v.getOrElse(2) { 0.0 }.toFloat())
        }
        val cs = (colors as? Lst)?.items
        ((faces as? Lst)?.items ?: throw WidgetError("mesh takes a list of faces, like [[0, 1, 2]]")).forEachIndexed { i, f ->
            val idx = numbers(f).map { it.toInt() }
            val color = b.color(cs?.getOrNull(i))
            for (k in 1 until idx.size - 1) b.tri(idx[0], idx[k], idx[k + 1], color = color)
        }
        return b.build(smooth = false)
    }

    /**
     * A Wavefront OBJ model: its points (`v`), texture places (`vt`) and faces (`f`), with each face colored
     * by [materials], a map from its `usemtl` name to a color. Smooth where the file turns smoothing on (`s 1`).
     */
    fun parseObj(
        text: String,
        materials: Value,
    ): Mesh {
        val b = MeshBuilder()
        val vt = ArrayList<Float>()
        var color = -1
        var smooth = false
        val mats = (materials as? Obj)?.fields
        // Faces whose corners all have a texture place carry it; a file with none has no uvs at all.
        val faces = ArrayList<Pair<IntArray, IntArray?>>()
        val faceColors = ArrayList<Int>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val parts = line.split(WHITESPACE)
            when (parts[0]) {
                "v" -> b.point(parts.getOrNull(1)?.toFloatOrNull() ?: 0f, parts.getOrNull(2)?.toFloatOrNull() ?: 0f, parts.getOrNull(3)?.toFloatOrNull() ?: 0f)
                "vt" -> {
                    vt += parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                    vt += parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                }
                "usemtl" -> color = b.color(mats?.get(parts.getOrNull(1) ?: ""))
                "s" -> smooth = smooth || parts.getOrNull(1).let { it != null && it != "off" && it != "0" }
                "f" -> {
                    val corners = parts.drop(1)
                    if (corners.size < 3) continue
                    val pts = IntArray(corners.size)
                    var tex: IntArray? = IntArray(corners.size)
                    corners.forEachIndexed { k, c ->
                        val bits = c.split('/')
                        val p = bits[0].toIntOrNull() ?: throw WidgetError("parseObj: a face corner '$c' isn't a point number")
                        pts[k] = if (p < 0) b.pointCount + p else p - 1
                        val t = bits.getOrNull(1)?.toIntOrNull()
                        if (t == null) tex = null else tex?.set(k, if (t < 0) vt.size / 2 + t else t - 1)
                    }
                    faces += pts to tex
                    faceColors += color
                }
            }
        }
        val uv = FloatArray(6)
        val anyTex = faces.isNotEmpty() && faces.all { it.second != null }
        faces.forEachIndexed { i, (pts, tex) ->
            for (k in 1 until pts.size - 1) {
                if (anyTex) {
                    val corner = intArrayOf(0, k, k + 1)
                    for (m in 0..2) {
                        val t = tex!![corner[m]]
                        uv[2 * m] = vt.getOrElse(2 * t) { 0f }
                        uv[2 * m + 1] = 1f - vt.getOrElse(2 * t + 1) { 0f }
                    }
                }
                b.tri(pts[0], pts[k], pts[k + 1], if (anyTex) uv else null, faceColors[i])
            }
        }
        return b.build(smooth)
    }

    /** turn(...)'s rotation as a 3x3 matrix, row by row: around z, then x, then y, each counterclockwise seen from its positive end. */
    fun rotation(
        rx: Float,
        ry: Float,
        rz: Float,
        out: FloatArray,
    ): FloatArray {
        val a = Math.toRadians(rx.toDouble())
        val b = Math.toRadians(ry.toDouble())
        val c = Math.toRadians(rz.toDouble())
        val cx = cos(a).toFloat()
        val sx = sin(a).toFloat()
        val cy = cos(b).toFloat()
        val sy = sin(b).toFloat()
        val cz = cos(c).toFloat()
        val sz = sin(c).toFloat()
        // Ry * Rx * Rz
        out[0] = cy * cz + sy * sx * sz
        out[1] = -cy * sz + sy * sx * cz
        out[2] = sy * cx
        out[3] = cx * sz
        out[4] = cx * cz
        out[5] = -sx
        out[6] = -sy * cz + cy * sx * sz
        out[7] = sy * sz + cy * sx * cz
        out[8] = cy * cx
        return out
    }

    private fun xyz(v: Value): List<Double> = numbers(v).let { listOf(it.getOrElse(0) { 0.0 }, it.getOrElse(1) { 0.0 }, it.getOrElse(2) { 0.0 }) }

    private fun mesh(
        a: Args,
        build: () -> Mesh,
    ): Mesh = build().also { a.tick(it.triangles / 16) }

    val builtins: Map<String, Builtin> =
        mapOf(
            "cube" to Builtin(0, 0) { a -> mesh(a) { cube() } },
            "plane" to Builtin(0, 1) { a -> mesh(a) { plane(a.int(0, 1)) } },
            "sphere" to Builtin(0, 1) { a -> mesh(a) { sphere(segments(a, 0, 16)) } },
            "cylinder" to Builtin(0, 1) { a -> mesh(a) { cylinder(segments(a, 0, 16), cone = false) } },
            "cone" to Builtin(0, 1) { a -> mesh(a) { cylinder(segments(a, 0, 16), cone = true) } },
            "terrain" to Builtin(1, 2) { a -> mesh(a) { terrain(a[0], a[1]) } },
            "mesh" to Builtin(2, 3) { a -> mesh(a) { mesh(a[0], a[1], a[2]) } },
            "parseObj" to Builtin(1, 2) { a -> a.tick(a.str(0).length / 64); mesh(a) { parseObj(a.str(0), a[1]) } },
            // project(point, camera, width, height): where a point shows on a Canvas of that size, as {x, y, depth}; null behind the camera.
            "project" to
                Builtin(4, 4) { a ->
                    val cam = Camera.of(a[1])
                    val p = vec3(a[0], 0f, FloatArray(3))
                    cam.project(p[0], p[1], p[2], a.num(2).toFloat(), a.num(3).toFloat())?.let { (x, y, d) ->
                        Obj(mapOf("x" to n(x.toDouble()), "y" to n(y.toDouble()), "depth" to n(d.toDouble())))
                    } ?: Null
                },
            // turn(v, [rx, ry, rz]): v turned as model(..., turn:) turns a model.
            "turn" to
                Builtin(2, 2) { a ->
                    val r = vec3(a[1], 0f, FloatArray(3))
                    val m = rotation(r[0], r[1], r[2], FloatArray(9))
                    val v = xyz(a[0])
                    vec(List(3) { i -> m[3 * i] * v[0] + m[3 * i + 1] * v[1] + m[3 * i + 2] * v[2] })
                },
            "dot" to
                Builtin(2, 2) { a ->
                    val x = numbers(a[0])
                    val y = numbers(a[1])
                    n(x.indices.sumOf { i -> x[i] * (y.getOrNull(i) ?: 0.0) })
                },
            "cross" to
                Builtin(2, 2) { a ->
                    val x = xyz(a[0])
                    val y = xyz(a[1])
                    vec(listOf(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]))
                },
            "norm" to
                Builtin(1, 1) { a ->
                    val x = numbers(a[0])
                    val l = sqrt(x.sumOf { it * it })
                    if (l == 0.0) vec(x) else vec(x.map { it / l })
                },
            // dist(a, b): how far apart two points are, in 2D or 3D; dist(v) is a vector's length.
            "dist" to
                Builtin(1, 2) { a ->
                    val x = numbers(a[0])
                    val y = if (a.size > 1) numbers(a[1]) else emptyList()
                    val k = maxOf(x.size, y.size)
                    n(sqrt((0 until k).sumOf { i -> ((x.getOrNull(i) ?: 0.0) - (y.getOrNull(i) ?: 0.0)).let { d -> d * d } }))
                },
        )
}

/**
 * Where a scene is seen from: a map {eye, look, up, fov, near, far}. `eye` is where the camera is, `look` the
 * point it looks at, `fov` how many degrees it sees from the top of the canvas to the bottom.
 */
class Camera(
    val ex: Float,
    val ey: Float,
    val ez: Float,
    /** Right, up and forward, unit vectors; a point's camera coordinates are its offset from the eye along them. */
    val r: FloatArray,
    val u: FloatArray,
    val f: FloatArray,
    val fov: Float,
    val near: Float,
    val far: Float,
) {
    /** The focal length on a canvas [height] high: how far one unit ahead, a unit to the side shows. */
    fun focal(height: Float) = (height / 2) / tan(Math.toRadians(fov / 2.0)).toFloat()

    /** Where world point x, y, z shows on a canvas [w] by [h], and how far ahead it is; null nearer than [near]. */
    fun project(
        x: Float,
        y: Float,
        z: Float,
        w: Float,
        h: Float,
    ): Triple<Float, Float, Float>? {
        val qx = x - ex
        val qy = y - ey
        val qz = z - ez
        val d = qx * f[0] + qy * f[1] + qz * f[2]
        if (d < near) return null
        val s = focal(h) / d
        return Triple(w / 2 + (qx * r[0] + qy * r[1] + qz * r[2]) * s, h / 2 - (qx * u[0] + qy * u[1] + qz * u[2]) * s, d)
    }

    companion object {
        fun of(v: Value): Camera {
            val o = (v as? Obj)?.fields ?: emptyMap()
            val eye = Meshes.vec3(o["eye"], 0f, FloatArray(3))
            if (o["eye"] == null) eye[2] = 5f
            val look = Meshes.vec3(o["look"], 0f, FloatArray(3))
            val up = Meshes.vec3(o["up"] ?: Lst(listOf(Num(0.0), Num(1.0), Num(0.0))), 0f, FloatArray(3))
            val f = unit(look[0] - eye[0], look[1] - eye[1], look[2] - eye[2]) ?: floatArrayOf(0f, 0f, -1f)
            // right = forward x up; straight up or down, any right will do.
            val r = unit(f[1] * up[2] - f[2] * up[1], f[2] * up[0] - f[0] * up[2], f[0] * up[1] - f[1] * up[0]) ?: if (abs(f[0]) < 0.9f) unit(0f, -f[2], f[1])!! else unit(f[2], 0f, -f[0])!!
            val u = floatArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
            val fov = (o["fov"]?.num() ?: 60.0).toFloat().coerceIn(1f, 170f)
            val near = (o["near"]?.num() ?: 0.1).toFloat().coerceAtLeast(0.001f)
            val far = max(near * 2, (o["far"]?.num() ?: 1000.0).toFloat())
            return Camera(eye[0], eye[1], eye[2], r, u, f, fov, near, far)
        }

        private fun unit(
            x: Float,
            y: Float,
            z: Float,
        ): FloatArray? {
            val l = sqrt(x * x + y * y + z * z)
            return if (l < 1e-6f) null else floatArrayOf(x / l, y / l, z / l)
        }
    }
}

/**
 * Turns a scene's models into 2D triangles, ready for one drawVertices call per texture: each model is moved
 * into the camera's view, skipped whole when it's out of view, its faces turned away dropped, the rest lit,
 * fogged, cut at the near plane and projected; then every triangle of the scene is sorted far to near, so the
 * near ones are painted over the far ones (a painter's algorithm: there's no depth buffer).
 *
 * Kept by a Canvas between frames: its arrays grow to the largest scene and are reused.
 */
class Scene3d {
    private lateinit var cam: Camera
    private var w = 0f
    private var h = 0f
    private var focal = 0f
    private var tx = 0f
    private var ty = 0f

    /** Unit vector toward the light, in camera coordinates. */
    private var lx = 0f
    private var ly = 0f
    private var lz = 0f
    private var ambient = 0.35f
    private var fog = false
    private var fogR = 0f
    private var fogG = 0f
    private var fogB = 0f
    private var fogFrom = 0f

    /** Staged triangles: screen x, y of each corner, their colors and uvs, depth and texture. */
    private var n = 0
    private var sxy = FloatArray(6 * 256)
    private var cols = IntArray(3 * 256)
    private var uv = FloatArray(6 * 256)
    private var depth = FloatArray(256)
    private var tex = IntArray(256)
    private var keys = LongArray(256)

    /** The textures models were drawn with, by index; what they are is the renderer's business. */
    val textures = ArrayList<Any?>()
    private val texSizes = ArrayList<Float>()

    /** A model's points in camera coordinates. */
    private var px = FloatArray(256)
    private var py = FloatArray(256)
    private var pz = FloatArray(256)

    /** Output, in drawing order: two floats per vertex, a color per vertex (room for one per float), texture coordinates in image pixels. */
    var verts = FloatArray(0)
        private set
    var colors = IntArray(0)
        private set
    var texs = FloatArray(0)
        private set

    /** Runs of triangles with one texture each: the first triangle, how many, and the index into [textures] (-1 for none). */
    var batches = 0
        private set
    var batchStart = IntArray(16)
        private set
    var batchCount = IntArray(16)
        private set
    var batchTexture = IntArray(16)
        private set

    /** Triangles to draw, after [finish]. */
    val triangles get() = n

    private val rot = FloatArray(9)
    private val tmp = FloatArray(3)

    /** Polygon cut at the near plane: up to 4 corners of x, y, d, r, g, b, a, u, v. */
    private val poly = FloatArray(9 * 4)
    private val corner = FloatArray(9 * 3)

    /**
     * Starts a frame's scene on a canvas [width] by [height]. [light] is the direction toward the light, in
     * the world; [ambient] how bright faces turned from it still are, 0 to 1; [fog] an ARGB color that faces
     * fade into as they near the camera's far end, from halfway there, or null.
     */
    fun begin(
        camera: Camera,
        width: Float,
        height: Float,
        light: FloatArray,
        ambient: Float,
        fog: Int?,
    ) {
        cam = camera
        w = width
        h = height
        focal = camera.focal(height)
        tx = (width / 2) / focal
        ty = (height / 2) / focal
        val l = sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]).takeIf { it > 0f } ?: 1f
        lx = (light[0] * cam.r[0] + light[1] * cam.r[1] + light[2] * cam.r[2]) / l
        ly = (light[0] * cam.u[0] + light[1] * cam.u[1] + light[2] * cam.u[2]) / l
        lz = (light[0] * cam.f[0] + light[1] * cam.f[1] + light[2] * cam.f[2]) / l
        this.ambient = ambient.coerceIn(0f, 1f)
        this.fog = fog != null
        if (fog != null) {
            fogR = ((fog ushr 16) and 0xff).toFloat()
            fogG = ((fog ushr 8) and 0xff).toFloat()
            fogB = (fog and 0xff).toFloat()
        }
        fogFrom = camera.far / 2
        n = 0
        batches = 0
        textures.clear()
        texSizes.clear()
    }

    /**
     * Adds [mesh] at [at], turned by [turn] (degrees around x, y, z) and scaled by [scale], each face its own
     * color from [palette] (ARGB by the mesh's palette index) or [color]. [ambient] NaN takes the scene's.
     * [texture] is an image the mesh's uvs map onto, [texW] by [texH] pixels, or null.
     */
    fun add(
        mesh: Mesh,
        at: FloatArray,
        turn: FloatArray,
        scale: FloatArray,
        color: Int,
        palette: IntArray?,
        ambient: Float,
        smooth: Boolean,
        twoSided: Boolean,
        texture: Any?,
        texW: Float,
        texH: Float,
    ) {
        if (mesh.triangles == 0) return
        val amb = if (ambient.isNaN()) this.ambient else ambient.coerceIn(0f, 1f)
        val r = Meshes.rotation(turn[0], turn[1], turn[2], rot)
        val sx = scale[0]
        val sy = scale[1]
        val sz = scale[2]
        val cr = cam.r
        val cu = cam.u
        val cf = cam.f
        // C = V * R * S, the mesh's points to camera coordinates; o = V * (at - eye).
        val c00 = (cr[0] * r[0] + cr[1] * r[3] + cr[2] * r[6]) * sx
        val c01 = (cr[0] * r[1] + cr[1] * r[4] + cr[2] * r[7]) * sy
        val c02 = (cr[0] * r[2] + cr[1] * r[5] + cr[2] * r[8]) * sz
        val c10 = (cu[0] * r[0] + cu[1] * r[3] + cu[2] * r[6]) * sx
        val c11 = (cu[0] * r[1] + cu[1] * r[4] + cu[2] * r[7]) * sy
        val c12 = (cu[0] * r[2] + cu[1] * r[5] + cu[2] * r[8]) * sz
        val c20 = (cf[0] * r[0] + cf[1] * r[3] + cf[2] * r[6]) * sx
        val c21 = (cf[0] * r[1] + cf[1] * r[4] + cf[2] * r[7]) * sy
        val c22 = (cf[0] * r[2] + cf[1] * r[5] + cf[2] * r[8]) * sz
        val qx = at[0] - cam.ex
        val qy = at[1] - cam.ey
        val qz = at[2] - cam.ez
        val ox = cr[0] * qx + cr[1] * qy + cr[2] * qz
        val oy = cu[0] * qx + cu[1] * qy + cu[2] * qz
        val oz = cf[0] * qx + cf[1] * qy + cf[2] * qz
        // The whole model out of view: nothing to do.
        val mcx = c00 * mesh.cx + c01 * mesh.cy + c02 * mesh.cz + ox
        val mcy = c10 * mesh.cx + c11 * mesh.cy + c12 * mesh.cz + oy
        val mcz = c20 * mesh.cx + c21 * mesh.cy + c22 * mesh.cz + oz
        val mr = mesh.radius * maxOf(abs(sx), abs(sy), abs(sz))
        if (mcz + mr < cam.near || mcz - mr > cam.far) return
        if ((mcx - tx * mcz) > mr * sqrt(1 + tx * tx) || (-mcx - tx * mcz) > mr * sqrt(1 + tx * tx)) return
        if ((mcy - ty * mcz) > mr * sqrt(1 + ty * ty) || (-mcy - ty * mcz) > mr * sqrt(1 + ty * ty)) return
        // Normals: N = V * R * S^-1, normalized after.
        val isx = if (sx != 0f) 1 / sx else 0f
        val isy = if (sy != 0f) 1 / sy else 0f
        val isz = if (sz != 0f) 1 / sz else 0f
        val n00 = c00 * isx * isx
        val n01 = c01 * isy * isy
        val n02 = c02 * isz * isz
        val n10 = c10 * isx * isx
        val n11 = c11 * isy * isy
        val n12 = c12 * isz * isz
        val n20 = c20 * isx * isx
        val n21 = c21 * isy * isy
        val n22 = c22 * isz * isz

        val count = mesh.points.size / 3
        if (px.size < count) {
            px = FloatArray(count)
            py = FloatArray(count)
            pz = FloatArray(count)
        }
        val pts = mesh.points
        for (i in 0 until count) {
            val x = pts[3 * i]
            val y = pts[3 * i + 1]
            val z = pts[3 * i + 2]
            px[i] = c00 * x + c01 * y + c02 * z + ox
            py[i] = c10 * x + c11 * y + c12 * z + oy
            pz[i] = c20 * x + c21 * y + c22 * z + oz
        }
        val texIndex =
            if (texture != null && mesh.uvs != null) {
                // One index per image, so models that share it draw in one batch.
                textures.indexOfFirst { it === texture }.takeIf { it >= 0 } ?: run {
                    textures += texture
                    texSizes += texW
                    texSizes += texH
                    textures.size - 1
                }
            } else {
                -1
            }
        val fn = mesh.faceNormals
        val cn = if (smooth) mesh.cornerNormals else null
        val tris = mesh.tris
        val uvs = mesh.uvs
        val ci = mesh.colorIndex
        val near = cam.near
        val far = cam.far
        for (t in 0 until mesh.triangles) {
            val a = tris[3 * t]
            val b = tris[3 * t + 1]
            val c = tris[3 * t + 2]
            val da = pz[a]
            val db = pz[b]
            val dc = pz[c]
            if (da < near && db < near && dc < near) continue
            if (da > far && db > far && dc > far) continue
            // All on the outside of one side of the view.
            if (px[a] > tx * da && px[b] > tx * db && px[c] > tx * dc) continue
            if (-px[a] > tx * da && -px[b] > tx * db && -px[c] > tx * dc) continue
            if (py[a] > ty * da && py[b] > ty * db && py[c] > ty * dc) continue
            if (-py[a] > ty * da && -py[b] > ty * db && -py[c] > ty * dc) continue
            var fx = n00 * fn[3 * t] + n01 * fn[3 * t + 1] + n02 * fn[3 * t + 2]
            var fy = n10 * fn[3 * t] + n11 * fn[3 * t + 1] + n12 * fn[3 * t + 2]
            var fz = n20 * fn[3 * t] + n21 * fn[3 * t + 1] + n22 * fn[3 * t + 2]
            // Turned away from the eye, which is at the camera's origin: its back shows, or nothing.
            val back = fx * px[a] + fy * py[a] + fz * pz[a] >= 0f
            if (back && !twoSided) continue
            val flip = if (back) -1f else 1f
            // Without nullable Ints: a boxed color per triangle is garbage every frame.
            val pi = if (ci != null) ci[t] else -1
            val base = if (pi >= 0 && palette != null && pi < palette.size) palette[pi] else color
            val alpha = ((base ushr 24) and 0xff).toFloat()
            val br = ((base ushr 16) and 0xff).toFloat()
            val bg = ((base ushr 8) and 0xff).toFloat()
            val bb = (base and 0xff).toFloat()
            var light = 0f
            if (cn == null) {
                val l = sqrt(fx * fx + fy * fy + fz * fz).takeIf { it > 0f } ?: 1f
                fx *= flip / l
                fy *= flip / l
                fz *= flip / l
                light = amb + (1 - amb) * maxOf(0f, fx * lx + fy * ly + fz * lz)
            }
            for (k in 0..2) {
                val p = tris[3 * t + k]
                val o = 9 * k
                corner[o] = px[p]
                corner[o + 1] = py[p]
                corner[o + 2] = pz[p]
                var lit = light
                if (cn != null) {
                    val j = 9 * t + 3 * k
                    val nx = n00 * cn[j] + n01 * cn[j + 1] + n02 * cn[j + 2]
                    val ny = n10 * cn[j] + n11 * cn[j + 1] + n12 * cn[j + 2]
                    val nz = n20 * cn[j] + n21 * cn[j + 1] + n22 * cn[j + 2]
                    val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
                    lit = amb + (1 - amb) * maxOf(0f, (nx * lx + ny * ly + nz * lz) * flip / l)
                }
                var rr = br * lit
                var gg = bg * lit
                var bl = bb * lit
                if (fog) {
                    val f = ((pz[p] - fogFrom) / (far - fogFrom)).coerceIn(0f, 1f)
                    rr += (fogR - rr) * f
                    gg += (fogG - gg) * f
                    bl += (fogB - bl) * f
                }
                corner[o + 3] = rr
                corner[o + 4] = gg
                corner[o + 5] = bl
                corner[o + 6] = alpha
                if (uvs != null && texIndex >= 0) {
                    corner[o + 7] = uvs[6 * t + 2 * k]
                    corner[o + 8] = uvs[6 * t + 2 * k + 1]
                }
            }
            if (da >= near && db >= near && dc >= near) {
                stage(corner, 0, 1, 2, texIndex)
            } else {
                // Cut at the near plane: what's left is a triangle or a quad.
                var m = 0
                for (k in 0..2) {
                    val i = 9 * k
                    val j = 9 * ((k + 1) % 3)
                    val ina = corner[i + 2] >= near
                    val inb = corner[j + 2] >= near
                    if (ina) {
                        System.arraycopy(corner, i, poly, 9 * m, 9)
                        m++
                    }
                    if (ina != inb) {
                        val s = (near - corner[i + 2]) / (corner[j + 2] - corner[i + 2])
                        for (q in 0 until 9) poly[9 * m + q] = corner[i + q] + (corner[j + q] - corner[i + q]) * s
                        m++
                    }
                }
                if (m >= 3) stage(poly, 0, 1, 2, texIndex)
                if (m == 4) stage(poly, 0, 2, 3, texIndex)
            }
        }
    }

    /** Projects corners [a], [b], [c] of [src] (9 floats each) and stages the triangle. */
    private fun stage(
        src: FloatArray,
        a: Int,
        b: Int,
        c: Int,
        texIndex: Int,
    ) {
        if (n >= MAX_SCENE_TRIANGLES) throw WidgetError("more than $MAX_SCENE_TRIANGLES triangles in one scene")
        if (n == depth.size) grow()
        val tw = if (texIndex >= 0) texSizes[2 * texIndex] else 0f
        val th = if (texIndex >= 0) texSizes[2 * texIndex + 1] else 0f
        project(src, a, 0, tw, th)
        project(src, b, 1, tw, th)
        project(src, c, 2, tw, th)
        val d = src[9 * a + 2] + src[9 * b + 2] + src[9 * c + 2]
        depth[n] = d / 3
        tex[n] = texIndex
        n++
    }

    private fun project(
        src: FloatArray,
        i: Int,
        k: Int,
        tw: Float,
        th: Float,
    ) {
        val o = 9 * i
        val s = focal / src[o + 2]
        sxy[6 * n + 2 * k] = w / 2 + src[o] * s
        sxy[6 * n + 2 * k + 1] = h / 2 - src[o + 1] * s
        cols[3 * n + k] = (channel(src[o + 6]) shl 24) or (channel(src[o + 3]) shl 16) or (channel(src[o + 4]) shl 8) or channel(src[o + 5])
        uv[6 * n + 2 * k] = src[o + 7] * tw
        uv[6 * n + 2 * k + 1] = src[o + 8] * th
    }

    private fun channel(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

    private fun grow() {
        val size = depth.size * 2
        sxy = sxy.copyOf(6 * size)
        cols = cols.copyOf(3 * size)
        uv = uv.copyOf(6 * size)
        depth = depth.copyOf(size)
        tex = tex.copyOf(size)
    }

    /** Sorts the staged triangles far to near into [verts], [colors] and [texs], in [batches] by texture. */
    fun finish() {
        if (keys.size < n) keys = LongArray(depth.size)
        // Depths are positive, so their float bits sort as they do; ties keep the order the models came in.
        for (i in 0 until n) keys[i] = (java.lang.Float.floatToRawIntBits(depth[i]).toLong() shl 32) or (n - i).toLong()
        java.util.Arrays.sort(keys, 0, n)
        if (verts.size < 6 * n) {
            val size = maxOf(6 * n, verts.size * 2)
            verts = FloatArray(size)
            texs = FloatArray(size)
            colors = IntArray(size)
        }
        batches = 0
        var last = Int.MIN_VALUE
        for (k in 0 until n) {
            val i = n - (keys[n - 1 - k] and 0xffffffffL).toInt()
            System.arraycopy(sxy, 6 * i, verts, 6 * k, 6)
            System.arraycopy(uv, 6 * i, texs, 6 * k, 6)
            System.arraycopy(cols, 3 * i, colors, 3 * k, 3)
            if (tex[i] != last) {
                if (batches == batchStart.size) {
                    batchStart = batchStart.copyOf(batches * 2)
                    batchCount = batchCount.copyOf(batches * 2)
                    batchTexture = batchTexture.copyOf(batches * 2)
                }
                batchStart[batches] = k
                batchCount[batches] = 0
                batchTexture[batches] = tex[i]
                batches++
                last = tex[i]
            }
            batchCount[batches - 1]++
        }
    }
}
