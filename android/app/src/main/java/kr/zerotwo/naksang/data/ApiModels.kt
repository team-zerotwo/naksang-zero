package kr.zerotwo.naksang.data

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

/** POST /api/devices and PUT /api/devices/me/address. Blank optionals are omitted. */
data class AddressBody(
    @SerializedName("address") val address: String,
    @SerializedName("detail") val detail: String,
    @SerializedName("entryMethod") val entryMethod: String? = null,
    @SerializedName("roomNote") val roomNote: String? = null,
) {
    companion object {
        fun of(address: String, detail: String, entryMethod: String, roomNote: String) = AddressBody(
            address = address.trim(),
            detail = detail.trim(),
            entryMethod = entryMethod.trim().ifBlank { null },
            roomNote = roomNote.trim().ifBlank { null },
        )
    }
}

data class RegisterResponse(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("inviteCode") val inviteCode: String,
    @SerializedName("deviceToken") val deviceToken: String,
)

data class HeartbeatBody(
    @SerializedName("battery") val battery: Int,
    @SerializedName("status") val status: String,
)

data class FallEventBody(
    @SerializedName("detectedAt") val detectedAt: String,
    @SerializedName("outcome") val outcome: String,
    @SerializedName("isTest") val isTest: Boolean? = null,
)

data class FallEventResponse(
    @SerializedName("eventId") val eventId: String,
)

object Outcomes {
    const val TIMEOUT = "timeout"
    const val CANCELLED = "cancelled"
}

object HeartbeatStatus {
    const val WATCHING = "watching"
    const val COUNTDOWN = "countdown"
    const val IDLE = "idle"
}

object DeviceHeader {
    const val NAME = "X-Device-Token"
}

/** inviteCode and ids may arrive as JSON numbers. Keep them as strings. */
class RegisterResponseDeserializer : JsonDeserializer<RegisterResponse> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): RegisterResponse {
        val obj = json.asJsonObject
        val code = jsonString(obj, "inviteCode").filter { it.isDigit() }.ifBlank { jsonString(obj, "inviteCode") }
        val padded = if (code.length in 1..5 && code.all { it.isDigit() }) code.padStart(6, '0') else code
        return RegisterResponse(
            deviceId = jsonString(obj, "deviceId"),
            inviteCode = padded,
            deviceToken = jsonString(obj, "deviceToken"),
        )
    }
}

class FallEventResponseDeserializer : JsonDeserializer<FallEventResponse> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): FallEventResponse {
        return FallEventResponse(eventId = jsonString(json.asJsonObject, "eventId"))
    }
}

internal fun jsonString(obj: JsonObject, name: String): String {
    val el = obj.get(name) ?: return ""
    if (!el.isJsonPrimitive) return el.toString()
    return el.asString
}

data class PendingFall(
    val detectedAt: String,
    val outcome: String,
    val isTest: Boolean?,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
)

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Http(val code: Int, val body: String) : ApiResult<Nothing>()
    data class Network(val message: String) : ApiResult<Nothing>()

    val isOk: Boolean get() = this is Ok
}
