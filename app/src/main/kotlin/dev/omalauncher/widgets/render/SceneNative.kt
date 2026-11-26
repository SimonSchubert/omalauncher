package dev.omalauncher.widgets.render

import android.util.Log
import dev.omalauncher.widgets.lang.SceneKernel

/** JNI bindings, see cpp/scene3d.c: a 3D scene's work per triangle. */
object SceneNative {
    @JvmStatic external fun create(): Long

    @JvmStatic external fun destroy(scene: Long)

    @JvmStatic external fun reset(scene: Long)

    @JvmStatic external fun add(
        scene: Long,
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
    ): Int

    @JvmStatic external fun finish(
        scene: Long,
        verts: FloatArray,
        texs: FloatArray,
        colors: IntArray,
        batches: IntArray,
    ): Int
}

/** A scene's triangles in C, two to three times as fast as owl's Kotlin on a model of many faces; the same numbers. */
class NativeKernel private constructor(
    private val scene: Long,
) : SceneKernel {
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

    /** The C side's memory goes with the Canvas that kept the scene. */
    protected fun finalize() = SceneNative.destroy(scene)

    companion object {
        private val loaded by lazy {
            try {
                System.loadLibrary("scene3d")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w("owl", "scenes are drawn without scene3d", e)
                false
            }
        }

        /** A kernel, or null where the library isn't there (a JVM test) or memory ran out: the scene then does the work itself. */
        fun create(): NativeKernel? = if (loaded) SceneNative.create().takeIf { it != 0L }?.let(::NativeKernel) else null
    }
}
