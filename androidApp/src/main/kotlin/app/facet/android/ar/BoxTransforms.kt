package app.facet.android.ar

import android.opengl.Matrix
import app.facet.scan.PlacedBox
import com.google.ar.core.Pose

/**
 * Transforms between ARCore world space (metres, Y up) and the scan box frame used by the C++
 * volume (millimetres, Z up, origin at the centre of the box base, X = width). All matrices are
 * column-major (android.opengl.Matrix).
 */
internal class BoxTransforms(anchor: FloatArray, box: PlacedBox) {
    /** Box mm ← world m. */
    val boxFromWorld: FloatArray

    /** World m ← box mm (renders box-frame meshes in AR). */
    val worldFromBox: FloatArray

    init {
        val localFromWorld = FloatArray(16).also { Matrix.invertM(it, 0, anchor, 0) }
        // Plane frame (Z up, mm) ← anchor-local (Y up, m): (x, y, z)_plane = 1000 · (lx, −lz, ly).
        val planeFromLocal = floatArrayOf(
            1000f, 0f, 0f, 0f,
            0f, 0f, 1000f, 0f,
            0f, -1000f, 0f, 0f,
            0f, 0f, 0f, 1f,
        )
        // Box ← plane: undo the box's position and yaw.
        val boxFromPlane = FloatArray(16).also {
            Matrix.setRotateM(it, 0, (-box.yawDeg).toFloat(), 0f, 0f, 1f)
            Matrix.translateM(it, 0, (-box.centerX).toFloat(), (-box.centerY).toFloat(), 0f)
        }
        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, planeFromLocal, 0, localFromWorld, 0)
        boxFromWorld = FloatArray(16).also { Matrix.multiplyMM(it, 0, boxFromPlane, 0, tmp, 0) }
        worldFromBox = FloatArray(16).also { Matrix.invertM(it, 0, boxFromWorld, 0) }
    }

    /** Box mm ← camera-frame mm (+X right, +Y up, −Z forward), for depth fusion. */
    fun cameraToBox(cameraPose: Pose): FloatArray {
        val worldFromCamera = FloatArray(16).also { cameraPose.toMatrix(it, 0) }
        Matrix.scaleM(worldFromCamera, 0, 0.001f, 0.001f, 0.001f) // camera mm → m
        return FloatArray(16).also { Matrix.multiplyMM(it, 0, boxFromWorld, 0, worldFromCamera, 0) }
    }
}
