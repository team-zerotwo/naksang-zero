package kr.zerotwo.naksang.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kr.zerotwo.naksang.util.ServerUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiContractTest {
    private val server = MockWebServer()
    private lateinit var api: NaksangApi

    @Before
    fun setUp() {
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        api = ApiFactory.create { base }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun registerPostsAddressAndReadsNumericInviteCode() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"deviceId":"dev-1","inviteCode":482913,"deviceToken":"tok-1"}"""),
        )
        val response = api.register(AddressBody.of("서울시 ○○구 ○○로 12", "101동 502호", "#1234", "안방"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/devices", request.path)
        assertNull(request.getHeader(DeviceHeader.NAME))
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("서울시 ○○구 ○○로 12", json.get("address").asString)
        assertEquals("101동 502호", json.get("detail").asString)
        assertEquals("#1234", json.get("entryMethod").asString)
        assertEquals("안방", json.get("roomNote").asString)
        assertEquals("dev-1", response.deviceId)
        assertEquals("482913", response.inviteCode)
        assertEquals("tok-1", response.deviceToken)
    }

    @Test
    fun optionalAddressFieldsAreOmitted() {
        val json = Gson().toJson(AddressBody.of("도로", "101동", "  ", ""))
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("도로", obj.get("address").asString)
        assertEquals("101동", obj.get("detail").asString)
        assertFalse(obj.has("entryMethod"))
        assertFalse(obj.has("roomNote"))
    }

    @Test
    fun updateAddressUsesDeviceToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val response = api.updateAddress("device-token", AddressBody.of("도로", "202호", "", "거실"))
        assertTrue(response.isSuccessful)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/devices/me/address", request.path)
        assertEquals("device-token", request.getHeader("X-Device-Token"))
    }

    @Test
    fun heartbeatBodyAndHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        api.heartbeat("tok", HeartbeatBody(40, HeartbeatStatus.WATCHING))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/devices/me/heartbeat", request.path)
        assertEquals("tok", request.getHeader("X-Device-Token"))
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(40, json.get("battery").asInt)
        assertEquals("watching", json.get("status").asString)
    }

    @Test
    fun fallTimeoutIncludesTestFlagOnlyWhenSet() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"eventId":"evt-9"}"""))
        val response = api.fall(
            "tok",
            FallEventBody(detectedAt = "2026-11-03T06:00:00Z", outcome = Outcomes.TIMEOUT, isTest = true),
        )
        assertEquals("evt-9", response.eventId)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/events/fall", request.path)
        assertEquals("tok", request.getHeader("X-Device-Token"))
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("2026-11-03T06:00:00Z", json.get("detectedAt").asString)
        assertEquals("timeout", json.get("outcome").asString)
        assertTrue(json.get("isTest").asBoolean)

        server.enqueue(MockResponse().setBody("""{"eventId":12}"""))
        val cancelled = api.fall(
            "tok",
            FallEventBody(detectedAt = "2026-11-03T06:01:00Z", outcome = Outcomes.CANCELLED, isTest = null),
        )
        assertEquals("12", cancelled.eventId)
        val second = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("cancelled", second.get("outcome").asString)
        assertFalse(second.has("isTest"))
    }

    @Test
    fun serverUrlDropsPathAndAddsScheme() {
        assertEquals("https://soominn.duckdns.org", ServerUrl.normalize("https://soominn.duckdns.org/"))
        assertEquals("https://soominn.duckdns.org", ServerUrl.normalize("soominn.duckdns.org"))
        assertEquals("http://10.0.2.2:8080", ServerUrl.normalize("http://10.0.2.2:8080/api"))
        assertNull(ServerUrl.normalize("   "))
    }
}
