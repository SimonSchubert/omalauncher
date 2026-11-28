package dev.omalauncher.widgets.render

import dev.omalauncher.widgets.lang.BspParts
import dev.omalauncher.widgets.lang.MeshKernel
import dev.omalauncher.widgets.lang.ObjParts

/** JNI bindings, see cpp/mesh3d.c: building a mesh, in the scene3d library. */
object MeshNative {
    @JvmStatic external fun cornerNormals(
        points: FloatArray,
        tris: IntArray,
        faceNormals: FloatArray,
        areas: FloatArray,
        out: FloatArray,
    ): Boolean

    @JvmStatic external fun bsp(
        points: FloatArray,
        tris: IntArray,
        uvs: FloatArray?,
        faceNormals: FloatArray,
        radius: Float,
        limit: Int,
    ): Array<Any?>?

    @JvmStatic external fun scanObj(text: String): Array<Any?>?
}

/** owl's mesh building in C, which gives the same numbers: a big model's first frame comes without the wait. */
object NativeMeshKernel : MeshKernel {
    override fun cornerNormals(
        points: FloatArray,
        tris: IntArray,
        faceNormals: FloatArray,
        areas: FloatArray,
        out: FloatArray,
    ) = scene3dLoaded && MeshNative.cornerNormals(points, tris, faceNormals, areas, out)

    override fun bsp(
        points: FloatArray,
        tris: IntArray,
        uvs: FloatArray?,
        faceNormals: FloatArray,
        radius: Float,
        limit: Int,
    ): BspParts? = if (scene3dLoaded) bspParts(MeshNative.bsp(points, tris, uvs, faceNormals, radius, limit)) else null

    override fun scanObj(text: String): ObjParts? = if (scene3dLoaded) objParts(MeshNative.scanObj(text)) else null

    /** What cpp/mesh3d.c's bsp() answers, as owl takes it. */
    fun bspParts(a: Array<Any?>?): BspParts? =
        when {
            a == null -> null
            a.isEmpty() -> BspParts.NONE
            else -> BspParts(a[0] as FloatArray, a[1] as IntArray, a[2] as FloatArray, a[3] as FloatArray?, a[4] as IntArray)
        }

    /** What cpp/mesh3d.c's scanObj() answers, as owl takes it. */
    @Suppress("UNCHECKED_CAST")
    fun objParts(a: Array<Any?>?): ObjParts? =
        a?.let {
            ObjParts(
                it[0] as FloatArray,
                it[1] as FloatArray,
                it[2] as IntArray,
                it[3] as IntArray,
                it[4] as IntArray,
                it[5] as BooleanArray,
                it[6] as IntArray,
                it[7] as Array<String>,
                (it[8] as BooleanArray)[0],
            )
        }
}
