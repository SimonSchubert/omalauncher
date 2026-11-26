package dev.omalauncher.widgets.lang

/**
 * The work of building a mesh, done somewhere faster than here: the app's is C (cpp/mesh3d.c). Each must give the
 * numbers the Kotlin in [Mesh], [Bsp] and [Meshes.parseObj] gives, and may answer null to leave the work to it.
 */
interface MeshKernel {
    /** [Mesh.cornerNormals] into [out], nine per triangle; false to leave it to the Kotlin. */
    fun cornerNormals(
        points: FloatArray,
        tris: IntArray,
        faceNormals: FloatArray,
        areas: FloatArray,
        out: FloatArray,
    ): Boolean

    /**
     * [Bsp.build]'s tree of a mesh of 2 to [MAX_BSP_TRIANGLES] triangles: [BspParts.NONE] when it needs none or cuts into
     * more than [limit] faces, null to leave it to the Kotlin.
     */
    fun bsp(
        points: FloatArray,
        tris: IntArray,
        uvs: FloatArray?,
        faceNormals: FloatArray,
        radius: Float,
        limit: Int,
    ): BspParts?

    /** An OBJ file's lines read as [Meshes.parseObj] reads them, or null to leave it to the Kotlin. */
    fun scanObj(text: String): ObjParts?
}

/** The [MeshKernel] meshes are built with, if any: the app sets one. */
object MeshKernels {
    @Volatile
    var kernel: MeshKernel? = null
}

/** A [Bsp] as built: see its fields. The cut mesh's faces are one per [src], three points and six uvs each. */
class BspParts(
    val planes: FloatArray,
    val nodes: IntArray,
    val points: FloatArray,
    val uvs: FloatArray?,
    val src: IntArray,
) {
    companion object {
        /** No tree. */
        val NONE = BspParts(FloatArray(0), IntArray(0), FloatArray(0), null, IntArray(0))
    }
}

/**
 * An OBJ file's content, for [Meshes.parseObj] to build its mesh from. Faces are [faceStart]'s ranges of [corners]
 * (point indices, already counted from 0) and [cornerTex] (texture place indices, the same), without texture places
 * where [faceTex] is false; [faceMaterial] is each face's index into [materials], the `usemtl` lines in the order they
 * came, -1 before the first.
 */
class ObjParts(
    val points: FloatArray,
    val vt: FloatArray,
    val faceStart: IntArray,
    val corners: IntArray,
    val cornerTex: IntArray,
    val faceTex: BooleanArray,
    val faceMaterial: IntArray,
    val materials: Array<String>,
    val smooth: Boolean,
)
