package kr.zerotwo.naksang.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.Optional

/**
 * Runs the lite pose landmarker on a single analyzer thread.
 * Frames are not stored or sent anywhere.
 */
class PoseReader(private val context: Context) {
    private var landmarker: PoseLandmarker? = null
    private var failed = false

    fun read(bitmap: Bitmap): Array<FloatArray>? {
        val marker = landmarker ?: create() ?: return null
        val image = BitmapImageBuilder(bitmap).build()
        try {
            val result = marker.detect(image)
            val pose = result.landmarks().firstOrNull() ?: return null
            return Array(pose.size) { index ->
                val landmark = pose[index]
                floatArrayOf(landmark.x(), landmark.y(), visibilityOf(landmark))
            }
        } catch (e: Exception) {
            Log.e(TAG, "pose detect failed", e)
            return null
        } finally {
            image.close()
        }
    }

    private fun create(): PoseLandmarker? {
        if (failed) return null
        return try {
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL)
                        .setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                        .build(),
                )
                .setRunningMode(RunningMode.IMAGE)
                // One person in the room. Do not raise this.
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(MIN_POSE_DETECTION_CONFIDENCE)
                .setMinPosePresenceConfidence(MIN_POSE_PRESENCE_CONFIDENCE)
                // IMAGE mode does not track across frames; the fall detector holds
                // a short grace period instead. The value is set so a later switch
                // to VIDEO does not jump back to 0.5.
                .setMinTrackingConfidence(MIN_POSE_TRACKING_CONFIDENCE)
                .build()
            PoseLandmarker.createFromOptions(context, options).also { landmarker = it }
        } catch (e: Exception) {
            failed = true
            Log.e(TAG, "pose model failed to load", e)
            null
        }
    }

    fun loadFailed(): Boolean = failed

    private fun visibilityOf(landmark: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
        val value: Optional<Float> = landmark.visibility()
        return if (value.isPresent) value.get() else 0f
    }

    companion object {
        private const val TAG = "NaksangPose"

        /**
         * Lite, not full. The full float16 task (~9.4MB) is the same kind of
         * download as lite (~5.8MB), but this path stays on the CPU delegate at
         * 640×480. The heavier network would add heat for an accuracy gain we
         * cannot measure here. Pose quality gaps are handled by the lower
         * confidence gates and by [kr.zerotwo.naksang.detect.FallDetector].
         */
        const val MODEL = "pose_landmarker_lite.task"

        const val MIN_POSE_DETECTION_CONFIDENCE = 0.35f
        const val MIN_POSE_PRESENCE_CONFIDENCE = 0.35f
        const val MIN_POSE_TRACKING_CONFIDENCE = 0.35f
    }
}
