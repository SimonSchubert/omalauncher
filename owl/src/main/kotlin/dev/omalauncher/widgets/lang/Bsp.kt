package dev.omalauncher.widgets.lang

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** The most triangles a mesh may have for a [Bsp]; past it the scene sorts it by depth alone, as it does every mesh across models. */
const val MAX_BSP_TRIANGLES = 8192

/**
 * A mesh cut into a binary space partition, so a scene can draw its faces in the right order from any side, with no
 * depth buffer: each node is the plane of one of its faces, holding the faces in that plane, with what lies in front
 * of the plane on one side and what lies behind on the other. Seen from the eye, the side away from it is drawn
 * first, then the plane's faces, then the side toward it. Faces a plane passes through are cut in two.
 *
 * Without it, the scene sorts faces by the depth of their middles, and two boxes that overlap (a wheel poking into a
 * car's body) or a big face beside small ones swap places as the view turns. Built once per mesh, which never
 * changes, and only for one that can hide part of itself: a convex mesh (a cube, a sphere, a flat plane) shows its
 * front faces in any order. [mesh] is the cut mesh, three points per face, that the scene draws instead.
 */
class Bsp private constructor(
    val mesh: Mesh,
    /** Each node's plane: normal x, y, z and its distance from the origin along it. */
    private val planes: FloatArray,
    private val front: IntArray,
    private val back: IntArray,
    /** Each node's faces: from [first], [count] of them in [faces]. */
    private val first: IntArray,
    private val count: IntArray,
    private val faces: IntArray,
) {
    /** The faces of [mesh] drawn far to near from eye [ex], [ey], [ez] (in the mesh's own space) into [out]. */
    fun order(
        ex: Float,
        ey: Float,
        ez: Float,
        out: IntArray,
    ) {
        // Without recursion: a tree of a mesh with tens of thousands of faces can be as deep, too deep for the stack.
        // Each entry is a node to visit, or (as -1 - node) one whose faces come next; a node is pushed at most twice.
        val stack = IntArray(2 * count.size)
        var top = 0
        var k = 0
        stack[top++] = 0
        while (top > 0) {
            val node = stack[--top]
            if (node < 0) {
                val m = -1 - node
                for (i in first[m] until first[m] + count[m]) out[k++] = faces[i]
                continue
            }
            val o = 4 * node
            val inFront = planes[o] * ex + planes[o + 1] * ey + planes[o + 2] * ez - planes[o + 3] > 0f
            // Popped last to first: the far side, the node's faces, the near side.
            val near = if (inFront) front[node] else back[node]
            val far = if (inFront) back[node] else front[node]
            if (near >= 0) stack[top++] = near
            stack[top++] = -1 - node
            if (far >= 0) stack[top++] = far
        }
    }

    companion object {
        /** A face while the tree is built: its corners' x, y, z and u, v, and the mesh face it came from. */
        private class Face(
            val p: FloatArray,
            val uv: FloatArray?,
            val src: Int,
        )

        private class TooBig : RuntimeException(null, null, false, false)

        /** The tree for [mesh], or null when it needs none (convex, or one face) or would cut into too many faces. */
        fun build(mesh: Mesh): Bsp? {
            val n = mesh.triangles
            if (n < 2 || n > MAX_BSP_TRIANGLES || isConvex(mesh)) return null
            val eps = 1e-4f * max(1f, mesh.radius)
            val faces = ArrayList<Face>(n)
            val pts = mesh.points
            val tris = mesh.tris
            val uvs = mesh.uvs
            for (t in 0 until n) {
                if (mesh.faceNormals[3 * t] == 0f && mesh.faceNormals[3 * t + 1] == 0f && mesh.faceNormals[3 * t + 2] == 0f) continue
                val p = FloatArray(9)
                for (k in 0..2) System.arraycopy(pts, 3 * tris[3 * t + k], p, 3 * k, 3)
                faces += Face(p, uvs?.copyOfRange(6 * t, 6 * t + 6), t)
            }
            if (faces.size < 2) return null
            // Cutting can multiply the faces; past this the mesh is drawn as it was.
            val limit = minOf(MAX_MESH_TRIANGLES, max(4 * faces.size, faces.size + 2000))
            val out = ArrayList<Face>(faces.size * 2)
            val planes = FloatArrayList()
            val front = IntArrayList()
            val back = IntArrayList()
            val first = IntArrayList()
            val count = IntArrayList()
            // Nodes are built from a stack of (node, its faces, which child of which parent), so a deep tree can't
            // overflow the stack while it's built.
            val work = ArrayDeque<Triple<List<Face>, Int, Boolean>>()
            work.addLast(Triple(faces, -1, false))
            var total = faces.size
            try {
                while (work.isNotEmpty()) {
                    val (list, parent, isFront) = work.removeLast()
                    val node = count.size
                    if (parent >= 0) (if (isFront) front else back)[parent] = node
                    val splitter = pickSplitter(list, eps)
                    val s = plane(splitter)
                    val nx = s[0]
                    val ny = s[1]
                    val nz = s[2]
                    val d = s[3]
                    planes.add(nx, ny, nz, d)
                    front.add(-1)
                    back.add(-1)
                    val on = ArrayList<Face>()
                    val fs = ArrayList<Face>()
                    val bs = ArrayList<Face>()
                    for (f in list) {
                        val d0 = nx * f.p[0] + ny * f.p[1] + nz * f.p[2] - d
                        val d1 = nx * f.p[3] + ny * f.p[4] + nz * f.p[5] - d
                        val d2 = nx * f.p[6] + ny * f.p[7] + nz * f.p[8] - d
                        when {
                            // The splitter stays on its node even when rounding puts a sliver's far corners off its own
                            // plane: each node then takes at least one face, and the tree can't go on forever.
                            f === splitter || (abs(d0) <= eps && abs(d1) <= eps && abs(d2) <= eps) -> on += f
                            d0 >= -eps && d1 >= -eps && d2 >= -eps -> fs += f
                            d0 <= eps && d1 <= eps && d2 <= eps -> bs += f
                            else -> {
                                val before = fs.size + bs.size
                                split(f, floatArrayOf(d0, d1, d2), eps, fs, bs)
                                total += fs.size + bs.size - before - 1
                                if (total > limit) throw TooBig()
                            }
                        }
                    }
                    first.add(out.size)
                    count.add(on.size)
                    out += on
                    if (bs.isNotEmpty()) work.addLast(Triple(bs, node, false))
                    if (fs.isNotEmpty()) work.addLast(Triple(fs, node, true))
                }
            } catch (_: TooBig) {
                return null
            }
            // The cut mesh: three points of its own per face, so each face keeps its corners after a cut.
            val points = FloatArray(9 * out.size)
            val ftris = IntArray(3 * out.size) { it }
            val fuv = if (uvs != null) FloatArray(6 * out.size) else null
            val ci = mesh.colorIndex?.let { c -> IntArray(out.size) { c[out[it].src] } }
            out.forEachIndexed { i, f ->
                System.arraycopy(f.p, 0, points, 9 * i, 9)
                if (fuv != null && f.uv != null) System.arraycopy(f.uv, 0, fuv, 6 * i, 6)
            }
            val cut = Mesh(points, ftris, fuv, ci, mesh.palette, mesh.smooth)
            return Bsp(cut, planes.toArray(), front.toArray(), back.toArray(), first.toArray(), count.toArray(), IntArray(out.size) { it })
        }

        /** Whether every face has all of the mesh's points on or behind its plane: then no face can hide another that faces the eye. */
        private fun isConvex(mesh: Mesh): Boolean {
            val pts = mesh.points
            val eps = 1e-4f * max(1f, mesh.radius)
            for (t in 0 until mesh.triangles) {
                val nx = mesh.faceNormals[3 * t]
                val ny = mesh.faceNormals[3 * t + 1]
                val nz = mesh.faceNormals[3 * t + 2]
                if (nx == 0f && ny == 0f && nz == 0f) continue
                val a = 3 * mesh.tris[3 * t]
                val d = nx * pts[a] + ny * pts[a + 1] + nz * pts[a + 2]
                for (i in 0 until pts.size / 3) {
                    if (nx * pts[3 * i] + ny * pts[3 * i + 1] + nz * pts[3 * i + 2] - d > eps) return false
                }
            }
            return true
        }

        /**
         * The face of [list] whose plane to cut by: of a few spread through the list, the one that cuts the fewest of a
         * sample of the faces, and splits the rest most evenly.
         */
        private fun pickSplitter(
            list: List<Face>,
            eps: Float,
        ): Face {
            var best = list[0]
            var bestScore = Int.MAX_VALUE
            val tries = minOf(list.size, 12)
            val step = max(1, list.size / 200)
            for (c in 0 until tries) {
                val f = list[c * list.size / tries]
                val pl = plane(f)
                var cuts = 0
                var fr = 0
                var bk = 0
                var i = 0
                while (i < list.size) {
                    val g = list[i]
                    var pos = false
                    var neg = false
                    for (k in 0..2) {
                        val dist = pl[0] * g.p[3 * k] + pl[1] * g.p[3 * k + 1] + pl[2] * g.p[3 * k + 2] - pl[3]
                        if (dist > eps) pos = true
                        if (dist < -eps) neg = true
                    }
                    if (pos && neg) cuts++ else if (pos) fr++ else if (neg) bk++
                    i += step
                }
                val score = 8 * cuts + abs(fr - bk)
                if (score < bestScore) {
                    bestScore = score
                    best = f
                }
            }
            return best
        }

        private fun plane(f: Face): FloatArray {
            val p = f.p
            val ux = p[3] - p[0]
            val uy = p[4] - p[1]
            val uz = p[5] - p[2]
            val vx = p[6] - p[0]
            val vy = p[7] - p[1]
            val vz = p[8] - p[2]
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= l
            ny /= l
            nz /= l
            return floatArrayOf(nx, ny, nz, nx * p[0] + ny * p[1] + nz * p[2])
        }

        /** Cuts [f], whose corners are [dist] from the plane, into the part in front (to [fs]) and behind (to [bs]). */
        private fun split(
            f: Face,
            dist: FloatArray,
            eps: Float,
            fs: MutableList<Face>,
            bs: MutableList<Face>,
        ) {
            // Corners as x, y, z, u, v; each side's polygon has at most four.
            val fp = ArrayList<FloatArray>(4)
            val bp = ArrayList<FloatArray>(4)
            fun corner(k: Int) = floatArrayOf(f.p[3 * k], f.p[3 * k + 1], f.p[3 * k + 2], f.uv?.get(2 * k) ?: 0f, f.uv?.get(2 * k + 1) ?: 0f)
            for (k in 0..2) {
                val j = (k + 1) % 3
                val a = corner(k)
                val da = dist[k]
                val db = dist[j]
                if (da >= -eps) fp += a
                if (da <= eps) bp += a
                if ((da > eps && db < -eps) || (da < -eps && db > eps)) {
                    val s = da / (da - db)
                    val b = corner(j)
                    val m = FloatArray(5) { a[it] + (b[it] - a[it]) * s }
                    fp += m
                    bp += m
                }
            }
            fan(fp, f, fs)
            fan(bp, f, bs)
        }

        private fun fan(
            poly: List<FloatArray>,
            f: Face,
            to: MutableList<Face>,
        ) {
            for (k in 1 until poly.size - 1) {
                val c = listOf(poly[0], poly[k], poly[k + 1])
                val p = FloatArray(9)
                val uv = if (f.uv != null) FloatArray(6) else null
                for (q in 0..2) {
                    p[3 * q] = c[q][0]
                    p[3 * q + 1] = c[q][1]
                    p[3 * q + 2] = c[q][2]
                    if (uv != null) {
                        uv[2 * q] = c[q][3]
                        uv[2 * q + 1] = c[q][4]
                    }
                }
                // A sliver with no area left after the cut isn't worth a face.
                val ux = p[3] - p[0]
                val uy = p[4] - p[1]
                val uz = p[5] - p[2]
                val vx = p[6] - p[0]
                val vy = p[7] - p[1]
                val vz = p[8] - p[2]
                val cx = uy * vz - uz * vy
                val cy = uz * vx - ux * vz
                val cz = ux * vy - uy * vx
                if (cx * cx + cy * cy + cz * cz > 1e-12f) to += Face(p, uv, f.src)
            }
        }
    }

    private class FloatArrayList {
        private var a = FloatArray(64)
        private var n = 0

        fun add(vararg xs: Float) {
            if (n + xs.size > a.size) a = a.copyOf(max(a.size * 2, n + xs.size))
            xs.copyInto(a, n)
            n += xs.size
        }

        fun toArray() = a.copyOf(n)
    }

    private class IntArrayList {
        private var a = IntArray(64)
        var size = 0
            private set

        fun add(x: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = x
        }

        operator fun set(
            i: Int,
            x: Int,
        ) {
            a[i] = x
        }

        fun toArray() = a.copyOf(size)
    }
}
