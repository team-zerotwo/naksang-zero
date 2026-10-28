package kr.zerotwo.naksang.session

import kr.zerotwo.naksang.detect.FallDebugSnapshot
import kr.zerotwo.naksang.detect.JointPoint
import kr.zerotwo.naksang.detect.SignalCounts
import kr.zerotwo.naksang.detect.WouldTriggerEvent

/** In-memory model-test panel. Not persisted and never sent. */
data class ModelTestUi(
    val snapshot: FallDebugSnapshot = FallDebugSnapshot.initial(),
    val landmarks: List<JointPoint> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val fps: Float = 0f,
    val events: List<WouldTriggerEvent> = emptyList(),
    val counts: SignalCounts = SignalCounts(),
    val replayRunning: Boolean = false,
    val replayIndex: Int = 0,
    val replayTotal: Int = 0,
    val replayError: String? = null,
    val liveLine: String = "이번 프레임에는 발화할 조건이 없어요",
    val lastFireLine: String? = null,
    /** Snapped phone quadrant: 0, 90, 180, or 270. */
    val phoneOrientation: Int = 0,
)
