package kr.zerotwo.naksang.detect

import kotlin.math.max

/**
 * MediaPipe Pose Landmarker skeleton.
 * Edges match mediapipe/tasks `pose_landmarks_connections.h` (35 connections).
 * The analyzer bitmap is already rotated upright; [mapPosePoint] only does the
 * preview FILL_CENTER crop and the front-camera mirror.
 */
object PoseSkeleton {
    const val LANDMARK_COUNT = 33

    val CONNECTIONS: List<Pair<Int, Int>> = listOf(
        0 to 4,
        4 to 5,
        5 to 6,
        6 to 8,
        0 to 1,
        1 to 2,
        2 to 3,
        3 to 7,
        10 to 9,
        12 to 11,
        12 to 14,
        14 to 16,
        16 to 18,
        16 to 20,
        16 to 22,
        18 to 20,
        11 to 13,
        13 to 15,
        15 to 17,
        15 to 19,
        15 to 21,
        17 to 19,
        12 to 24,
        11 to 23,
        24 to 23,
        24 to 26,
        23 to 25,
        26 to 28,
        25 to 27,
        28 to 30,
        27 to 29,
        30 to 32,
        29 to 31,
        28 to 32,
        27 to 31,
    )
}

data class ScreenPoint(val x: Float, val y: Float)

data class SkeletonLayout(
    val lines: List<Pair<ScreenPoint, ScreenPoint>>,
    val dots: List<ScreenPoint>,
)

/**
 * Map a normalized upright-frame point into a view that draws that frame with
 * FILL_CENTER (scale to cover, crop the overflow, centered).
 * [mirrorX] flips the front-camera preview, which CameraX mirrors for the user.
 */
fun mapPosePoint(
    normX: Float,
    normY: Float,
    frameWidth: Int,
    frameHeight: Int,
    viewWidth: Float,
    viewHeight: Float,
    mirrorX: Boolean,
): ScreenPoint {
    val xNorm = if (mirrorX) 1f - normX.coerceIn(0f, 1f) else normX.coerceIn(0f, 1f)
    val yNorm = normY.coerceIn(0f, 1f)
    if (frameWidth <= 0 || frameHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
        return ScreenPoint(xNorm * viewWidth, yNorm * viewHeight)
    }
    val scale = max(viewWidth / frameWidth.toFloat(), viewHeight / frameHeight.toFloat())
    val drawnW = frameWidth * scale
    val drawnH = frameHeight * scale
    val left = (viewWidth - drawnW) / 2f
    val top = (viewHeight - drawnH) / 2f
    return ScreenPoint(left + xNorm * drawnW, top + yNorm * drawnH)
}

/** Lines and dots for landmarks at or above [minVisibility]. Hidden joints are omitted. */
fun layoutSkeleton(
    landmarks: List<JointPoint>,
    minVisibility: Float,
    frameWidth: Int,
    frameHeight: Int,
    viewWidth: Float,
    viewHeight: Float,
    mirrorX: Boolean,
): SkeletonLayout {
    if (landmarks.isEmpty() || viewWidth <= 0f || viewHeight <= 0f) {
        return SkeletonLayout(emptyList(), emptyList())
    }
    val points = Array<ScreenPoint?>(landmarks.size) { index ->
        val joint = landmarks[index]
        if (joint.visibility < minVisibility) {
            null
        } else {
            mapPosePoint(joint.x, joint.y, frameWidth, frameHeight, viewWidth, viewHeight, mirrorX)
        }
    }
    val lines = ArrayList<Pair<ScreenPoint, ScreenPoint>>()
    for ((a, b) in PoseSkeleton.CONNECTIONS) {
        val start = points.getOrNull(a) ?: continue
        val end = points.getOrNull(b) ?: continue
        lines += start to end
    }
    val dots = ArrayList<ScreenPoint>(points.size)
    for (point in points) {
        if (point != null) dots += point
    }
    return SkeletonLayout(lines, dots)
}
