package kr.zerotwo.naksang.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kr.zerotwo.naksang.detect.JointPoint
import kr.zerotwo.naksang.session.FallDelivery
import kr.zerotwo.naksang.ui.screens.CameraPlaceScreen
import kr.zerotwo.naksang.ui.screens.ConnectMode
import kr.zerotwo.naksang.ui.screens.ConnectScreen
import kr.zerotwo.naksang.ui.screens.CountdownScreen
import kr.zerotwo.naksang.ui.screens.SentScreen
import kr.zerotwo.naksang.ui.screens.StandbyScreen
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Static frames for screenshots. Launch with
 * adb shell am start -n kr.zerotwo.naksang/.MainActivity --es preview p3
 */
@Composable
fun DesignPreview(which: String) {
    val clock = remember { previewClock() }
    when (which) {
        "p1" -> ConnectScreen(
            mode = ConnectMode.LINKED,
            inviteCode = "482913",
            address = "서울시 ○○구 ○○로 12",
            detail = "101동 502호",
            entryMethod = "#1234",
            roomNote = "안방",
            busy = false,
            error = null,
            copied = false,
            onSubmit = { _, _, _, _ -> },
            onNext = {},
            onCopied = {},
            onOpenSettings = {},
        )
        "p2" -> CameraPlaceScreen(
            personVisible = true,
            personX = 0.48f,
            personY = 0.46f,
            mirrorX = false,
            cameraError = null,
            showLiveCamera = false,
            onPreviewReady = { _, _ -> },
            onPreviewGone = {},
            onBack = {},
            onStart = {},
            landmarks = previewStandingPose(),
            frameWidth = 480,
            frameHeight = 640,
        )
        "p4" -> CountdownScreen(
            endsAt = clock.now,
            totalMs = 30_000,
            isTest = false,
            secondsOverride = 27,
            onOk = {},
        )
        "p5" -> SentScreen(
            address = "서울시 ○○구 ○○로 12",
            detail = "101동 502호",
            detectedAtEpoch = clock.afternoon,
            delivery = FallDelivery.SENT,
            nowMillis = clock.now,
            onBack = {},
        )
        else -> StandbyScreen(
            batteryPercent = 40,
            charging = false,
            heartbeatOk = true,
            lastHeartbeatAt = clock.now,
            demoOffline = false,
            watchingSince = clock.morning,
            cameraError = null,
            nowMillis = clock.now,
            ticking = false,
            onEditAddress = {},
            onOpenSettings = {},
            onSecretTest = {},
        )
    }
}

/** Standing figure in upright normalized coordinates, for the placement-screen skeleton. */
private fun previewStandingPose(): List<JointPoint> {
    val points = MutableList(33) { JointPoint(0.5f, 0.5f, 0f) }
    fun put(index: Int, x: Float, y: Float) {
        points[index] = JointPoint(x, y, 0.95f)
    }
    put(0, 0.48f, 0.20f)
    put(1, 0.465f, 0.185f)
    put(2, 0.45f, 0.185f)
    put(3, 0.435f, 0.19f)
    put(4, 0.495f, 0.185f)
    put(5, 0.51f, 0.185f)
    put(6, 0.525f, 0.19f)
    put(7, 0.40f, 0.21f)
    put(8, 0.56f, 0.21f)
    put(9, 0.465f, 0.23f)
    put(10, 0.50f, 0.23f)
    put(11, 0.36f, 0.32f)
    put(12, 0.60f, 0.32f)
    put(13, 0.30f, 0.46f)
    put(14, 0.66f, 0.44f)
    put(15, 0.32f, 0.58f)
    put(16, 0.64f, 0.56f)
    put(17, 0.29f, 0.62f)
    put(18, 0.67f, 0.60f)
    put(19, 0.34f, 0.63f)
    put(20, 0.66f, 0.61f)
    put(21, 0.35f, 0.59f)
    put(22, 0.61f, 0.57f)
    put(23, 0.42f, 0.56f)
    put(24, 0.55f, 0.56f)
    put(25, 0.41f, 0.74f)
    put(26, 0.56f, 0.74f)
    put(27, 0.40f, 0.90f)
    put(28, 0.57f, 0.90f)
    put(29, 0.37f, 0.93f)
    put(30, 0.60f, 0.93f)
    put(31, 0.44f, 0.95f)
    put(32, 0.63f, 0.95f)
    return points
}

private data class PreviewClock(val morning: Long, val now: Long, val afternoon: Long)

private fun previewClock(): PreviewClock {
    val zone = ZoneId.systemDefault()
    val morning = ZonedDateTime.now(zone).withHour(7).withMinute(0).withSecond(0).withNano(0)
    val now = morning.plusHours(8).plusMinutes(30)
    val afternoon = morning.plusHours(8).plusMinutes(42)
    return PreviewClock(
        morning = morning.toInstant().toEpochMilli(),
        now = now.toInstant().toEpochMilli(),
        afternoon = afternoon.toInstant().toEpochMilli(),
    )
}
