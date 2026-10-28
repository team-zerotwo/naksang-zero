package kr.zerotwo.naksang.camera

import kotlin.math.abs
import kotlin.math.min

/**
 * Snaps a physical device angle to 0/90/180/270.
 *
 * The activity is locked to portrait, so CameraX keeps rotating frames for a
 * vertical screen. This angle is the phone itself: 0 upright, 90 with the
 * left side up, 180 upside down, 270 with the right side up. A flat phone
 * reports no angle and keeps the last snap.
 *
 * A new quadrant is applied only after it stays clearly inside that quadrant
 * for [STABLE_MS]. Angles near the 45° boundaries do not count.
 */
object PhoneOrientation {
    const val STABLE_MS = 1_000L

    /** Degrees inside each quadrant edge that are too close to call. */
    const val EDGE_MARGIN_DEG = 15

    fun step(
        state: OrientationSnap,
        angleDeg: Int?,
        nowMs: Long,
        stableMs: Long = STABLE_MS,
    ): OrientationSnap {
        if (angleDeg == null || angleDeg < 0) {
            return if (state.pending == null) state else state.copy(pending = null, pendingSinceMs = 0L)
        }
        val clear = clearQuadrant(angleDeg)
            ?: return if (state.pending == null) state else state.copy(pending = null, pendingSinceMs = 0L)
        if (clear == state.snapped) {
            return if (state.pending == null) state else state.copy(pending = null, pendingSinceMs = 0L)
        }
        if (state.pending != clear) {
            return state.copy(pending = clear, pendingSinceMs = nowMs)
        }
        if (nowMs - state.pendingSinceMs >= stableMs) {
            return OrientationSnap(snapped = clear)
        }
        return state
    }

    /**
     * Quadrant whose center is within `45 - [EDGE_MARGIN_DEG]` degrees of [angleDeg],
     * or null when the angle sits in the fuzzy band around 45/135/225/315.
     */
    fun clearQuadrant(angleDeg: Int): Int? {
        val angle = angleDeg.mod(360)
        val centers = intArrayOf(0, 90, 180, 270)
        var best = 0
        var bestDistance = 360
        for (center in centers) {
            val distance = angularDistance(angle, center)
            if (distance < bestDistance) {
                bestDistance = distance
                best = center
            }
        }
        val limit = 45 - EDGE_MARGIN_DEG
        return if (bestDistance <= limit) best else null
    }

    fun label(quadrant: Int): String = when (quadrant.mod(360)) {
        90 -> "가로 왼쪽"
        180 -> "거꾸로"
        270 -> "가로 오른쪽"
        else -> "세로"
    }

    private fun angularDistance(a: Int, b: Int): Int {
        val delta = abs(a - b) % 360
        return min(delta, 360 - delta)
    }
}

data class OrientationSnap(
    val snapped: Int = 0,
    val pending: Int? = null,
    val pendingSinceMs: Long = 0L,
)

/**
 * Extra clockwise degrees applied after CameraX has already made the frame
 * upright for the portrait screen.
 *
 * Front and back cameras look opposite ways, so the same physical turn
 * rotates the picture in opposite directions. Zero leaves the bitmap as
 * CameraX delivered it.
 */
fun analysisCompensationDegrees(deviceQuadrant: Int, frontCamera: Boolean): Int {
    val quadrant = deviceQuadrant.mod(360)
    if (quadrant == 0) return 0
    return if (frontCamera) quadrant else (360 - quadrant) % 360
}
