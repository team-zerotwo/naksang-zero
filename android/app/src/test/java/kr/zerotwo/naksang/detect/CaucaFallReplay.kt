package kr.zerotwo.naksang.detect

import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

/**
 * Test-only replay of landmark CSVs through the shipped [FallDetector].
 * Nothing here is on the app classpath. Thresholds in [FallConfig] are not
 * changed; the sweep writes hypothetical counts beside the shipped rule.
 *
 * CSV columns are produced by ml/validation/extract_landmarks.py.
 * `timestamp_ms,source_frame,pose_found,l00_x,l00_y,l00_v,...l32_v`.
 */
internal object CaucaFallReplay {
    val landmarkHeader: List<String> = buildList {
        add("timestamp_ms")
        add("source_frame")
        add("pose_found")
        for (index in 0 until PoseSkeleton.LANDMARK_COUNT) {
            add("l%02d_x".format(index))
            add("l%02d_y".format(index))
            add("l%02d_v".format(index))
        }
    }

    data class ReplayFrame(
        val timestampMs: Long,
        val sourceFrame: Int,
        val poseFound: Boolean,
        val joints: Array<FloatArray>,
    )

    data class VideoMeta(
        val videoId: String,
        val subject: String,
        val activity: String,
        val kind: String,
        val activityType: String,
        val onsetMs: Long?,
        val durationMs: Long?,
        val csvFile: File,
    )

    data class VideoEval(
        val meta: VideoMeta,
        val detected: Boolean,
        val detectedAtMs: Long?,
        val delayMs: Long?,
        val frames: Int,
        val poseFrames: Int,
        val poseFraction: Float,
        val medianTorsoLen: Float?,
        val minAngleDeg: Float?,
        val maxDrop800: Float?,
        val longestGapMs: Long?,
        val edgeFraction: Float?,
        val poseFractionAfterLowest: Float?,
        val tailAfterOnsetMs: Long?,
        val unblockedBy: List<String>,
        val blocker: String?,
    )

    fun writeCsv(file: File, frames: List<ReplayFrame>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { writer ->
            writer.append(landmarkHeader.joinToString(","))
            writer.append('\n')
            for (frame in frames) {
                writer.append(frame.timestampMs.toString())
                writer.append(',')
                writer.append(frame.sourceFrame.toString())
                writer.append(',')
                writer.append(if (frame.poseFound) "1" else "0")
                val joints = frame.joints
                for (index in 0 until PoseSkeleton.LANDMARK_COUNT) {
                    val row = joints.getOrNull(index) ?: floatArrayOf(0f, 0f, 0f)
                    writer.append(',')
                    writer.append(formatFloat(row.getOrElse(0) { 0f }))
                    writer.append(',')
                    writer.append(formatFloat(row.getOrElse(1) { 0f }))
                    writer.append(',')
                    writer.append(formatFloat(row.getOrElse(2) { 0f }))
                }
                writer.append('\n')
            }
        }
    }

    fun readCsv(file: File): List<ReplayFrame> {
        val lines = file.readLines()
        if (lines.isEmpty()) error("empty csv ${file.name}")
        val header = parseCsvLine(lines.first())
        if (header != landmarkHeader) {
            error("unexpected header in ${file.name}")
        }
        val frames = ArrayList<ReplayFrame>(lines.size)
        for (lineIndex in 1 until lines.size) {
            val line = lines[lineIndex]
            if (line.isBlank()) continue
            val cells = parseCsvLine(line)
            if (cells.size != header.size) {
                error("${file.name}:${lineIndex + 1} has ${cells.size} columns, expected ${header.size}")
            }
            val joints = Array(PoseSkeleton.LANDMARK_COUNT) { index ->
                val base = 3 + index * 3
                floatArrayOf(cells[base].toFloat(), cells[base + 1].toFloat(), cells[base + 2].toFloat())
            }
            frames += ReplayFrame(
                timestampMs = cells[0].toLong(),
                sourceFrame = cells[1].toInt(),
                poseFound = cells[2] == "1",
                joints = joints,
            )
        }
        return frames
    }

    fun readManifest(manifest: File, csvDir: File): List<VideoMeta> {
        val rows = readRows(manifest)
        if (rows.isEmpty()) error("empty manifest")
        val header = rows.first()
        fun indexOf(name: String): Int {
            val index = header.indexOf(name)
            if (index < 0) error("manifest is missing $name")
            return index
        }
        val id = indexOf("video_id")
        val subject = indexOf("subject")
        val activity = indexOf("activity")
        val kind = indexOf("kind")
        val activityType = indexOf("activity_type")
        val csvFile = indexOf("csv_file")
        val onset = indexOf("onset_ms")
        val duration = indexOf("duration_ms")
        return rows.drop(1).map { cells ->
            val name = cells[csvFile]
            val file = if (File(name).isAbsolute) File(name) else File(csvDir, name)
            VideoMeta(
                videoId = cells[id],
                subject = cells[subject],
                activity = cells[activity],
                kind = cells[kind],
                activityType = cells[activityType],
                onsetMs = cells[onset].toLongOrNull(),
                durationMs = cells[duration].toLongOrNull(),
                csvFile = file,
            )
        }
    }

    fun evaluateAndWrite(videos: List<VideoMeta>, outDir: File) {
        if (videos.isEmpty()) error("no videos to replay")
        outDir.mkdirs()
        val loaded = videos.map { meta ->
            if (!meta.csvFile.isFile) error("missing landmark csv ${meta.csvFile}")
            meta to readCsv(meta.csvFile)
        }
        val config = FallConfig()
        val evals = loaded.map { (meta, frames) -> evaluate(frames, meta, config) }
        writePerVideo(File(outDir, "per_video.csv"), evals)
        writeSweep(File(outDir, "threshold_sweep.csv"), loaded, config)
        File(outDir, "shipped_config.txt").writeText(config.toString() + "\n")
    }

    fun evaluate(frames: List<ReplayFrame>, meta: VideoMeta, config: FallConfig = FallConfig()): VideoEval {
        val detectedAt = firstFire(frames, config)
        val duration = meta.durationMs ?: frames.lastOrNull()?.timestampMs
        val delay = if (detectedAt != null && meta.onsetMs != null) detectedAt - meta.onsetMs else null
        val tail = if (duration != null && meta.onsetMs != null) duration - meta.onsetMs else null
        val stats = poseStats(frames, config)
        val missedFall = meta.kind == "fall" && detectedAt == null
        val unblocked = if (missedFall && stats.poseFrames >= 3) {
            relaxations(config).mapNotNull { (id, relaxed) ->
                if (firstFire(frames, relaxed) != null) id else null
            }
        } else {
            emptyList()
        }
        val blocker = when {
            !missedFall -> null
            stats.poseFrames < 3 -> "no_pose"
            unblocked.isEmpty() && firstFire(frames, loose(config)) != null -> "combined_thresholds"
            unblocked.isEmpty() -> "no_fall_pattern"
            unblocked == listOf("still_duration") && tail != null && tail < config.stillDurationMs ->
                "clip_shorter_than_stillness"
            else -> unblocked.joinToString("+")
        }
        return VideoEval(
            meta = meta,
            detected = detectedAt != null,
            detectedAtMs = detectedAt,
            delayMs = delay,
            frames = frames.size,
            poseFrames = stats.poseFrames,
            poseFraction = if (frames.isEmpty()) 0f else stats.poseFrames.toFloat() / frames.size,
            medianTorsoLen = stats.medianTorsoLen,
            minAngleDeg = stats.minAngleDeg,
            maxDrop800 = stats.maxDrop,
            longestGapMs = stats.longestGapMs,
            edgeFraction = stats.edgeFraction,
            poseFractionAfterLowest = stats.poseFractionAfterLowest,
            tailAfterOnsetMs = tail,
            unblockedBy = unblocked,
            blocker = blocker,
        )
    }

    fun firstFire(frames: List<ReplayFrame>, config: FallConfig): Long? {
        val detector = FallDetector(config)
        for (frame in frames) {
            if (detector.onJointArray(frame.timestampMs, frame.joints)) return frame.timestampMs
        }
        return null
    }

    private data class PoseStats(
        val poseFrames: Int,
        val medianTorsoLen: Float?,
        val minAngleDeg: Float?,
        val maxDrop: Float?,
        val longestGapMs: Long?,
        val edgeFraction: Float?,
        val poseFractionAfterLowest: Float?,
    )

    private data class RawSample(
        val t: Long,
        val cy: Float,
        val angleDeg: Float,
        val span: Float,
        val edge: Boolean,
    )

    private fun poseStats(frames: List<ReplayFrame>, config: FallConfig): PoseStats {
        val series = ArrayList<RawSample>()
        var previousPose: Long? = null
        var longestGap = 0L
        var poseFrames = 0
        for (frame in frames) {
            if (!frame.poseFound) continue
            val points = frame.joints.toJointPoints()
            val axis = torsoAxis(points) ?: continue
            poseFrames += 1
            val span = shoulderHipSpan(points)
            series += RawSample(
                t = frame.timestampMs,
                cy = axis.cy,
                angleDeg = axis.angleDeg,
                span = span ?: 0f,
                edge = nearEdge(points),
            )
            val previous = previousPose
            if (previous != null) longestGap = max(longestGap, frame.timestampMs - previous)
            previousPose = frame.timestampMs
        }
        if (series.isEmpty()) {
            return PoseStats(0, null, null, null, null, null, null)
        }
        var maxDrop = Float.NEGATIVE_INFINITY
        val window = config.dropWindowMs
        for (sample in series) {
            val ref = series.lastOrNull { it.t <= sample.t - window } ?: continue
            if (sample.t - ref.t <= window + 250) {
                maxDrop = max(maxDrop, sample.cy - ref.cy)
            }
        }
        val lowest = series.maxByOrNull { it.cy } ?: return PoseStats(poseFrames, null, null, null, null, null, null)
        val after = frames.filter { it.timestampMs > lowest.t }
        val afterFraction = if (after.isEmpty()) {
            null
        } else {
            after.count { frame ->
                frame.poseFound && torsoAxis(frame.joints.toJointPoints()) != null
            }.toFloat() / after.size
        }
        return PoseStats(
            poseFrames = poseFrames,
            medianTorsoLen = median(series.map { it.span }),
            minAngleDeg = series.minOf { it.angleDeg },
            maxDrop = if (maxDrop == Float.NEGATIVE_INFINITY) null else maxDrop,
            longestGapMs = if (poseFrames >= 2) longestGap else 0L,
            edgeFraction = series.count { it.edge }.toFloat() / series.size,
            poseFractionAfterLowest = afterFraction,
        )
    }

    /** Shoulder-group to hip-group distance. Descriptive only; the decision uses [torsoAxis]. */
    private fun shoulderHipSpan(landmarks: List<JointPoint>): Float? {
        val shoulders = ArrayList<JointPoint>(2)
        val hips = ArrayList<JointPoint>(2)
        landmarks.getOrNull(PoseJoints.LEFT_SHOULDER)?.takeIf { it.visibility >= DEFAULT_MIN_VISIBILITY }?.let { shoulders += it }
        landmarks.getOrNull(PoseJoints.RIGHT_SHOULDER)?.takeIf { it.visibility >= DEFAULT_MIN_VISIBILITY }?.let { shoulders += it }
        landmarks.getOrNull(PoseJoints.LEFT_HIP)?.takeIf { it.visibility >= DEFAULT_MIN_VISIBILITY }?.let { hips += it }
        landmarks.getOrNull(PoseJoints.RIGHT_HIP)?.takeIf { it.visibility >= DEFAULT_MIN_VISIBILITY }?.let { hips += it }
        if (shoulders.isEmpty() || hips.isEmpty()) return null
        val sx = shoulders.sumOf { it.x.toDouble() }.toFloat() / shoulders.size
        val sy = shoulders.sumOf { it.y.toDouble() }.toFloat() / shoulders.size
        val hx = hips.sumOf { it.x.toDouble() }.toFloat() / hips.size
        val hy = hips.sumOf { it.y.toDouble() }.toFloat() / hips.size
        return hypot((sx - hx).toDouble(), (sy - hy).toDouble()).toFloat()
    }

    private fun nearEdge(landmarks: List<JointPoint>): Boolean {
        val indexes = intArrayOf(
            PoseJoints.LEFT_SHOULDER,
            PoseJoints.RIGHT_SHOULDER,
            PoseJoints.LEFT_HIP,
            PoseJoints.RIGHT_HIP,
        )
        return indexes.any { index ->
            val joint = landmarks.getOrNull(index) ?: return@any false
            joint.visibility >= DEFAULT_MIN_VISIBILITY &&
                (joint.x !in 0.02f..0.98f || joint.y !in 0.02f..0.98f)
        }
    }

    private fun relaxations(base: FallConfig): List<Pair<String, FallConfig>> = listOf(
        "min_drop" to base.copy(minDrop = 0.08f),
        "torso_angle" to base.copy(maxLieAngleDeg = 55f),
        "still_movement" to base.copy(stillMovement = 0.12f),
        "still_duration" to base.copy(stillDurationMs = 400),
        "drop_window" to base.copy(dropWindowMs = 2_000),
        "upright" to base.copy(uprightAngleDeg = 30f),
        "person_lost_grace" to base.copy(personLostGraceMs = 4_000, trackingGapResetMs = 4_000),
    )

    private fun loose(base: FallConfig) = base.copy(
        minDrop = 0.08f,
        maxLieAngleDeg = 55f,
        stillMovement = 0.12f,
        stillDurationMs = 400,
        dropWindowMs = 2_000,
        uprightAngleDeg = 30f,
        personLostGraceMs = 4_000,
        trackingGapResetMs = 4_000,
    )

    private data class SweepRow(
        val slice: String,
        val config: FallConfig,
        val fallDetected: Int,
        val fallTotal: Int,
        val adlFalse: Int,
        val adlTotal: Int,
        val sittingFalse: Int,
        val sittingTotal: Int,
        val kneelingFalse: Int,
        val kneelingTotal: Int,
        val medianDelayMs: Long?,
        val byType: Map<String, Int>,
    )

    private fun writeSweep(
        file: File,
        loaded: List<Pair<VideoMeta, List<ReplayFrame>>>,
        shipped: FallConfig,
    ) {
        val rows = ArrayList<SweepRow>()
        rows += sweepRow("default", shipped, loaded)
        for (minDrop in listOf(0.08f, 0.10f, 0.12f, 0.14f, 0.16f, 0.18f, 0.22f, 0.26f, 0.30f)) {
            rows += sweepRow("minDrop", shipped.copy(minDrop = minDrop), loaded)
        }
        for (angle in listOf(18f, 22f, 28f, 35f, 42f, 50f)) {
            rows += sweepRow("maxLieAngleDeg", shipped.copy(maxLieAngleDeg = angle), loaded)
        }
        for (still in listOf(0.02f, 0.035f, 0.05f, 0.08f, 0.12f)) {
            rows += sweepRow("stillMovement", shipped.copy(stillMovement = still), loaded)
        }
        for (window in listOf(400L, 600L, 800L, 1200L, 1600L, 2000L)) {
            rows += sweepRow("dropWindowMs", shipped.copy(dropWindowMs = window), loaded)
        }
        val drops = listOf(0.10f, 0.12f, 0.14f, 0.16f, 0.18f, 0.22f, 0.26f)
        val angles = listOf(20f, 28f, 36f, 45f)
        val stills = listOf(0.02f, 0.035f, 0.05f, 0.08f)
        val windows = listOf(600L, 800L, 1200L, 1600L)
        for (minDrop in drops) {
            for (angle in angles) {
                for (still in stills) {
                    for (window in windows) {
                        rows += sweepRow(
                            "grid",
                            shipped.copy(
                                minDrop = minDrop,
                                maxLieAngleDeg = angle,
                                stillMovement = still,
                                dropWindowMs = window,
                            ),
                            loaded,
                        )
                    }
                }
            }
        }
        val types = listOf(
            "forward",
            "backward",
            "left",
            "right",
            "from_sitting",
            "walking",
            "hopping",
            "picking_up",
            "sitting",
            "kneeling",
        )
        file.bufferedWriter().use { writer ->
            writer.append(
                "slice,min_drop,max_lie_angle_deg,still_movement,drop_window_ms," +
                    "falls_detected,falls_total,adl_false,adl_total," +
                    "sitting_false,sitting_total,kneeling_false,kneeling_total,median_delay_ms," +
                    types.joinToString(","),
            )
            writer.append('\n')
            for (row in rows) {
                writer.append(row.slice)
                writer.append(',')
                writer.append(formatFloat(row.config.minDrop))
                writer.append(',')
                writer.append(formatFloat(row.config.maxLieAngleDeg))
                writer.append(',')
                writer.append(formatFloat(row.config.stillMovement))
                writer.append(',')
                writer.append(row.config.dropWindowMs.toString())
                writer.append(',')
                writer.append(row.fallDetected.toString())
                writer.append(',')
                writer.append(row.fallTotal.toString())
                writer.append(',')
                writer.append(row.adlFalse.toString())
                writer.append(',')
                writer.append(row.adlTotal.toString())
                writer.append(',')
                writer.append(row.sittingFalse.toString())
                writer.append(',')
                writer.append(row.sittingTotal.toString())
                writer.append(',')
                writer.append(row.kneelingFalse.toString())
                writer.append(',')
                writer.append(row.kneelingTotal.toString())
                writer.append(',')
                writer.append(row.medianDelayMs?.toString() ?: "")
                for (type in types) {
                    writer.append(',')
                    writer.append((row.byType[type] ?: 0).toString())
                }
                writer.append('\n')
            }
        }
    }

    private fun sweepRow(
        slice: String,
        config: FallConfig,
        loaded: List<Pair<VideoMeta, List<ReplayFrame>>>,
    ): SweepRow {
        var fallDetected = 0
        var fallTotal = 0
        var adlFalse = 0
        var adlTotal = 0
        var sittingFalse = 0
        var sittingTotal = 0
        var kneelingFalse = 0
        var kneelingTotal = 0
        val delays = ArrayList<Long>()
        val byType = HashMap<String, Int>()
        for ((meta, frames) in loaded) {
            val firedAt = firstFire(frames, config)
            val fired = firedAt != null
            if (meta.kind == "fall") {
                fallTotal += 1
                if (fired) {
                    fallDetected += 1
                    val onset = meta.onsetMs
                    if (onset != null) delays += firedAt - onset
                }
            } else {
                adlTotal += 1
                if (fired) adlFalse += 1
            }
            if (meta.activityType == "sitting") {
                sittingTotal += 1
                if (fired) sittingFalse += 1
            }
            if (meta.activityType == "kneeling") {
                kneelingTotal += 1
                if (fired) kneelingFalse += 1
            }
            if (fired) byType[meta.activityType] = (byType[meta.activityType] ?: 0) + 1
        }
        return SweepRow(
            slice = slice,
            config = config,
            fallDetected = fallDetected,
            fallTotal = fallTotal,
            adlFalse = adlFalse,
            adlTotal = adlTotal,
            sittingFalse = sittingFalse,
            sittingTotal = sittingTotal,
            kneelingFalse = kneelingFalse,
            kneelingTotal = kneelingTotal,
            medianDelayMs = medianLong(delays),
            byType = byType,
        )
    }

    private fun writePerVideo(file: File, evals: List<VideoEval>) {
        file.bufferedWriter().use { writer ->
            writer.append(
                "video_id,subject,activity,kind,activity_type,onset_ms,duration_ms," +
                    "frames,pose_frames,pose_fraction,detected,detected_at_ms,delay_ms," +
                    "blocker,unblocked_by,median_torso_len,min_angle_deg,max_drop_800," +
                    "longest_gap_ms,edge_fraction,pose_fraction_after_lowest,tail_after_onset_ms",
            )
            writer.append('\n')
            for (row in evals) {
                val cells = listOf(
                    row.meta.videoId,
                    row.meta.subject,
                    row.meta.activity,
                    row.meta.kind,
                    row.meta.activityType,
                    row.meta.onsetMs?.toString() ?: "",
                    row.meta.durationMs?.toString() ?: "",
                    row.frames.toString(),
                    row.poseFrames.toString(),
                    formatFloat(row.poseFraction),
                    if (row.detected) "1" else "0",
                    row.detectedAtMs?.toString() ?: "",
                    row.delayMs?.toString() ?: "",
                    row.blocker ?: "",
                    row.unblockedBy.joinToString("|"),
                    formatFloat(row.medianTorsoLen),
                    formatFloat(row.minAngleDeg),
                    formatFloat(row.maxDrop800),
                    row.longestGapMs?.toString() ?: "",
                    formatFloat(row.edgeFraction),
                    formatFloat(row.poseFractionAfterLowest),
                    row.tailAfterOnsetMs?.toString() ?: "",
                )
                writer.append(cells.joinToString(",") { csvEscape(it) })
                writer.append('\n')
            }
        }
    }

    private fun readRows(file: File): List<List<String>> {
        return file.readLines().filter { it.isNotBlank() }.map { parseCsvLine(it) }
    }

    internal fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            if (quoted) {
                if (char == '"') {
                    if (index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index += 1
                    } else {
                        quoted = false
                    }
                } else {
                    current.append(char)
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    ',' -> {
                        out += current.toString()
                        current.clear()
                    }
                    else -> current.append(char)
                }
            }
            index += 1
        }
        out += current.toString()
        return out
    }

    private fun formatFloat(value: Float?): String {
        if (value == null || value.isNaN()) return ""
        return String.format(Locale.US, "%.5f", value)
    }

    private fun csvEscape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private fun medianLong(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
