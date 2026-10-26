package kr.zerotwo.naksang.session

import kr.zerotwo.naksang.data.PendingFall
import kr.zerotwo.naksang.detect.FallConfig

enum class Phase {
    NEED_CONNECT,
    CONNECTED,
    WATCHING,
    COUNTDOWN,
    SENT,
}

enum class Route {
    Connect,
    Camera,
    Standby,
    Countdown,
    Sent,
    Settings,
    EditAddress,
}

enum class FallDelivery { NONE, SENT, PENDING }

data class AppState(
    val ready: Boolean = false,
    val route: Route = Route.Connect,
    val phase: Phase = Phase.NEED_CONNECT,
    val address: String = "",
    val detail: String = "",
    val entryMethod: String = "",
    val roomNote: String = "",
    val inviteCode: String = "",
    val deviceId: String = "",
    val deviceToken: String = "",
    val serverUrl: String = "https://soominn.duckdns.org",
    val demoOffline: Boolean = false,
    val cameraFacingFront: Boolean = true,
    val analysisFps: Int = 5,
    val fallConfig: FallConfig = FallConfig(),
    val personVisible: Boolean = false,
    val personX: Float = 0.5f,
    val personY: Float = 0.45f,
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    val heartbeatOk: Boolean? = null,
    val lastHeartbeatAt: Long? = null,
    val watchingSince: Long? = null,
    val countdownEndsAt: Long? = null,
    val countdownTotalMs: Long = 30_000,
    val detectedAtEpoch: Long? = null,
    val detectedAtIso: String? = null,
    val testFall: Boolean = false,
    val lastEventId: String? = null,
    val fallDelivery: FallDelivery = FallDelivery.NONE,
    val pendingFalls: List<PendingFall> = emptyList(),
    val errorMessage: String? = null,
    val busy: Boolean = false,
    val cameraError: String? = null,
    val copied: Boolean = false,
    val settingsReturn: Route = Route.Standby,
)
