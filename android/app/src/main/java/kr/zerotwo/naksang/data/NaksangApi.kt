package kr.zerotwo.naksang.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Phone → server contract. Paths and JSON names are fixed.
 * See the shared 11/3 contract: do not rename fields.
 */
interface NaksangApi {
    @POST("api/devices")
    suspend fun register(@Body body: AddressBody): RegisterResponse

    @PUT("api/devices/me/address")
    suspend fun updateAddress(
        @Header(DeviceHeader.NAME) token: String,
        @Body body: AddressBody,
    ): retrofit2.Response<ResponseBody>

    @POST("api/devices/me/heartbeat")
    suspend fun heartbeat(
        @Header(DeviceHeader.NAME) token: String,
        @Body body: HeartbeatBody,
    ): retrofit2.Response<ResponseBody>

    @POST("api/events/fall")
    suspend fun fall(
        @Header(DeviceHeader.NAME) token: String,
        @Body body: FallEventBody,
    ): FallEventResponse
}

fun contractGson(): Gson = GsonBuilder()
    .registerTypeAdapter(RegisterResponse::class.java, RegisterResponseDeserializer())
    .registerTypeAdapter(FallEventResponse::class.java, FallEventResponseDeserializer())
    .create()

class DynamicBaseUrlInterceptor(
    private val baseUrl: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val parsed = try {
            URI(baseUrl())
        } catch (_: Exception) {
            return chain.proceed(request)
        }
        val scheme = parsed.scheme ?: return chain.proceed(request)
        val host = parsed.host ?: return chain.proceed(request)
        val port = if (parsed.port != -1) parsed.port else if (scheme == "http") 80 else 443
        val rewritten = request.url.newBuilder()
            .scheme(scheme)
            .host(host)
            .port(port)
            .build()
        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}

object ApiFactory {
    fun create(baseUrl: () -> String): NaksangApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(DynamicBaseUrlInterceptor(baseUrl))
            .build()
        return Retrofit.Builder()
            .baseUrl("https://soominn.duckdns.org/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(contractGson()))
            .build()
            .create(NaksangApi::class.java)
    }
}
