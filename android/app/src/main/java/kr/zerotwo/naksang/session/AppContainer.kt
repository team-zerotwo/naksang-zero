package kr.zerotwo.naksang.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.Preview
import androidx.camera.core.ViewPort
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.zerotwo.naksang.BuildConfig
import kr.zerotwo.naksang.MainActivity
import kr.zerotwo.naksang.camera.FallCameraEngine
import kr.zerotwo.naksang.camera.PoseReader
import kr.zerotwo.naksang.camera.VideoFrameDecoder
import kr.zerotwo.naksang.data.AddressBody
import kr.zerotwo.naksang.data.ApiFactory
import kr.zerotwo.naksang.data.ApiResult
import kr.zerotwo.naksang.data.FallEventBody
import kr.zerotwo.naksang.data.HeartbeatStatus
import kr.zerotwo.naksang.data.NaksangRepository
import kr.zerotwo.naksang.data.Outcomes
import kr.zerotwo.naksang.data.PendingFall
import kr.zerotwo.naksang.data.SessionStore
import kr.zerotwo.naksang.detect.FallAnalysis
import kr.zerotwo.naksang.detect.FallConfig
import kr.zerotwo.naksang.detect.FallDebugSnapshot
import kr.zerotwo.naksang.detect.FrameRate
import kr.zerotwo.naksang.detect.SignalCounts
import kr.zerotwo.naksang.detect.WouldTriggerLog
import kr.zerotwo.naksang.detect.formatEventClock
import kr.zerotwo.naksang.detect.formatWouldTriggerCsv
import kr.zerotwo.naksang.detect.liveTriggerLine
import kr.zerotwo.naksang.detect.toJointPoints
import kr.zerotwo.naksang.detect.torsoCenter
import kr.zerotwo.naksang.detect.wouldTriggerEvents
import kr.zerotwo.naksang.service.FallDetectionService
import kr.zerotwo.naksang.util.IsoTime
import kr.zerotwo.naksang.util.ServerUrl
import kr.zerotwo.naksang.util.shareCacheFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AppContainer(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = SessionStore(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var serverBase: String = BuildConfig.SERVER_BASE_URL

    private val repo = NaksangRepository(ApiFactory.create { serverBase })
    private val analysis = FallAnalysis()
    private val testAnalysis = FallAnalysis()
    private val testLog = WouldTriggerLog()
    private val testFps = FrameRate()
    private val camera = FallCameraEngine(app)
    private val poseReader = PoseReader(app)
    private val poseLock = Any()
    private val testLock = Any()
    private val replayBusy = AtomicBoolean(false)
    private val replayGeneration = AtomicInteger(0)

    private val _state = MutableStateFlow(AppState(serverUrl = BuildConfig.SERVER_BASE_URL))
    val state: StateFlow<AppState> = _state
    private val _modelTest = MutableStateFlow(ModelTestUi())
    val modelTest: StateFlow<ModelTestUi> = _modelTest
    private var replayJob: Job? = null

    private var heartbeatJob: Job? = null
    private var countdownJob: Job? = null
    private var service: FallDetectionService? = null
    private var serviceRunning = false
    private val persistMutex = Mutex()

    init {
        camera.fpsProvider = { _state.value.analysisFps }
        camera.onError = { message ->
            mainHandler.post { _state.update { it.copy(cameraError = message) } }
        }
        camera.onFrame = { bitmap, timestamp ->
            val frameWidth = bitmap.width
            val frameHeight = bitmap.height
            val packet = try {
                synchronized(poseLock) {
                    if (replayBusy.get()) {
                        null
                    } else {
                        poseReader.read(bitmap) to poseReader.loadFailed()
                    }
                }
            } finally {
                bitmap.recycle()
            }
            if (packet != null) {
                val (joints, modelFailed) = packet
                mainHandler.post {
                    if (modelFailed) {
                        _state.update { it.copy(cameraError = "포즈 모델을 열지 못했어요") }
                    }
                    if (_state.value.route == Route.ModelTest) {
                        if (!replayBusy.get()) {
                            onTestFrame(
                                timestampMs = timestamp,
                                joints = joints,
                                frameWidth = frameWidth,
                                frameHeight = frameHeight,
                                videoOffsetMs = null,
                                replayIndex = 0,
                                replayTotal = 0,
                                fromReplay = false,
                            )
                        }
                    } else {
                        onPose(timestamp, joints, frameWidth, frameHeight)
                    }
                }
            }
        }
        scope.launch {
            val loaded = store.load()
            serverBase = loaded.serverUrl
            analysis.updateConfig(loaded.fallConfig)
            testAnalysis.updateConfig(loaded.fallConfig)
            val route = routeFor(loaded.phase)
            _state.value = loaded.copy(ready = true, route = route, serverUrl = loaded.serverUrl)
            refreshBattery()
            when (loaded.phase) {
                Phase.WATCHING, Phase.SENT -> ensureService()
                Phase.COUNTDOWN -> {
                    ensureService()
                    val ends = loaded.countdownEndsAt ?: 0L
                    if (ends <= System.currentTimeMillis()) onTimeout() else armCountdown()
                }
                else -> Unit
            }
            if (serviceRunning) startHeartbeat()
        }
    }

    fun connect(address: String, detail: String, entry: String, room: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null) }
        scope.launch {
            val body = AddressBody.of(address, detail, entry, room)
            when (val result = repo.register(body, _state.value.demoOffline)) {
                is ApiResult.Ok -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            phase = Phase.CONNECTED,
                            route = Route.Connect,
                            address = body.address,
                            detail = body.detail,
                            entryMethod = body.entryMethod.orEmpty(),
                            roomNote = body.roomNote.orEmpty(),
                            deviceId = result.value.deviceId,
                            inviteCode = result.value.inviteCode,
                            deviceToken = result.value.deviceToken,
                            errorMessage = null,
                        )
                    }
                    persist()
                }
                is ApiResult.Http -> fail("서버가 연결을 거절했어요 (${result.code}).")
                is ApiResult.Network -> fail("서버에 연결하지 못했어요. 주소를 확인하거나 오프라인 데모를 켜 주세요.")
            }
        }
    }

    fun updateAddress(address: String, detail: String, entry: String, room: String) {
        val token = _state.value.deviceToken
        if (token.isBlank() || _state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null) }
        scope.launch {
            val body = AddressBody.of(address, detail, entry, room)
            when (val result = repo.updateAddress(token, body, _state.value.demoOffline)) {
                is ApiResult.Ok -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            address = body.address,
                            detail = body.detail,
                            entryMethod = body.entryMethod.orEmpty(),
                            roomNote = body.roomNote.orEmpty(),
                            route = Route.Standby,
                            errorMessage = null,
                        )
                    }
                    persist()
                }
                is ApiResult.Http -> fail("주소를 저장하지 못했어요 (${result.code}).")
                is ApiResult.Network -> fail("서버에 연결하지 못했어요. 잠시 뒤 다시 저장해 주세요.")
            }
        }
    }

    fun openCamera() {
        if (!hasCameraPermission()) {
            _state.update { it.copy(cameraError = "카메라 권한이 필요해요") }
            return
        }
        _state.update {
            it.copy(
                route = Route.Camera,
                errorMessage = null,
                cameraError = null,
                personVisible = false,
                poseLandmarks = emptyList(),
            )
        }
        camera.setLens(_state.value.cameraFacingFront)
        camera.start()
    }

    fun backFromCamera() {
        _state.update { it.copy(route = Route.Connect, personVisible = false, poseLandmarks = emptyList()) }
        if (!serviceRunning) camera.stop()
    }

    fun attachPreview(surface: Preview.SurfaceProvider?, viewPort: ViewPort? = null) {
        camera.setPreview(surface, viewPort)
    }

    fun startWatching() {
        if (!hasCameraPermission()) {
            _state.update { it.copy(cameraError = "카메라 권한이 필요해요") }
            return
        }
        val since = _state.value.watchingSince ?: System.currentTimeMillis()
        resetDetectors()
        _state.update {
            it.copy(
                phase = Phase.WATCHING,
                route = Route.Standby,
                watchingSince = since,
                errorMessage = null,
                cameraError = null,
                personVisible = false,
                poseLandmarks = emptyList(),
            )
        }
        persist()
        camera.setLens(_state.value.cameraFacingFront)
        camera.start()
        ensureService()
        refreshBattery()
    }

    fun stopWatching() {
        countdownJob?.cancel()
        _state.update {
            it.copy(
                phase = Phase.CONNECTED,
                route = Route.Connect,
                countdownEndsAt = null,
            )
        }
        persist()
        resetDetectors()
        stopService()
    }

    fun openSettings() {
        _state.update { it.copy(route = Route.Settings, settingsReturn = it.route, errorMessage = null) }
    }

    fun closeSettings() {
        val back = _state.value.settingsReturn
        val route = if (back == Route.Settings) routeFor(_state.value.phase) else back
        _state.update { it.copy(route = route) }
    }

    fun openEditor() {
        _state.update { it.copy(route = Route.EditAddress, errorMessage = null) }
    }

    fun closeEditor() {
        _state.update { it.copy(route = Route.Standby, errorMessage = null, busy = false) }
    }

    fun saveSettings(
        serverRaw: String,
        demo: Boolean,
        front: Boolean,
        fps: Int,
        config: FallConfig,
    ): String? {
        val url = ServerUrl.normalize(serverRaw) ?: return "서버 주소 형식을 확인해 주세요."
        serverBase = url
        analysis.updateConfig(config)
        testAnalysis.updateConfig(config)
        val lensChanged = front != _state.value.cameraFacingFront
        _state.update {
            it.copy(
                serverUrl = url,
                demoOffline = demo,
                cameraFacingFront = front,
                analysisFps = fps.coerceIn(2, 10),
                fallConfig = config,
                errorMessage = null,
            )
        }
        if (lensChanged) camera.setLens(front)
        persist()
        return null
    }

    fun clearConnection() {
        countdownJob?.cancel()
        stopService()
        resetDetectors()
        val keep = _state.value
        _state.value = AppState(
            ready = true,
            route = Route.Connect,
            phase = Phase.NEED_CONNECT,
            serverUrl = keep.serverUrl,
            demoOffline = keep.demoOffline,
            cameraFacingFront = keep.cameraFacingFront,
            analysisFps = keep.analysisFps,
            fallConfig = keep.fallConfig,
        )
        serverBase = keep.serverUrl
        persist()
    }

    fun markCopied() {
        _state.update { it.copy(copied = true) }
        scope.launch {
            delay(2000)
            _state.update { it.copy(copied = false) }
        }
    }

    fun triggerHiddenTest() {
        if (_state.value.phase != Phase.WATCHING) return
        beginCountdown(isTest = true)
    }

    fun confirmOk() {
        if (_state.value.phase != Phase.COUNTDOWN) return
        countdownJob?.cancel()
        val iso = _state.value.detectedAtIso ?: IsoTime.now()
        val test = _state.value.testFall
        resetDetectors()
        _state.update {
            it.copy(
                phase = Phase.WATCHING,
                route = Route.Standby,
                countdownEndsAt = null,
                testFall = false,
            )
        }
        persist()
        service?.syncNotification(Phase.WATCHING)
        scope.launch {
            postFall(iso, Outcomes.CANCELLED, test)
            persist()
        }
    }

    fun backToWatching() {
        resetDetectors()
        _state.update {
            it.copy(
                phase = Phase.WATCHING,
                route = Route.Standby,
                countdownEndsAt = null,
                testFall = false,
                fallDelivery = FallDelivery.NONE,
            )
        }
        persist()
        service?.syncNotification(Phase.WATCHING)
        ensureService()
    }

    fun attachService(running: FallDetectionService) {
        service = running
        serviceRunning = true
        if (hasCameraPermission()) {
            camera.setLens(_state.value.cameraFacingFront)
            camera.start()
        }
        startHeartbeat()
        running.syncNotification(_state.value.phase)
    }

    fun detachService() {
        service = null
        serviceRunning = false
        heartbeatJob?.cancel()
        if (!cameraKeptAlive()) camera.stop()
    }

    fun refreshBattery() {
        val (percent, charging) = readBattery()
        _state.update { it.copy(batteryPercent = percent, charging = charging) }
    }

    private fun onPose(
        timestampMs: Long,
        joints: Array<FloatArray>?,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        val current = _state.value
        val center = joints?.let { torsoCenter(it, current.fallConfig.minVisibility) }
        if (current.route == Route.Camera) {
            if (joints == null || center == null) {
                if (current.personVisible || current.poseLandmarks.isNotEmpty()) {
                    _state.update { it.copy(personVisible = false, poseLandmarks = emptyList()) }
                }
            } else {
                _state.update {
                    it.copy(
                        personVisible = true,
                        personX = center.first,
                        personY = center.second,
                        poseLandmarks = joints.toJointPoints(),
                        poseFrameWidth = frameWidth,
                        poseFrameHeight = frameHeight,
                    )
                }
            }
        } else if (current.personVisible || current.poseLandmarks.isNotEmpty()) {
            _state.update { it.copy(personVisible = false, poseLandmarks = emptyList()) }
        }
        if (current.phase == Phase.WATCHING && current.route != Route.ModelTest) {
            // Rule, pose model, or lying-still. The countdown and the API body stay the same.
            if (analysis.onFrame(timestampMs, joints).suspected) beginCountdown(isTest = false)
        }
    }

    private fun resetDetectors() {
        analysis.reset()
    }

    private fun beginCountdown(isTest: Boolean) {
        if (_state.value.phase == Phase.COUNTDOWN) return
        val now = System.currentTimeMillis()
        val iso = IsoTime.now()
        _state.update {
            it.copy(
                phase = Phase.COUNTDOWN,
                route = Route.Countdown,
                countdownEndsAt = now + it.countdownTotalMs,
                detectedAtEpoch = now,
                detectedAtIso = iso,
                testFall = isTest,
                fallDelivery = FallDelivery.NONE,
                lastEventId = null,
            )
        }
        persist()
        armCountdown()
        service?.syncNotification(Phase.COUNTDOWN)
        service?.wakeScreen()
        bringActivityForward()
    }

    private fun armCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            val ends = _state.value.countdownEndsAt ?: return@launch
            val wait = ends - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if (_state.value.phase == Phase.COUNTDOWN) onTimeout()
        }
    }

    private fun onTimeout() {
        if (_state.value.phase != Phase.COUNTDOWN) return
        countdownJob?.cancel()
        val iso = _state.value.detectedAtIso ?: IsoTime.now()
        val epoch = _state.value.detectedAtEpoch ?: System.currentTimeMillis()
        val test = _state.value.testFall
        _state.update {
            it.copy(
                phase = Phase.SENT,
                route = Route.Sent,
                countdownEndsAt = null,
                detectedAtIso = iso,
                detectedAtEpoch = epoch,
            )
        }
        persist()
        service?.syncNotification(Phase.SENT)
        scope.launch {
            postFall(iso, Outcomes.TIMEOUT, test)
            persist()
        }
    }

    private suspend fun postFall(detectedAt: String, outcome: String, isTest: Boolean) {
        val body = FallEventBody(
            detectedAt = detectedAt,
            outcome = outcome,
            isTest = if (isTest) true else null,
        )
        val token = _state.value.deviceToken
        if (token.isBlank() && !_state.value.demoOffline) {
            enqueue(body)
            _state.update { it.copy(fallDelivery = FallDelivery.PENDING) }
            return
        }
        when (val result = repo.fall(token, body, _state.value.demoOffline)) {
            is ApiResult.Ok -> _state.update {
                it.copy(fallDelivery = FallDelivery.SENT, lastEventId = result.value)
            }
            is ApiResult.Http -> if (result.code in 500..599 || result.code == 408 || result.code == 429) {
                enqueue(body)
                _state.update { it.copy(fallDelivery = FallDelivery.PENDING) }
            } else {
                _state.update { it.copy(fallDelivery = FallDelivery.PENDING, errorMessage = "알림을 저장하지 못했어요 (${result.code}).") }
                enqueue(body)
            }
            is ApiResult.Network -> {
                enqueue(body)
                _state.update { it.copy(fallDelivery = FallDelivery.PENDING) }
            }
        }
    }

    private fun enqueue(body: FallEventBody) {
        val item = PendingFall(
            detectedAt = body.detectedAt,
            outcome = body.outcome,
            isTest = body.isTest,
            nextAttemptAt = System.currentTimeMillis() + 5_000,
        )
        _state.update { state ->
            val next = (state.pendingFalls + item).takeLast(8)
            state.copy(pendingFalls = next)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                sendHeartbeat()
                flushFalls()
                delay(60_000)
            }
        }
    }

    private suspend fun sendHeartbeat() {
        if (_state.value.route == Route.ModelTest) return
        refreshBattery()
        val current = _state.value
        if (current.deviceToken.isBlank() && !current.demoOffline) return
        val status = when (current.phase) {
            Phase.COUNTDOWN -> HeartbeatStatus.COUNTDOWN
            Phase.WATCHING -> HeartbeatStatus.WATCHING
            else -> HeartbeatStatus.IDLE
        }
        val battery = current.batteryPercent.coerceIn(0, 100)
        when (repo.heartbeat(current.deviceToken, battery, status, current.demoOffline)) {
            is ApiResult.Ok -> _state.update {
                it.copy(heartbeatOk = true, lastHeartbeatAt = System.currentTimeMillis())
            }
            else -> _state.update { it.copy(heartbeatOk = false) }
        }
        persist()
    }

    private suspend fun flushFalls() {
        if (_state.value.route == Route.ModelTest) return
        val now = System.currentTimeMillis()
        val current = _state.value
        if (current.pendingFalls.isEmpty()) return
        if (current.deviceToken.isBlank() && !current.demoOffline) return
        val remain = mutableListOf<PendingFall>()
        var sentId: String? = current.lastEventId
        var delivery = current.fallDelivery
        for (item in current.pendingFalls) {
            if (item.nextAttemptAt > now) {
                remain += item
                continue
            }
            val body = FallEventBody(item.detectedAt, item.outcome, item.isTest)
            when (val result = repo.fall(current.deviceToken, body, current.demoOffline)) {
                is ApiResult.Ok -> {
                    if (item.detectedAt == current.detectedAtIso && item.outcome == Outcomes.TIMEOUT) {
                        sentId = result.value
                        delivery = FallDelivery.SENT
                    }
                }
                is ApiResult.Http -> if (result.code in 400..499 && result.code != 408 && result.code != 429) {
                    Log.w(TAG, "drop fall event http ${result.code}")
                } else {
                    remain += item.copy(attempts = item.attempts + 1, nextAttemptAt = now + backoff(item.attempts))
                }
                is ApiResult.Network -> {
                    remain += item.copy(attempts = item.attempts + 1, nextAttemptAt = now + backoff(item.attempts))
                }
            }
        }
        _state.update { it.copy(pendingFalls = remain.takeLast(8), lastEventId = sentId, fallDelivery = delivery) }
        persist()
    }

    private fun backoff(attempts: Int): Long = when (attempts) {
        0 -> 5_000
        1 -> 15_000
        else -> 45_000
    }

    private fun ensureService() {
        if (!hasCameraPermission()) {
            _state.update { it.copy(cameraError = "카메라 권한이 필요해요") }
            return
        }
        val intent = Intent(app, FallDetectionService::class.java)
        ContextCompat.startForegroundService(app, intent)
    }

    private fun stopService() {
        app.stopService(Intent(app, FallDetectionService::class.java))
        if (!cameraKeptAlive()) camera.stop()
    }

    private fun bringActivityForward() {
        val intent = Intent(app, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        app.startActivity(intent)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun readBattery(): Pair<Int, Boolean> {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0) (level * 100) / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    fun openModelTest() {
        val current = _state.value
        if (current.phase == Phase.COUNTDOWN || current.phase == Phase.SENT) return
        if (!hasCameraPermission()) {
            _state.update { it.copy(cameraError = "카메라 권한이 필요해요") }
            return
        }
        stopReplay()
        synchronized(testLock) {
            testAnalysis.updateConfig(current.fallConfig)
            testAnalysis.reset()
            testFps.reset()
        }
        _modelTest.value = ModelTestUi(
            snapshot = FallDebugSnapshot.initial(current.fallConfig),
            events = testLog.snapshot(),
            counts = testLog.counts(),
        )
        _state.update { it.copy(route = Route.ModelTest, cameraError = null, errorMessage = null) }
        camera.setLens(current.cameraFacingFront)
        camera.start()
    }

    fun closeModelTest() {
        stopReplay()
        _state.update { it.copy(route = Route.Settings, personVisible = false, poseLandmarks = emptyList()) }
        if (!cameraKeptAlive()) camera.stop()
    }

    fun clearModelTestLog() {
        testLog.clear()
        _modelTest.update { it.copy(events = emptyList(), counts = SignalCounts(), lastFireLine = null) }
    }

    fun shareModelTestLog(context: Context) {
        val csv = formatWouldTriggerCsv(testLog.snapshot(), testLog.counts())
        val error = shareCacheFile(
            context,
            "naksang-model-test.csv",
            "text/csv",
            csv,
            "테스트 기록 보내기",
        )
        if (error != null) _modelTest.update { it.copy(replayError = error) }
    }

    fun replayModelTestVideo(uri: Uri) {
        if (_state.value.route != Route.ModelTest) return
        val generation = replayGeneration.incrementAndGet()
        replayJob?.cancel()
        replayBusy.set(true)
        synchronized(testLock) { testAnalysis.reset() }
        _modelTest.update {
            it.copy(replayRunning = true, replayError = null, replayIndex = 0, replayTotal = 0)
        }
        replayJob = scope.launch(Dispatchers.Default) {
            val error = try {
                VideoFrameDecoder(app).decode(
                    uri = uri,
                    cancelled = { generation != replayGeneration.get() },
                ) { frame ->
                    if (generation != replayGeneration.get()) {
                        frame.bitmap.recycle()
                        return@decode
                    }
                    val width = frame.bitmap.width
                    val height = frame.bitmap.height
                    val joints = synchronized(poseLock) {
                        val read = poseReader.read(frame.bitmap)
                        frame.bitmap.recycle()
                        read
                    }
                    if (generation != replayGeneration.get()) return@decode
                    onTestFrame(
                        timestampMs = frame.timestampMs,
                        joints = joints,
                        frameWidth = width,
                        frameHeight = height,
                        videoOffsetMs = frame.timestampMs,
                        replayIndex = frame.index,
                        replayTotal = frame.total,
                        fromReplay = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "replay", e)
                "영상을 열지 못했어요"
            }
            if (generation == replayGeneration.get()) {
                _modelTest.update { it.copy(replayRunning = false, replayError = error) }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (generation == replayGeneration.get()) {
                    replayBusy.set(false)
                    synchronized(testLock) { testAnalysis.reset() }
                    _modelTest.update { state -> state.copy(replayRunning = false) }
                }
            }
        }
    }

    fun stopModelReplay() {
        stopReplay()
    }

    private fun stopReplay() {
        replayGeneration.incrementAndGet()
        replayJob?.cancel()
        synchronized(testLock) { testAnalysis.reset() }
        replayBusy.set(false)
        _modelTest.update { it.copy(replayRunning = false) }
    }

    private fun onTestFrame(
        timestampMs: Long,
        joints: Array<FloatArray>?,
        frameWidth: Int,
        frameHeight: Int,
        videoOffsetMs: Long?,
        replayIndex: Int,
        replayTotal: Int,
        fromReplay: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val next = synchronized(testLock) {
            val frame = testAnalysis.onFrame(timestampMs, joints)
            val events = wouldTriggerEvents(now, videoOffsetMs, frame.debug)
            if (events.isNotEmpty()) testLog.addAll(events)
            val previous = _modelTest.value
            val fps = if (fromReplay) previous.fps else testFps.mark(timestampMs)
            val lastFire = if (events.isEmpty()) {
                previous.lastFireLine
            } else {
                val names = events.joinToString(" · ") { it.signal.korean() }
                "최근 발화 · $names · ${formatEventClock(events.first())}"
            }
            previous.copy(
                snapshot = frame.debug,
                landmarks = joints?.toJointPoints().orEmpty(),
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                fps = fps,
                events = testLog.snapshot(),
                counts = testLog.counts(),
                replayRunning = fromReplay,
                replayIndex = if (fromReplay) replayIndex else previous.replayIndex,
                replayTotal = if (fromReplay) replayTotal else previous.replayTotal,
                liveLine = liveTriggerLine(frame.debug.wouldFire),
                lastFireLine = lastFire,
            )
        }
        _modelTest.value = next
    }

    private fun cameraKeptAlive(): Boolean {
        val route = _state.value.route
        return serviceRunning || route == Route.Camera || route == Route.ModelTest
    }

    private fun fail(message: String) {
        _state.update { it.copy(busy = false, errorMessage = message) }
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            persistMutex.withLock { store.save(_state.value) }
        }
    }

    private fun routeFor(phase: Phase): Route = when (phase) {
        Phase.NEED_CONNECT, Phase.CONNECTED -> Route.Connect
        Phase.WATCHING -> Route.Standby
        Phase.COUNTDOWN -> Route.Countdown
        Phase.SENT -> Route.Sent
    }

    companion object {
        private const val TAG = "Naksang"
    }
}
