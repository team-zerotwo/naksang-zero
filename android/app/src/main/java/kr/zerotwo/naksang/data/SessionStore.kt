package kr.zerotwo.naksang.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kr.zerotwo.naksang.BuildConfig
import kr.zerotwo.naksang.detect.FallConfig
import kr.zerotwo.naksang.session.AppState
import kr.zerotwo.naksang.session.Phase

private val Context.sessionDataStore by preferencesDataStore(name = "naksang_session")

class SessionStore(private val context: Context) {
    private val gson = Gson()
    private val queueType = object : TypeToken<List<PendingFall>>() {}.type

    suspend fun load(): AppState {
        val prefs = context.sessionDataStore.data.first()
        val config = FallConfig(
            dropWindowMs = prefs.longOr(Keys.dropWindow, FallConfig().dropWindowMs),
            minDrop = prefs.floatOr(Keys.minDrop, FallConfig().minDrop),
            maxLieAngleDeg = prefs.floatOr(Keys.maxLie, FallConfig().maxLieAngleDeg),
            lieAngleReleaseDeg = prefs.floatOr(Keys.lieRelease, FallConfig().lieAngleReleaseDeg),
            uprightAngleDeg = prefs.floatOr(Keys.upright, FallConfig().uprightAngleDeg),
            stillDurationMs = prefs.longOr(Keys.stillMs, FallConfig().stillDurationMs),
            stillMovement = prefs.floatOr(Keys.stillMove, FallConfig().stillMovement),
        )
        val phase = prefs[Keys.phase]?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: Phase.NEED_CONNECT
        return AppState(
            phase = phase,
            address = prefs[Keys.address].orEmpty(),
            detail = prefs[Keys.detail].orEmpty(),
            entryMethod = prefs[Keys.entry].orEmpty(),
            roomNote = prefs[Keys.room].orEmpty(),
            inviteCode = prefs[Keys.invite].orEmpty(),
            deviceId = prefs[Keys.deviceId].orEmpty(),
            deviceToken = prefs[Keys.token].orEmpty(),
            serverUrl = prefs[Keys.server] ?: BuildConfig.SERVER_BASE_URL,
            demoOffline = prefs[Keys.demo] ?: BuildConfig.DEMO_OFFLINE,
            cameraFacingFront = prefs[Keys.front] ?: true,
            analysisFps = prefs[Keys.fps] ?: 5,
            fallConfig = config,
            heartbeatOk = prefs[Keys.heartbeatOk],
            lastHeartbeatAt = prefs[Keys.heartbeatAt]?.takeIf { it > 0L },
            watchingSince = prefs[Keys.watchingSince]?.takeIf { it > 0L },
            countdownEndsAt = prefs[Keys.countdownEnds]?.takeIf { it > 0L },
            detectedAtEpoch = prefs[Keys.detectedEpoch]?.takeIf { it > 0L },
            detectedAtIso = prefs[Keys.detectedIso],
            testFall = prefs[Keys.testFall] ?: false,
            lastEventId = prefs[Keys.eventId],
            fallDelivery = when (prefs[Keys.delivery]) {
                "sent" -> kr.zerotwo.naksang.session.FallDelivery.SENT
                "pending" -> kr.zerotwo.naksang.session.FallDelivery.PENDING
                else -> kr.zerotwo.naksang.session.FallDelivery.NONE
            },
            pendingFalls = loadQueue(prefs),
        )
    }

    suspend fun save(state: AppState) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.phase] = state.phase.name
            prefs[Keys.address] = state.address
            prefs[Keys.detail] = state.detail
            prefs[Keys.entry] = state.entryMethod
            prefs[Keys.room] = state.roomNote
            prefs[Keys.invite] = state.inviteCode
            prefs[Keys.deviceId] = state.deviceId
            prefs[Keys.token] = state.deviceToken
            prefs[Keys.server] = state.serverUrl
            prefs[Keys.demo] = state.demoOffline
            prefs[Keys.front] = state.cameraFacingFront
            prefs[Keys.fps] = state.analysisFps
            prefs[Keys.dropWindow] = state.fallConfig.dropWindowMs
            prefs[Keys.minDrop] = state.fallConfig.minDrop
            prefs[Keys.maxLie] = state.fallConfig.maxLieAngleDeg
            prefs[Keys.lieRelease] = state.fallConfig.lieAngleReleaseDeg
            prefs[Keys.upright] = state.fallConfig.uprightAngleDeg
            prefs[Keys.stillMs] = state.fallConfig.stillDurationMs
            prefs[Keys.stillMove] = state.fallConfig.stillMovement
            prefs[Keys.heartbeatAt] = state.lastHeartbeatAt ?: 0L
            prefs[Keys.watchingSince] = state.watchingSince ?: 0L
            prefs[Keys.countdownEnds] = state.countdownEndsAt ?: 0L
            prefs[Keys.detectedEpoch] = state.detectedAtEpoch ?: 0L
            prefs[Keys.detectedIso] = state.detectedAtIso.orEmpty()
            prefs[Keys.testFall] = state.testFall
            prefs[Keys.eventId] = state.lastEventId.orEmpty()
            prefs[Keys.delivery] = state.fallDelivery.name.lowercase()
            prefs[Keys.queue] = gson.toJson(state.pendingFalls)
            val ok = state.heartbeatOk
            if (ok == null) prefs.remove(Keys.heartbeatOk) else prefs[Keys.heartbeatOk] = ok
        }
    }

    private fun loadQueue(prefs: Preferences): List<PendingFall> {
        val raw = prefs[Keys.queue].orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { gson.fromJson<List<PendingFall>>(raw, queueType) }.getOrNull().orEmpty()
    }

    private fun Preferences.longOr(key: Preferences.Key<Long>, default: Long) = this[key] ?: default
    private fun Preferences.floatOr(key: Preferences.Key<Float>, default: Float) = this[key] ?: default

    private object Keys {
        val phase = stringPreferencesKey("phase")
        val address = stringPreferencesKey("address")
        val detail = stringPreferencesKey("detail")
        val entry = stringPreferencesKey("entry")
        val room = stringPreferencesKey("room")
        val invite = stringPreferencesKey("invite")
        val deviceId = stringPreferencesKey("device_id")
        val token = stringPreferencesKey("device_token")
        val server = stringPreferencesKey("server_url")
        val demo = booleanPreferencesKey("demo_offline")
        val front = booleanPreferencesKey("camera_front")
        val fps = intPreferencesKey("analysis_fps")
        val dropWindow = longPreferencesKey("drop_window")
        val minDrop = floatPreferencesKey("min_drop")
        val maxLie = floatPreferencesKey("max_lie")
        val lieRelease = floatPreferencesKey("lie_release")
        val upright = floatPreferencesKey("upright")
        val stillMs = longPreferencesKey("still_ms")
        val stillMove = floatPreferencesKey("still_move")
        val heartbeatOk = booleanPreferencesKey("heartbeat_ok")
        val heartbeatAt = longPreferencesKey("heartbeat_at")
        val watchingSince = longPreferencesKey("watching_since")
        val countdownEnds = longPreferencesKey("countdown_ends")
        val detectedEpoch = longPreferencesKey("detected_epoch")
        val detectedIso = stringPreferencesKey("detected_iso")
        val testFall = booleanPreferencesKey("test_fall")
        val eventId = stringPreferencesKey("event_id")
        val delivery = stringPreferencesKey("delivery")
        val queue = stringPreferencesKey("pending_falls")
    }
}
