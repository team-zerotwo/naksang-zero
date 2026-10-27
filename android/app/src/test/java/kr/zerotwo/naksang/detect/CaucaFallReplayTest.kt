package kr.zerotwo.naksang.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The synthetic poses match FallDetectorTest: a real fall is a short drop into
 * a flat torso that then holds still. These tests only check that the CSV
 * replay calls that detector. They do not need CAUCAFall.
 */
class CaucaFallReplayTest {
    @Test
    fun `CSV 왕복과 넘어짐 재생`() {
        val frames = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_500, until = 5_000)
        val dir = Files.createTempDirectory("cauca-replay").toFile()
        val csv = java.io.File(dir, "fall.csv")
        CaucaFallReplay.writeCsv(csv, frames)
        val read = CaucaFallReplay.readCsv(csv)
        assertEquals(frames.size, read.size)
        assertEquals(1_000L, read.first { it.timestampMs == 1_000L }.timestampMs)
        assertEquals(frames[4].joints[PoseJoints.LEFT_HIP][1], read[4].joints[PoseJoints.LEFT_HIP][1], 0.001f)
        val eval = CaucaFallReplay.evaluate(read, meta("fall_demo", "fall", "forward", onsetMs = 1_500, durationMs = 5_000))
        assertTrue("낙상 CSV 가 감지되어야 합니다. at=${eval.detectedAtMs}", eval.detected)
        assertTrue(eval.detectedAtMs!! >= 1_500 + 2_000 - 200)
        assertNull(eval.blocker)
    }

    @Test
    fun `앉기 CSV 는 낙상이 아니다`() {
        val frames = hold(stand(), sitting(), leadIn = 1_000, transitionEnd = 2_200, until = 6_000)
        val eval = CaucaFallReplay.evaluate(frames, meta("sit_demo", "adl", "sitting", onsetMs = null, durationMs = 6_000))
        assertEquals(false, eval.detected)
        assertNull(eval.blocker)
    }

    @Test
    fun `하강이 작으면 min_drop 이 막는다`() {
        val shortFall = shiftY(lying(), -0.12f)
        val frames = hold(stand(), shortFall, leadIn = 1_000, transitionEnd = 1_500, until = 5_000)
        val eval = CaucaFallReplay.evaluate(frames, meta("small_drop", "fall", "forward", onsetMs = 1_500, durationMs = 5_000))
        assertEquals(false, eval.detected)
        assertEquals("min_drop", eval.blocker)
        assertTrue(eval.unblockedBy.contains("min_drop"))
    }

    @Test
    fun `매니페스트 재생은 낙상과 앉기를 가른다`() {
        val root = Files.createTempDirectory("cauca-manifest").toFile()
        val fall = hold(stand(), lying(), 1_000, 1_500, 5_000)
        val sit = hold(stand(), sitting(), 1_000, 2_200, 6_000)
        CaucaFallReplay.writeCsv(java.io.File(root, "s01_forward.csv"), fall)
        CaucaFallReplay.writeCsv(java.io.File(root, "s01_sitting.csv"), sit)
        val manifest = java.io.File(root, "manifest.csv")
        manifest.writeText(
            "video_id,subject,activity,kind,activity_type,csv_file,onset_ms,duration_ms\n" +
                "s01_forward,Subject.1,Fall forward,fall,forward,s01_forward.csv,1500,5000\n" +
                "s01_sitting,Subject.1,Sit down,adl,sitting,s01_sitting.csv,,6000\n",
        )
        val out = java.io.File(root, "out")
        CaucaFallReplay.evaluateAndWrite(CaucaFallReplay.readManifest(manifest, root), out)
        val rows = java.io.File(out, "per_video.csv").readLines().filter { it.isNotBlank() }
        assertEquals(3, rows.size)
        val fallRow = CaucaFallReplay.parseCsvLine(rows[1])
        val sitRow = CaucaFallReplay.parseCsvLine(rows[2])
        assertEquals("s01_forward", fallRow[0])
        assertEquals("1", fallRow[10])
        assertEquals("s01_sitting", sitRow[0])
        assertEquals("0", sitRow[10])
        assertTrue(java.io.File(out, "threshold_sweep.csv").readText().lineSequence().any { it.startsWith("default,") })
        assertTrue(java.io.File(out, "threshold_sweep.csv").readText().contains("grid,"))
    }
}

class CaucaFallDatasetReplayTest {
    @Test
    fun `CAUCAFall CSV 를 출고 FallDetector 로 재생한다`() {
        val csvDir = System.getProperty("cauca.csvDir").orEmpty()
        org.junit.Assume.assumeTrue(
            "Set -PcsvDir to replay CAUCAFall. This skip is expected in unit tests.",
            csvDir.isNotBlank(),
        )
        val outDir = System.getProperty("cauca.outDir") ?: error("cauca.outDir is required")
        val dir = java.io.File(csvDir)
        val manifest = java.io.File(dir, "manifest.csv")
        assertTrue("manifest missing: ${manifest.absolutePath}", manifest.isFile)
        val videos = CaucaFallReplay.readManifest(manifest, dir)
        assertTrue("manifest has no videos", videos.isNotEmpty())
        CaucaFallReplay.evaluateAndWrite(videos, java.io.File(outDir))
        assertTrue(java.io.File(outDir, "per_video.csv").isFile)
        assertTrue(java.io.File(outDir, "threshold_sweep.csv").isFile)
    }
}

private fun meta(
    id: String,
    kind: String,
    activityType: String,
    onsetMs: Long?,
    durationMs: Long,
) = CaucaFallReplay.VideoMeta(
    videoId = id,
    subject = "Subject.1",
    activity = activityType,
    kind = kind,
    activityType = activityType,
    onsetMs = onsetMs,
    durationMs = durationMs,
    csvFile = java.io.File("unused.csv"),
)

private fun hold(
    from: Array<FloatArray>,
    to: Array<FloatArray>,
    leadIn: Long,
    transitionEnd: Long,
    until: Long,
    step: Long = 100,
): List<CaucaFallReplay.ReplayFrame> {
    val frames = mutableListOf<CaucaFallReplay.ReplayFrame>()
    var t = 0L
    var index = 0
    while (t <= until) {
        val pose = when {
            t <= leadIn -> from
            t >= transitionEnd -> to
            else -> lerpPose(from, to, (t - leadIn).toFloat() / (transitionEnd - leadIn))
        }
        frames += CaucaFallReplay.ReplayFrame(t, index, poseFound = true, joints = pose)
        t += step
        index += 1
    }
    return frames
}

private fun lerpPose(a: Array<FloatArray>, b: Array<FloatArray>, u: Float): Array<FloatArray> {
    return Array(a.size) { index ->
        floatArrayOf(
            a[index][0] + (b[index][0] - a[index][0]) * u,
            a[index][1] + (b[index][1] - a[index][1]) * u,
            1f,
        )
    }
}

private fun pose(ls: FloatArray, rs: FloatArray, lh: FloatArray, rh: FloatArray): Array<FloatArray> {
    val joints = Array(33) { floatArrayOf(0f, 0f, 0f) }
    joints[PoseJoints.LEFT_SHOULDER] = floatArrayOf(ls[0], ls[1], 1f)
    joints[PoseJoints.RIGHT_SHOULDER] = floatArrayOf(rs[0], rs[1], 1f)
    joints[PoseJoints.LEFT_HIP] = floatArrayOf(lh[0], lh[1], 1f)
    joints[PoseJoints.RIGHT_HIP] = floatArrayOf(rh[0], rh[1], 1f)
    return joints
}

private fun stand() = pose(
    ls = floatArrayOf(0.42f, 0.20f),
    rs = floatArrayOf(0.58f, 0.20f),
    lh = floatArrayOf(0.44f, 0.48f),
    rh = floatArrayOf(0.56f, 0.48f),
)

private fun lying() = pose(
    ls = floatArrayOf(0.28f, 0.55f),
    rs = floatArrayOf(0.36f, 0.52f),
    lh = floatArrayOf(0.62f, 0.58f),
    rh = floatArrayOf(0.72f, 0.54f),
)

private fun sitting() = pose(
    ls = floatArrayOf(0.42f, 0.38f),
    rs = floatArrayOf(0.58f, 0.38f),
    lh = floatArrayOf(0.44f, 0.70f),
    rh = floatArrayOf(0.56f, 0.70f),
)

private fun shiftY(pose: Array<FloatArray>, dy: Float): Array<FloatArray> {
    return Array(pose.size) { index ->
        floatArrayOf(pose[index][0], pose[index][1] + dy, pose[index][2])
    }
}
