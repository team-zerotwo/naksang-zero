package kr.zerotwo.naksang.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
            personX = 0.46f,
            personY = 0.42f,
            mirrorX = false,
            cameraError = null,
            showLiveCamera = false,
            onPreviewReady = {},
            onPreviewGone = {},
            onBack = {},
            onStart = {},
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
