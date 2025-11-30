package io.twinklink

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import heronarts.lx.LX
import kotlinx.coroutines.*
import java.io.IOException
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.*
import kotlin.math.min

class TwinklyAPI(ipAddress: String, private val protocolVersion: Int) {
    private val host: String = "http://$ipAddress"
    private var address: InetAddress? = try {
        InetAddress.getByName(ipAddress)
    } catch (_: UnknownHostException) {
        null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    private val buffer = ByteArray(1 + 8 + 2 + 1 + V3_MAX_CHUNK_SIZE)
    private val socket: DatagramSocket = try {
        DatagramSocket()
    } catch (e: SocketException) {
        throw RuntimeException("Failed to create UDP socket", e)
    }

    private var authToken: String? = null
    private var decodedAuthToken = ByteArray(8)
    private var tokenExpiresAt: Long = 0

    suspend fun authenticate() {
        // 1. Login
        val loginPayload = JsonObject()
        val challenge = ByteArray(32)
        SecureRandom().nextBytes(challenge)
        val challengeB64 = Base64.getEncoder().encodeToString(challenge)
        loginPayload.addProperty("challenge", challengeB64)

        val loginResponse = post("/xled/v1/login", loginPayload)
        authToken = loginResponse.get("authentication_token").asString
        val challengeResponse = loginResponse.get("challenge-response").asString
        val expiresIn = loginResponse.get("authentication_token_expires_in").asInt

        // 2. Verify
        val verifyPayload = JsonObject()
        verifyPayload.addProperty("challenge-response", challengeResponse)
        post("/xled/v1/verify", verifyPayload)

        decodedAuthToken = Base64.getDecoder().decode(authToken)
        tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L) - 5000 // 5s buffer
    }

    suspend fun getMode(): String {
        val response = get("/xled/v1/led/mode")
        return response.get("mode")?.asString
            ?: throw IllegalStateException("No mode in response")
    }

    suspend fun setMode(mode: String) {
        val payload = JsonObject()
        payload.addProperty("mode", mode)
        post("/xled/v1/led/mode", payload)
    }

    suspend fun getBrightness(): Int {
        val response = get("/xled/v1/led/out/brightness")
        return response.get("value")?.asInt
            ?: throw IllegalStateException("No brightness in response")
    }

    suspend fun setBrightness(value: Int) {
        val payload = JsonObject()
        payload.addProperty("value", value)
        post("/xled/v1/led/out/brightness", payload)
    }


    private suspend fun get(path: String): JsonObject = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(host + path))
                .header("X-Auth-Token", authToken)
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                LX.error("GET $path returned HTTP ${response.statusCode()}: ${response.body()}")
            }
            gson.fromJson(response.body(), JsonObject::class.java)
        } catch (e: Exception) {
            throw RuntimeException("GET request failed for $path", e)
        }
    }

    private suspend fun post(path: String, payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(payload)

            val builder = HttpRequest.newBuilder()
                .uri(URI.create(host + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))

            authToken?.let { builder.header("X-Auth-Token", it) }

            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                LX.error("POST $path returned HTTP ${response.statusCode()}: ${response.body()}")
            }
            gson.fromJson(response.body(), JsonObject::class.java)
        } catch (e: Exception) {
            throw RuntimeException("POST request failed for $path", e)
        }
    }

    fun sendRealtimeFrame(frameData: ByteArray) {
        when (protocolVersion) {
            1 -> {
                // Version 1: Single packet (Generation I)
                val ledCount = frameData.size / 3 // Assuming RGB
                val packet = ByteArray(1 + 8 + 1 + frameData.size)
                var offset = 0

                packet[offset++] = 0x01
                System.arraycopy(decodedAuthToken, 0, packet, offset, 8)
                offset += 8
                packet[offset++] = ledCount.toByte()
                System.arraycopy(frameData, 0, packet, offset, frameData.size)

                sendPacket(packet, packet.size)
            }
            2 -> {
                // Version 2: Single packet (Generation II pre-2.4.14)
                val packet = ByteArray(1 + 8 + 1 + frameData.size)
                var offset = 0

                packet[offset++] = 0x02
                System.arraycopy(decodedAuthToken, 0, packet, offset, 8)
                offset += 8
                packet[offset++] = 0x00
                System.arraycopy(frameData, 0, packet, offset, frameData.size)

                sendPacket(packet, packet.size)
            }
            else -> {
                // Version 3: Chunked packets (Generation II post-2.4.14, default)
                var bytesSent = 0
                var chunkIndex = 0

                while (bytesSent < frameData.size) {
                    val chunkSize = min(frameData.size - bytesSent, V3_MAX_CHUNK_SIZE)

                    // Build packet in buffer
                    var offset = 0
                    buffer[offset++] = 0x03
                    System.arraycopy(decodedAuthToken, 0, buffer, offset, 8)
                    offset += 8
                    buffer[offset++] = 0x00
                    buffer[offset++] = 0x00
                    buffer[offset++] = chunkIndex.toByte()
                    System.arraycopy(frameData, bytesSent, buffer, offset, chunkSize)
                    offset += chunkSize

                    sendPacket(buffer, offset)

                    bytesSent += chunkSize
                    chunkIndex++
                }
            }
        }
    }

    private fun sendPacket(data: ByteArray, length: Int) {
        try {
            val packet = DatagramPacket(data, length, address, RT_PORT)
            socket.send(packet)
        } catch (e: IOException) {
            LX.error(e, "Error sending Twinkly RT packet")
        }
    }

    fun dispose() {
        scope.cancel()
        socket.takeUnless { it.isClosed }?.close()
    }

    companion object {
        private const val RT_PORT = 7777
        private const val V3_MAX_CHUNK_SIZE = 900

        private val gson: Gson = GsonBuilder()
            .disableHtmlEscaping()
            .create()
    }
}
