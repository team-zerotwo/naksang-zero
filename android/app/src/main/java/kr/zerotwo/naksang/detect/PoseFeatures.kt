package kr.zerotwo.naksang.detect

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Window features for [PoseFallModel]. This is the Kotlin copy of
 * `ml/train/features.py`. The reference samples in the unit test fail when
 * the two drift.
 *
 * Distances that describe shape are divided by torso length. Left and right
 * are averaged or absolute, so a mirrored front camera does not change the
 * vector. A raw drop in image y is not a feature. Drop is kept only when it
 * is multiplied by a flattening or a shorter torso.
 */
object PoseFeatures {
    const val WINDOW = 10
    const val MIN_VALID = 6

    private val keyJoints = intArrayOf(
        PoseJoints.NOSE,
        PoseJoints.LEFT_SHOULDER,
        PoseJoints.RIGHT_SHOULDER,
        PoseJoints.LEFT_HIP,
        PoseJoints.RIGHT_HIP,
        PoseJoints.LEFT_KNEE,
        PoseJoints.RIGHT_KNEE,
        PoseJoints.LEFT_ANKLE,
        PoseJoints.RIGHT_ANKLE,
    )

    fun features(frames: List<Array<FloatArray>?>, timesMs: LongArray): DoubleArray? {
        val paddedFrames = ArrayList<Array<FloatArray>?>(WINDOW)
        val paddedTimes = ArrayList<Long>(WINDOW)
        val start = max(0, frames.size - WINDOW)
        for (index in start until frames.size) {
            paddedFrames += frames[index]
            paddedTimes += timesMs[index]
        }
        while (paddedFrames.size < WINDOW) {
            val anchor = paddedTimes.firstOrNull() ?: 0L
            paddedFrames.add(0, null)
            paddedTimes.add(0, anchor - 200L)
        }
        val smoothed = smooth(paddedFrames)
        val geoms = Array(WINDOW) { geometry(smoothed[it]) }
        return fromGeoms(geoms, paddedTimes.toLongArray())
    }

    private data class Geom(
        val angle: Double,
        val torso: Double,
        val cx: Double,
        val cy: Double,
        val hy: Double,
        val bw: Double,
        val bh: Double,
        val noseY: Double?,
        val kneeY: Double?,
        val ankleY: Double?,
        val shoulderW: Double,
        val hipW: Double,
        val vis: Double,
    )

    private fun smooth(frames: List<Array<FloatArray>?>): List<Array<DoubleArray>?> {
        val trackedX = DoubleArray(33)
        val trackedY = DoubleArray(33)
        val seen = BooleanArray(33)
        val alpha = PoseFallWeights.SMOOTH_ALPHA
        val minVis = PoseFallWeights.MIN_VISIBILITY.toDouble()
        val out = ArrayList<Array<DoubleArray>?>(frames.size)
        for (frame in frames) {
            if (frame == null) {
                out += null
                seen.fill(false)
                continue
            }
            val row = Array(33) { index ->
                val point = frame.getOrNull(index)
                doubleArrayOf(
                    point?.getOrNull(0)?.toDouble() ?: 0.0,
                    point?.getOrNull(1)?.toDouble() ?: 0.0,
                    point?.getOrNull(2)?.toDouble() ?: 0.0,
                )
            }
            for (index in keyJoints) {
                val point = row[index]
                if (point[2] < minVis) {
                    seen[index] = false
                    continue
                }
                var x = point[0]
                var y = point[1]
                if (seen[index]) {
                    x = alpha * x + (1.0 - alpha) * trackedX[index]
                    y = alpha * y + (1.0 - alpha) * trackedY[index]
                }
                trackedX[index] = x
                trackedY[index] = y
                seen[index] = true
                point[0] = x
                point[1] = y
            }
            out += row
        }
        return out
    }

    private fun geometry(frame: Array<DoubleArray>?): Geom? {
        if (frame == null) return null
        val minVis = PoseFallWeights.MIN_VISIBILITY.toDouble()
        val shoulders = meanXy(frame, intArrayOf(PoseJoints.LEFT_SHOULDER, PoseJoints.RIGHT_SHOULDER), minVis) ?: return null
        val hips = meanXy(frame, intArrayOf(PoseJoints.LEFT_HIP, PoseJoints.RIGHT_HIP), minVis) ?: return null
        val sx = shoulders[0]
        val sy = shoulders[1]
        val hx = hips[0]
        val hy = hips[1]
        val angle = Math.toDegrees(atan2(abs(sy - hy), abs(sx - hx)))
        val torso = hypot(sx - hx, sy - hy)
        var vis = 0
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (index in keyJoints) {
            val point = frame[index]
            if (point[2] < minVis) continue
            vis += 1
            minX = min(minX, point[0])
            maxX = max(maxX, point[0])
            minY = min(minY, point[1])
            maxY = max(maxY, point[1])
        }
        if (vis == 0) return null
        val bothShoulders = frame[PoseJoints.LEFT_SHOULDER][2] >= minVis && frame[PoseJoints.RIGHT_SHOULDER][2] >= minVis
        val bothHips = frame[PoseJoints.LEFT_HIP][2] >= minVis && frame[PoseJoints.RIGHT_HIP][2] >= minVis
        val shoulderW = if (bothShoulders) {
            abs(frame[PoseJoints.LEFT_SHOULDER][0] - frame[PoseJoints.RIGHT_SHOULDER][0])
        } else {
            0.0
        }
        val hipW = if (bothHips) abs(frame[PoseJoints.LEFT_HIP][0] - frame[PoseJoints.RIGHT_HIP][0]) else 0.0
        val nose = frame[PoseJoints.NOSE]
        return Geom(
            angle = angle,
            torso = torso,
            cx = (sx + hx) / 2.0,
            cy = (sy + hy) / 2.0,
            hy = hy,
            bw = maxX - minX,
            bh = maxY - minY,
            noseY = if (nose[2] >= minVis) nose[1] else null,
            kneeY = meanY(frame, intArrayOf(PoseJoints.LEFT_KNEE, PoseJoints.RIGHT_KNEE), minVis),
            ankleY = meanY(frame, intArrayOf(PoseJoints.LEFT_ANKLE, PoseJoints.RIGHT_ANKLE), minVis),
            shoulderW = shoulderW,
            hipW = hipW,
            vis = vis / keyJoints.size.toDouble(),
        )
    }

    private fun fromGeoms(geoms: Array<Geom?>, timesMs: LongArray): DoubleArray? {
        val valid = ArrayList<Pair<Int, Geom>>(WINDOW)
        for (index in geoms.indices) {
            val geom = geoms[index] ?: continue
            valid += index to geom
        }
        if (valid.size < MIN_VALID) return null
        val first = valid.first().second
        val last = valid.last().second
        var peakDisp = 0.0
        for (i in 0 until valid.size - 1) {
            val (_, g1) = valid[i]
            val (_, g2) = valid[i + 1]
            val disp = hypot(g2.cx - g1.cx, g2.cy - g1.cy)
            if (disp > peakDisp) peakDisp = disp
        }
        val eps = 1e-3
        val torsoStart = max(first.torso, eps)
        val bhStart = max(first.bh, eps)
        var angleMin = Double.POSITIVE_INFINITY
        var cyMax = Double.NEGATIVE_INFINITY
        var torsoMin = Double.POSITIVE_INFINITY
        var visSum = 0.0
        for ((_, geom) in valid) {
            if (geom.angle < angleMin) angleMin = geom.angle
            if (geom.cy > cyMax) cyMax = geom.cy
            if (geom.torso < torsoMin) torsoMin = geom.torso
            visSum += geom.vis
        }
        val angleEnd = last.angle
        val angleStart = first.angle
        val cyDrop = last.cy - first.cy
        val cyDropMax = cyMax - first.cy
        val torsoRatio = last.torso / torsoStart
        val torsoMinRatio = torsoMin / torsoStart
        val aspectEnd = last.bw / max(last.bh, eps)
        val aspectStart = first.bw / max(first.bh, eps)
        val heightRatio = last.bh / bhStart
        val torsoEnd = max(last.torso, eps)
        val legExt = if (last.ankleY != null) (last.ankleY - last.hy) / torsoEnd else 0.0
        val kneeExt = if (last.kneeY != null) (last.kneeY - last.hy) / torsoEnd else 0.0
        val noseAbove = if (last.noseY != null) (last.hy - last.noseY) / torsoEnd else 0.0
        val flatEver = if (angleMin <= 45.0) 1.0 else 0.0
        val uprightStart = if (angleStart >= 58.0) 1.0 else 0.0
        return doubleArrayOf(
            angleEnd / 90.0,
            angleStart / 90.0,
            (angleStart - angleMin) / 90.0,
            (angleEnd - angleStart) / 90.0,
            torsoRatio,
            torsoMinRatio,
            aspectEnd,
            aspectStart,
            aspectEnd - aspectStart,
            heightRatio,
            valid.size / WINDOW.toDouble(),
            visSum / valid.size,
            legExt,
            if (last.ankleY != null) 1.0 else 0.0,
            last.shoulderW / torsoEnd,
            last.hipW / torsoEnd,
            noseAbove,
            if (last.noseY != null) 1.0 else 0.0,
            uprightStart,
            if (angleEnd <= 45.0) 1.0 else 0.0,
            flatEver,
            if (angleEnd >= 65.0) 1.0 else 0.0,
            cyDrop * (1.0 - angleEnd / 90.0),
            cyDrop * (1.0 - torsoRatio),
            peakDisp * flatEver,
            cyDropMax * uprightStart,
            kneeExt,
        )
    }

    private fun meanXy(frame: Array<DoubleArray>, indexes: IntArray, minVis: Double): DoubleArray? {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (index in indexes) {
            val point = frame[index]
            if (point[2] < minVis) continue
            sx += point[0]
            sy += point[1]
            n += 1
        }
        if (n == 0) return null
        return doubleArrayOf(sx / n, sy / n)
    }

    private fun meanY(frame: Array<DoubleArray>, indexes: IntArray, minVis: Double): Double? {
        var sy = 0.0
        var n = 0
        for (index in indexes) {
            val point = frame[index]
            if (point[2] < minVis) continue
            sy += point[1]
            n += 1
        }
        if (n == 0) return null
        return sy / n
    }
}
