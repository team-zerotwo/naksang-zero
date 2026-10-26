package kr.zerotwo.naksang.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

class NaksangRepository(
    private val api: NaksangApi,
) {
    suspend fun register(body: AddressBody, demo: Boolean): ApiResult<RegisterResponse> {
        if (demo) {
            return ApiResult.Ok(
                RegisterResponse(
                    deviceId = "demo-${UUID.randomUUID()}",
                    inviteCode = Random.nextInt(0, 1_000_000).toString().padStart(6, '0'),
                    deviceToken = "demo-${UUID.randomUUID()}",
                ),
            )
        }
        return call { api.register(body) }
    }

    suspend fun updateAddress(token: String, body: AddressBody, demo: Boolean): ApiResult<Unit> {
        if (demo) return ApiResult.Ok(Unit)
        return callUnit { api.updateAddress(token, body) }
    }

    suspend fun heartbeat(token: String, battery: Int, status: String, demo: Boolean): ApiResult<Unit> {
        if (demo) return ApiResult.Ok(Unit)
        return callUnit { api.heartbeat(token, HeartbeatBody(battery.coerceIn(0, 100), status)) }
    }

    suspend fun fall(token: String, body: FallEventBody, demo: Boolean): ApiResult<String> {
        if (demo) return ApiResult.Ok("demo-${UUID.randomUUID()}")
        return when (val result = call { api.fall(token, body) }) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.eventId)
            is ApiResult.Http -> result
            is ApiResult.Network -> result
        }
    }

    private suspend fun <T> call(block: suspend () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Ok(block())
        } catch (e: HttpException) {
            ApiResult.Http(e.code(), e.response()?.errorBody()?.string().orEmpty())
        } catch (e: IOException) {
            ApiResult.Network(e.message ?: "network")
        } catch (e: Exception) {
            ApiResult.Network(e.message ?: "error")
        }
    }

    private suspend fun callUnit(block: suspend () -> retrofit2.Response<okhttp3.ResponseBody>): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val response = block()
                if (response.isSuccessful) {
                    ApiResult.Ok(Unit)
                } else {
                    ApiResult.Http(response.code(), response.errorBody()?.string().orEmpty())
                }
            } catch (e: IOException) {
                ApiResult.Network(e.message ?: "network")
            } catch (e: Exception) {
                ApiResult.Network(e.message ?: "error")
            }
        }
}
