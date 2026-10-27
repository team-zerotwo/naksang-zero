package kr.zerotwo.naksang.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kr.zerotwo.naksang.session.AppContainer
import kr.zerotwo.naksang.session.Phase
import kr.zerotwo.naksang.session.Route
import kr.zerotwo.naksang.ui.screens.CameraPlaceScreen
import kr.zerotwo.naksang.ui.screens.ConnectMode
import kr.zerotwo.naksang.ui.screens.ConnectScreen
import kr.zerotwo.naksang.ui.screens.CountdownScreen
import kr.zerotwo.naksang.ui.screens.SentScreen
import kr.zerotwo.naksang.ui.screens.SettingsScreen
import kr.zerotwo.naksang.ui.screens.StandbyScreen
import kr.zerotwo.naksang.ui.theme.NaksangColors

@Composable
fun NaksangRoot(container: AppContainer) {
    val state by container.state.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current

    if (!state.ready) {
        Box(Modifier.fillMaxSize().background(NaksangColors.Bg))
        return
    }

    val route = state.route
    SideEffect {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = route != Route.Camera
        controller.isAppearanceLightNavigationBars = route != Route.Camera
        @Suppress("DEPRECATION")
        window.navigationBarColor = when (route) {
            Route.Countdown -> NaksangColors.WarnBg.toArgb()
            Route.Camera -> NaksangColors.Text.toArgb()
            else -> NaksangColors.Bg.toArgb()
        }
    }

    BackHandler(enabled = route == Route.Settings) { container.closeSettings() }
    BackHandler(enabled = route == Route.EditAddress) { container.closeEditor() }
    BackHandler(enabled = route == Route.Camera) { container.backFromCamera() }
    BackHandler(enabled = route == Route.Countdown) { }
    BackHandler(enabled = route == Route.Sent) { container.backToWatching() }

    var afterCameraGrant by remember { mutableStateOf("camera") }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        container.startWatching()
    }
    fun watchAfterCamera() {
        if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else container.startWatching()
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) return@rememberLauncherForActivityResult
        if (afterCameraGrant == "watch") watchAfterCamera() else container.openCamera()
    }
    fun goToCamera(next: String) {
        afterCameraGrant = next
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else if (next == "watch") {
            watchAfterCamera()
        } else {
            container.openCamera()
        }
    }

    when (route) {
        Route.Connect -> ConnectScreen(
            mode = if (state.phase == Phase.CONNECTED || state.phase == Phase.WATCHING) ConnectMode.LINKED else ConnectMode.FORM,
            inviteCode = state.inviteCode,
            address = state.address,
            detail = state.detail,
            entryMethod = state.entryMethod,
            roomNote = state.roomNote,
            busy = state.busy,
            error = state.errorMessage,
            copied = state.copied,
            onSubmit = container::connect,
            onNext = { goToCamera("camera") },
            onCopied = container::markCopied,
            onOpenSettings = container::openSettings,
        )
        Route.EditAddress -> ConnectScreen(
            mode = ConnectMode.EDIT,
            inviteCode = state.inviteCode,
            address = state.address,
            detail = state.detail,
            entryMethod = state.entryMethod,
            roomNote = state.roomNote,
            busy = state.busy,
            error = state.errorMessage,
            copied = false,
            onSubmit = container::updateAddress,
            onNext = {},
            onCopied = {},
            onOpenSettings = {},
            onBack = container::closeEditor,
        )
        Route.Camera -> CameraPlaceScreen(
            personVisible = state.personVisible,
            personX = state.personX,
            personY = state.personY,
            mirrorX = state.cameraFacingFront,
            cameraError = state.cameraError,
            showLiveCamera = true,
            onPreviewReady = { surface, port -> container.attachPreview(surface, port) },
            onPreviewGone = { container.attachPreview(null) },
            onBack = container::backFromCamera,
            onStart = { goToCamera("watch") },
            landmarks = state.poseLandmarks,
            frameWidth = state.poseFrameWidth,
            frameHeight = state.poseFrameHeight,
            minVisibility = state.fallConfig.minVisibility,
        )
        Route.Standby -> StandbyScreen(
            batteryPercent = state.batteryPercent,
            charging = state.charging,
            heartbeatOk = state.heartbeatOk,
            lastHeartbeatAt = state.lastHeartbeatAt,
            demoOffline = state.demoOffline,
            watchingSince = state.watchingSince,
            cameraError = state.cameraError,
            onEditAddress = container::openEditor,
            onOpenSettings = container::openSettings,
            onSecretTest = container::triggerHiddenTest,
        )
        Route.Countdown -> CountdownScreen(
            endsAt = state.countdownEndsAt ?: System.currentTimeMillis(),
            totalMs = state.countdownTotalMs,
            isTest = state.testFall,
            onOk = container::confirmOk,
        )
        Route.Sent -> SentScreen(
            address = state.address,
            detail = state.detail,
            detectedAtEpoch = state.detectedAtEpoch,
            delivery = state.fallDelivery,
            onBack = container::backToWatching,
        )
        Route.Settings -> SettingsScreen(
            serverUrl = state.serverUrl,
            demoOffline = state.demoOffline,
            frontCamera = state.cameraFacingFront,
            fps = state.analysisFps,
            config = state.fallConfig,
            connected = state.deviceToken.isNotBlank(),
            watching = state.phase == Phase.WATCHING || state.phase == Phase.COUNTDOWN || state.phase == Phase.SENT,
            onSave = container::saveSettings,
            onClear = container::clearConnection,
            onStop = container::stopWatching,
            onBack = container::closeSettings,
        )
    }
}

