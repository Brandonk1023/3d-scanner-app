package app.facet.android.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.facet.mesh.ObjectMask
import app.facet.scan.ObjectSegmenter
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenterlegacy.InteractiveSegmenterLegacy
import java.nio.ByteOrder

/**
 * [ObjectSegmenter] on MediaPipe's point-seeded interactive segmenter ("Magic Touch" model,
 * bundled in assets — runs fully on device). Not thread-safe: use from one thread.
 */
class MediaPipeSegmenter(context: Context) : ObjectSegmenter {
    private val segmenter: InteractiveSegmenterLegacy = InteractiveSegmenterLegacy.createFromOptions(
        context.applicationContext,
        InteractiveSegmenterLegacy.InteractiveSegmenterLegacyOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build(),
    )

    override fun segment(jpeg: ByteArray, seedX: Float, seedY: Float): ObjectMask? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return null
        return try {
            val result = segmenter.segment(
                BitmapImageBuilder(bitmap).build(),
                InteractiveSegmenterLegacy.RegionOfInterest.create(NormalizedKeypoint.create(seedX, seedY)),
            )
            val mask = result.confidenceMasks().orElse(null)?.firstOrNull() ?: return null
            val w = mask.width
            val h = mask.height
            val values = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val pixels = ByteArray(w * h) { if (values.get(it) > THRESHOLD) 1 else 0 }
            ObjectMask(w, h, pixels)
        } catch (e: Exception) {
            Log.w(TAG, "Segmentation failed", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = segmenter.close()

    private companion object {
        const val TAG = "FacetSegmenter"
        const val MODEL = "magic_touch.tflite"
        const val THRESHOLD = 0.5f
    }
}
