package kr.zerotwo.naksang.detect

import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

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
        val dataset: String = "",
        val view: String = "",
        val camera: String = "",
        val direction: String = "",
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
        val minTorsoLenRatio: Float?,
        val minBodyHeightRatio: Float?,
        val maxHeadDrop: Float?,
        val maxHipDrop: Float?,
        val maxDrop1600: Float?,
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
        fun optional(name: String): Int = header.indexOf(name)
        val dataset = optional("dataset")
        val view = optional("view")
        val camera = optional("camera")
        val direction = optional("direction")
        return rows.drop(1).map { cells ->
            val name = cells[csvFile]
            val file = if (File(name).isAbsolute) File(name) else File(csvDir, name)
            fun cell(index: Int): String = if (index >= 0 && index < cells.size) cells[index] else ""
            VideoMeta(
                videoId = cells[id],
                subject = cells[subject],
                activity = cells[activity],
                kind = cells[kind],
                activityType = cells[activityType],
                onsetMs = cells[onset].toLongOrNull(),
                durationMs = cells[duration].toLongOrNull(),
                csvFile = file,
                dataset = cell(dataset),
                view = cell(view),
                camera = cell(camera),
                direction = cell(direction),
            )
        }
    }

    /** Rule shipped before the view-robust signals. Kept so a replay can compare. */
    fun legacyFallConfig(): FallConfig = FallConfig(
        dropWindowMs = 800,
        minDrop = 0.18f,
        maxLieAngleDeg = 28f,
        lieAngleReleaseDeg = 45f,
        uprightAngleDeg = 58f,
        stillDurationMs = 2_000,
        stillMovement = 0.035f,
        maxTorsoLenRatio = 0f,
        maxBodyHeightRatio = 0f,
        minDropInTorso = 0f,
        minLimbDrop = 0f,
        steepMinDrop = 0f,
    )

    fun evaluateAndWrite(videos: List<VideoMeta>, outDir: File) {
        if (videos.isEmpty()) error("no videos to replay")
        outDir.mkdirs()
        val loaded = videos.map { meta ->
            if (!meta.csvFile.isFile) error("missing landmark csv ${meta.csvFile}")
            meta to readCsv(meta.csvFile)
        }
        val config = FallConfig()
        val previous = legacyFallConfig()
        val evals = loaded.map { (meta, frames) -> evaluate(frames, meta, config) }
        val evalsPrevious = loaded.map { (meta, frames) -> evaluate(frames, meta, previous) }
        writePerVideo(File(outDir, "per_video.csv"), evals)
        writePerVideo(File(outDir, "per_video_previous.csv"), evalsPrevious)
        writeSignals(File(outDir, "per_video_signals.csv"), loaded, config)
        writeSweep(File(outDir, "threshold_sweep.csv"), loaded, config, previous)
        File(outDir, "shipped_config.txt").writeText(config.toString() + "\n")
        File(outDir, "previous_config.txt").writeText(previous.toString() + "\n")
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
            minTorsoLenRatio = stats.minTorsoLenRatio,
            minBodyHeightRatio = stats.minBodyHeightRatio,
            maxHeadDrop = stats.maxHeadDrop,
            maxHipDrop = stats.maxHipDrop,
            maxDrop1600 = stats.maxDrop1600,
        )
    }

    data class SignalRow(
        val videoId: String,
        val kind: String,
        val activityType: String,
        val dataset: String,
        val view: String,
        val onsetMs: Long?,
        val durationMs: Long?,
        val ruleAt: Long?,
        val modelAt: Long?,
        val lyingAt: Long?,
        val combinedAt: Long?,
        val source: String,
        val sawLying: Boolean,
        val sawFastDescent: Boolean,
        val maxStillLyingMs: Long,
    )

    /**
     * Rule, pose model, and lying-still on one clip. The earliest time wins.
     * A tie goes to the rule, then the model.
     */
    fun signals(frames: List<ReplayFrame>, meta: VideoMeta, config: FallConfig = FallConfig()): SignalRow {
        val rule = FallDetector(config)
        val model = PoseFallScorer(enabled = config.modelEnabled)
        val lying = LyingStillDetector(config)
        var ruleAt: Long? = null
        var modelAt: Long? = null
        var lyingAt: Long? = null
        for (frame in frames) {
            val joints = if (frame.poseFound) frame.joints else null
            if (ruleAt == null && joints != null && rule.onJointArray(frame.timestampMs, joints)) {
                ruleAt = frame.timestampMs
            }
            if (modelAt == null && model.onFrame(frame.timestampMs, joints)) {
                modelAt = frame.timestampMs
            }
            if (lyingAt == null && lying.onJointArray(frame.timestampMs, joints)) {
                lyingAt = frame.timestampMs
            }
        }
        val combined = listOfNotNull(ruleAt, modelAt, lyingAt).minOrNull()
        val source = when {
            combined == null -> ""
            ruleAt != null && ruleAt == combined -> "rule"
            modelAt != null && modelAt == combined -> "model"
            else -> "lying"
        }
        return SignalRow(
            videoId = meta.videoId,
            kind = meta.kind,
            activityType = meta.activityType,
            dataset = meta.dataset,
            view = meta.view,
            onsetMs = meta.onsetMs,
            durationMs = meta.durationMs,
            ruleAt = ruleAt,
            modelAt = modelAt,
            lyingAt = lyingAt,
            combinedAt = combined,
            source = source,
            sawLying = lying.sawLying,
            sawFastDescent = lying.sawFastDescent,
            maxStillLyingMs = lying.maxStillLyingMs,
        )
    }

    private fun writeSignals(file: File, loaded: List<Pair<VideoMeta, List<ReplayFrame>>>, config: FallConfig) {
        file.bufferedWriter().use { writer ->
            writer.append(
                "video_id,kind,activity_type,dataset,view,onset_ms,duration_ms," +
                    "rule_at_ms,model_at_ms,lying_at_ms,combined_at_ms,source,saw_lying,saw_fast_descent,max_still_lying_ms,delay_ms",
            )
            writer.append('\n')
            for ((meta, frames) in loaded) {
                val row = signals(frames, meta, config)
                val delay = if (row.combinedAt != null && row.onsetMs != null) row.combinedAt - row.onsetMs else null
                writer.append(row.videoId)
                writer.append(',')
                writer.append(row.kind)
                writer.append(',')
                writer.append(row.activityType)
                writer.append(',')
                writer.append(row.dataset)
                writer.append(',')
                writer.append(row.view)
                writer.append(',')
                writer.append(row.onsetMs?.toString() ?: "")
                writer.append(',')
                writer.append(row.durationMs?.toString() ?: "")
                writer.append(',')
                writer.append(row.ruleAt?.toString() ?: "")
                writer.append(',')
                writer.append(row.modelAt?.toString() ?: "")
                writer.append(',')
                writer.append(row.lyingAt?.toString() ?: "")
                writer.append(',')
                writer.append(row.combinedAt?.toString() ?: "")
                writer.append(',')
                writer.append(row.source)
                writer.append(',')
                writer.append(if (row.sawLying) "1" else "0")
                writer.append(',')
                writer.append(if (row.sawFastDescent) "1" else "0")
                writer.append(',')
                writer.append(row.maxStillLyingMs.toString())
                writer.append(',')
                writer.append(delay?.toString() ?: "")
                writer.append('\n')
            }
        }
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
        val minTorsoLenRatio: Float?,
        val minBodyHeightRatio: Float?,
        val maxHeadDrop: Float?,
        val maxHipDrop: Float?,
        val maxDrop1600: Float?,
    )

    private data class RawSample(
        val t: Long,
        val pose: PoseMeasure,
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
            val pose = poseMeasure(points) ?: continue
            poseFrames += 1
            series += RawSample(
                t = frame.timestampMs,
                pose = pose,
                edge = nearEdge(points),
            )
            val previous = previousPose
            if (previous != null) longestGap = max(longestGap, frame.timestampMs - previous)
            previousPose = frame.timestampMs
        }
        if (series.isEmpty()) {
            return PoseStats(0, null, null, null, null, null, null, null, null, null, null, null)
        }
        var maxDrop = Float.NEGATIVE_INFINITY
        var maxDrop1600 = Float.NEGATIVE_INFINITY
        var minLenRatio = Float.POSITIVE_INFINITY
        var minHeightRatio = Float.POSITIVE_INFINITY
        var maxHeadDrop = Float.NEGATIVE_INFINITY
        var maxHipDrop = Float.NEGATIVE_INFINITY
        val window = config.dropWindowMs
        for (sample in series) {
            maxDrop = max(maxDrop, windowDrop(series, sample, window))
            maxDrop1600 = max(maxDrop1600, windowDrop(series, sample, 1_600L))
            val ref = series.lastOrNull { it.t <= sample.t - 1_600L } ?: continue
            if (sample.t - ref.t > 1_600L + 250) continue
            if (ref.pose.angleDeg < 58f || ref.pose.torsoLen < 0.04f) continue
            minLenRatio = min(minLenRatio, sample.pose.torsoLen / ref.pose.torsoLen)
            val height = bodyHeightRatio(ref.pose, sample.pose)
            if (height != null) minHeightRatio = min(minHeightRatio, height)
            val nose = sample.pose.noseY
            val refNose = ref.pose.noseY
            if (nose != null && refNose != null) maxHeadDrop = max(maxHeadDrop, nose - refNose)
            maxHipDrop = max(maxHipDrop, sample.pose.hipY - ref.pose.hipY)
        }
        val lowest = series.maxByOrNull { it.pose.cy } ?: return PoseStats(poseFrames, null, null, null, null, null, null, null, null, null, null, null)
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
            medianTorsoLen = median(series.map { it.pose.torsoLen }),
            minAngleDeg = series.minOf { it.pose.angleDeg },
            maxDrop = finiteOrNull(maxDrop),
            longestGapMs = if (poseFrames >= 2) longestGap else 0L,
            edgeFraction = series.count { it.edge }.toFloat() / series.size,
            poseFractionAfterLowest = afterFraction,
            minTorsoLenRatio = finiteOrNull(minLenRatio, positive = true),
            minBodyHeightRatio = finiteOrNull(minHeightRatio, positive = true),
            maxHeadDrop = finiteOrNull(maxHeadDrop),
            maxHipDrop = finiteOrNull(maxHipDrop),
            maxDrop1600 = finiteOrNull(maxDrop1600),
        )
    }

    private fun windowDrop(series: List<RawSample>, sample: RawSample, window: Long): Float {
        val ref = series.lastOrNull { it.t <= sample.t - window } ?: return Float.NEGATIVE_INFINITY
        if (sample.t - ref.t > window + 250) return Float.NEGATIVE_INFINITY
        return sample.pose.cy - ref.pose.cy
    }

    private fun finiteOrNull(value: Float, positive: Boolean = false): Float? {
        if (positive) {
            if (value == Float.POSITIVE_INFINITY) return null
        } else if (value == Float.NEGATIVE_INFINITY) {
            return null
        }
        if (value.isNaN()) return null
        return value
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
        "torso_len" to base.copy(maxTorsoLenRatio = 0.80f),
        "body_height" to base.copy(maxBodyHeightRatio = 0.70f),
        "drop_in_torso" to base.copy(minDropInTorso = 0.50f),
        "limb_drop" to base.copy(minLimbDrop = 0.06f),
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
        maxTorsoLenRatio = 0.80f,
        maxBodyHeightRatio = 0.70f,
        minDropInTorso = 0.50f,
        minLimbDrop = 0.06f,
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
        val fallsByView: String,
        val adlByView: String,
    )

    private fun writeSweep(
        file: File,
        loaded: List<Pair<VideoMeta, List<ReplayFrame>>>,
        shipped: FallConfig,
        previous: FallConfig,
    ) {
        val rows = ArrayList<SweepRow>()
        rows += sweepRow("previous", previous, loaded)
        rows += sweepRow("default", shipped, loaded)
        for (minDrop in listOf(0.08f, 0.10f, 0.12f, 0.14f, 0.18f, 0.22f, 0.30f)) {
            rows += sweepRow("minDrop", shipped.copy(minDrop = minDrop), loaded)
        }
        for (angle in listOf(28f, 35f, 45f, 55f)) {
            rows += sweepRow("maxLieAngleDeg", shipped.copy(maxLieAngleDeg = angle, lieAngleReleaseDeg = angle + 17f), loaded)
        }
        for (still in listOf(0.035f, 0.050f, 0.080f)) {
            rows += sweepRow("stillMovement", shipped.copy(stillMovement = still), loaded)
        }
        for (window in listOf(800L, 1200L, 1600L, 2000L)) {
            rows += sweepRow("dropWindowMs", shipped.copy(dropWindowMs = window), loaded)
        }
        for (ratio in listOf(0f, 0.50f, 0.60f, 0.70f, 0.80f)) {
            rows += sweepRow("maxTorsoLenRatio", shipped.copy(maxTorsoLenRatio = ratio), loaded)
        }
        for (ratio in listOf(0f, 0.40f, 0.45f, 0.55f, 0.65f)) {
            rows += sweepRow("maxBodyHeightRatio", shipped.copy(maxBodyHeightRatio = ratio), loaded)
        }
        for (drop in listOf(0f, 0.70f, 0.90f, 1.20f)) {
            rows += sweepRow("minDropInTorso", shipped.copy(minDropInTorso = drop), loaded)
        }
        for (drop in listOf(0f, 0.08f, 0.12f, 0.18f)) {
            rows += sweepRow("minLimbDrop", shipped.copy(minLimbDrop = drop), loaded)
        }
        for (drop in listOf(0f, 0.18f)) {
            rows += sweepRow("steepMinDrop", shipped.copy(steepMinDrop = drop), loaded)
        }
        val drops = listOf(0.10f, 0.14f, 0.18f)
        val angles = listOf(28f, 40f, 50f)
        val windows = listOf(800L, 1_600L)
        val lengths = listOf(0f, 0.55f, 0.70f)
        val heights = listOf(0f, 0.45f, 0.60f)
        val norms = listOf(0f, 0.90f)
        val limbs = listOf(0f, 0.12f)
        val stills = listOf(0.035f, 0.050f)
        for (minDrop in drops) {
            for (angle in angles) {
                for (window in windows) {
                    for (length in lengths) {
                        for (height in heights) {
                            for (norm in norms) {
                                for (limb in limbs) {
                                    for (still in stills) {
                                        rows += sweepRow(
                                            "grid",
                                            shipped.copy(
                                                minDrop = minDrop,
                                                maxLieAngleDeg = angle,
                                                lieAngleReleaseDeg = angle + 17f,
                                                dropWindowMs = window,
                                                maxTorsoLenRatio = length,
                                                maxBodyHeightRatio = height,
                                                minDropInTorso = norm,
                                                minLimbDrop = limb,
                                                stillMovement = still,
                                            ),
                                            loaded,
                                        )
                                    }
                                }
                            }
                        }
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
                    "max_torso_len_ratio,max_body_height_ratio,min_drop_in_torso,min_limb_drop," +
                    "steep_min_drop,steep_min_angle_deg," +
                    "falls_detected,falls_total,adl_false,adl_total," +
                    "sitting_false,sitting_total,kneeling_false,kneeling_total,median_delay_ms," +
                    "falls_by_view,adl_by_view," +
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
                writer.append(formatFloat(row.config.maxTorsoLenRatio))
                writer.append(',')
                writer.append(formatFloat(row.config.maxBodyHeightRatio))
                writer.append(',')
                writer.append(formatFloat(row.config.minDropInTorso))
                writer.append(',')
                writer.append(formatFloat(row.config.minLimbDrop))
                writer.append(',')
                writer.append(formatFloat(row.config.steepMinDrop))
                writer.append(',')
                writer.append(formatFloat(row.config.steepMinAngleDeg))
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
                writer.append(',')
                writer.append(row.fallsByView)
                writer.append(',')
                writer.append(row.adlByView)
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
        val fallViewHits = HashMap<String, Int>()
        val fallViewTotal = HashMap<String, Int>()
        val adlViewHits = HashMap<String, Int>()
        val adlViewTotal = HashMap<String, Int>()
        for ((meta, frames) in loaded) {
            val firedAt = firstFire(frames, config)
            val fired = firedAt != null
            val view = meta.view.ifBlank { "unspecified" }
            if (meta.kind == "fall") {
                fallTotal += 1
                fallViewTotal[view] = (fallViewTotal[view] ?: 0) + 1
                if (fired) {
                    fallDetected += 1
                    fallViewHits[view] = (fallViewHits[view] ?: 0) + 1
                    val onset = meta.onsetMs
                    if (onset != null) delays += firedAt - onset
                }
            } else {
                adlTotal += 1
                adlViewTotal[view] = (adlViewTotal[view] ?: 0) + 1
                if (fired) {
                    adlFalse += 1
                    adlViewHits[view] = (adlViewHits[view] ?: 0) + 1
                }
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
            fallsByView = viewTally(fallViewHits, fallViewTotal),
            adlByView = viewTally(adlViewHits, adlViewTotal),
        )
    }

    private fun viewTally(hits: Map<String, Int>, total: Map<String, Int>): String {
        if (total.isEmpty()) return ""
        return total.keys.sorted().joinToString("|") { view ->
            "${view}:${hits[view] ?: 0}/${total[view] ?: 0}"
        }
    }

    private fun writePerVideo(file: File, evals: List<VideoEval>) {
        file.bufferedWriter().use { writer ->
            writer.append(
                "video_id,subject,activity,kind,activity_type,onset_ms,duration_ms," +
                    "frames,pose_frames,pose_fraction,detected,detected_at_ms,delay_ms," +
                    "blocker,unblocked_by,median_torso_len,min_angle_deg,max_drop_800," +
                    "longest_gap_ms,edge_fraction,pose_fraction_after_lowest,tail_after_onset_ms," +
                    "dataset,view,camera,direction,min_torso_len_ratio,min_body_height_ratio," +
                    "max_head_drop,max_hip_drop,max_drop_1600",
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
                    row.meta.dataset,
                    row.meta.view,
                    row.meta.camera,
                    row.meta.direction,
                    formatFloat(row.minTorsoLenRatio),
                    formatFloat(row.minBodyHeightRatio),
                    formatFloat(row.maxHeadDrop),
                    formatFloat(row.maxHipDrop),
                    formatFloat(row.maxDrop1600),
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
