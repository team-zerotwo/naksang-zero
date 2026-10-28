package kr.zerotwo.naksang.detect

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One would-trigger row. The countdown is not started. */
data class WouldTriggerEvent(
    val atEpochMs: Long,
    /** Set when the frame came from a video file. Milliseconds from the start of the file. */
    val videoOffsetMs: Long?,
    val signal: DetectSignal,
    val posture: PostureLabel,
    val torsoAngleDeg: Float?,
    val modelScore: Double,
    val modelThreshold: Double,
    val lyingElapsedMs: Long,
    val lyingThresholdMs: Long,
    val poseConfidence: Float?,
    val personDetected: Boolean,
)

data class SignalCounts(
    val rule: Int = 0,
    val viewDirection: Int = 0,
    val model: Int = 0,
    val lyingStill: Int = 0,
    val fastFlatten: Int = 0,
) {
    val total: Int get() = rule + viewDirection + model + lyingStill + fastFlatten

    operator fun plus(signal: DetectSignal): SignalCounts = when (signal) {
        DetectSignal.RULE -> copy(rule = rule + 1)
        DetectSignal.VIEW_DIRECTION -> copy(viewDirection = viewDirection + 1)
        DetectSignal.MODEL -> copy(model = model + 1)
        DetectSignal.LYING_STILL -> copy(lyingStill = lyingStill + 1)
        DetectSignal.FAST_FLATTEN -> copy(fastFlatten = fastFlatten + 1)
    }
}

/**
 * Last [limit] would-trigger rows, oldest first. Safe to call from the
 * camera thread and the video-replay thread.
 */
class WouldTriggerLog(val limit: Int = 100) {
    private val items = ArrayDeque<WouldTriggerEvent>()

    @Synchronized
    fun addAll(events: List<WouldTriggerEvent>) {
        for (event in events) {
            items.addLast(event)
        }
        while (items.size > limit) items.removeFirst()
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    @Synchronized
    fun snapshot(): List<WouldTriggerEvent> = items.toList()

    @Synchronized
    fun counts(): SignalCounts {
        var counts = SignalCounts()
        for (event in items) counts += event.signal
        return counts
    }
}

fun wouldTriggerEvents(
    atEpochMs: Long,
    videoOffsetMs: Long?,
    debug: FallDebugSnapshot,
): List<WouldTriggerEvent> {
    if (debug.wouldFire.isEmpty()) return emptyList()
    return debug.wouldFire.map { signal ->
        WouldTriggerEvent(
            atEpochMs = atEpochMs,
            videoOffsetMs = videoOffsetMs,
            signal = signal,
            posture = debug.posture,
            torsoAngleDeg = debug.torsoAngleDeg,
            modelScore = debug.modelScore,
            modelThreshold = debug.modelThreshold,
            lyingElapsedMs = debug.lyingElapsedMs,
            lyingThresholdMs = debug.lyingThresholdMs,
            poseConfidence = debug.poseConfidence,
            personDetected = debug.personDetected,
        )
    }
}

fun formatEventClock(event: WouldTriggerEvent, zone: ZoneId = ZoneId.systemDefault()): String {
    val offset = event.videoOffsetMs
    if (offset != null) {
        val clamped = offset.coerceAtLeast(0L)
        val totalSec = clamped / 1000
        val minute = totalSec / 60
        val second = totalSec % 60
        val tenth = (clamped % 1000) / 100
        return String.format(Locale.KOREA, "영상 %d:%02d.%d", minute, second, tenth)
    }
    val time = Instant.ofEpochMilli(event.atEpochMs).atZone(zone)
    return String.format(Locale.KOREA, "%02d:%02d:%02d", time.hour, time.minute, time.second)
}

fun formatWouldTriggerCsv(
    events: List<WouldTriggerEvent>,
    counts: SignalCounts,
    zone: ZoneId = ZoneId.of("Asia/Seoul"),
): String {
    val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA).withZone(zone)
    val builder = StringBuilder()
    builder.append("# 낙상제로 모델 테스트\n")
    builder.append(
        "# 규칙 ${counts.rule}, 시선방향 ${counts.viewDirection}, 포즈모델 ${counts.model}, 바닥에누움 ${counts.lyingStill}, 빠른눕힘 ${counts.fastFlatten}\n",
    )
    builder.append(
        "time,signal,posture,torso_angle_deg,model_score,model_threshold,lying_elapsed_s,lying_threshold_s,pose_confidence,person,video_offset_ms\n",
    )
    for (event in events) {
        val time = clock.format(Instant.ofEpochMilli(event.atEpochMs))
        builder.append(time)
        builder.append(',')
        builder.append(event.signal.code())
        builder.append(',')
        builder.append(event.posture.code())
        builder.append(',')
        builder.append(csvNumber(event.torsoAngleDeg?.toDouble(), 1))
        builder.append(',')
        builder.append(csvNumber(event.modelScore, 3))
        builder.append(',')
        builder.append(csvNumber(event.modelThreshold, 3))
        builder.append(',')
        builder.append(csvSeconds(event.lyingElapsedMs))
        builder.append(',')
        builder.append(csvSeconds(event.lyingThresholdMs))
        builder.append(',')
        builder.append(csvNumber(event.poseConfidence?.toDouble(), 3))
        builder.append(',')
        builder.append(if (event.personDetected) "1" else "0")
        builder.append(',')
        builder.append(event.videoOffsetMs?.toString().orEmpty())
        builder.append('\n')
    }
    return builder.toString()
}

private fun csvNumber(value: Double?, digits: Int): String {
    if (value == null) return ""
    return String.format(Locale.US, "%.${digits}f", value)
}

private fun csvSeconds(ms: Long): String = String.format(Locale.US, "%.1f", ms.coerceAtLeast(0L) / 1000.0)

/** Seconds with one decimal, for the live panel. */
fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.1f", ms.coerceAtLeast(0L) / 1000.0)
