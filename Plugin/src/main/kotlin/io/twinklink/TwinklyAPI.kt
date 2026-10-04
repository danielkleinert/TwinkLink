package io.twinklink

import com.google.gson.JsonObject
import heronarts.lx.LX
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.*
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

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
        .connectTimeout(2.seconds.toJavaDuration())
        .build()
    private val buffer = ByteArray(1 + 8 + 2 + 1 + V3_MAX_CHUNK_SIZE)
    private val socket: DatagramSocket = try {
        DatagramSocket()
    } catch (e: SocketException) {
        throw RuntimeException("Failed to create UDP socket", e)
    }

    private val authMutex = Mutex()
    @Volatile private var authToken: String? = null
    private var decodedAuthToken = ByteArray(8)
    private var refreshJob: Job? = null

    private fun scheduleTokenRefresh(expiresIn: Duration) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay((expiresIn - 1.minutes).inWholeMilliseconds)
            try {
                authenticate()
            } catch (e: Exception) {
                LX.error(e, "Failed to proactively refresh Twinkly token")
            }
        }
    }

    private suspend fun authenticate() = authMutex.withLock { login() }

    private suspend fun login() {
        val loginPayload = JsonObject()
        val challenge = ByteArray(32)
        SecureRandom().nextBytes(challenge)
        val challengeB64 = Base64.getEncoder().encodeToString(challenge)
        loginPayload.addProperty("challenge", challengeB64)

        val loginResponse = send("POST", "/xled/v1/login", loginPayload, retryAuth = false)
        val token = loginResponse.get("authentication_token").asString
        val challengeResponse = loginResponse.get("challenge-response").asString
        val expiresIn = loginResponse.get("authentication_token_expires_in").asInt

        authToken = token
        val verifyPayload = JsonObject()
        verifyPayload.addProperty("challenge-response", challengeResponse)
        send("POST", "/xled/v1/verify", verifyPayload, retryAuth = false)

        decodedAuthToken = Base64.getDecoder().decode(token)
        scheduleTokenRefresh(expiresIn.seconds)
    }

    /** Logs in again after [failedToken] was rejected, unless a concurrent request already did. */
    private suspend fun reauthenticate(failedToken: String?) = authMutex.withLock {
        if (authToken == failedToken) login()
    }

    suspend fun getMode(): String {
        val response = send("GET", "/xled/v1/led/mode")
        return response.get("mode")?.asString
            ?: throw IllegalStateException("No mode in response")
    }

    suspend fun setMode(mode: String) {
        val payload = JsonObject()
        payload.addProperty("mode", mode)
        send("POST", "/xled/v1/led/mode", payload)
    }

    suspend fun getBrightness(): Int {
        val response = send("GET", "/xled/v1/led/out/brightness")
        return response.get("value")?.asInt
            ?: throw IllegalStateException("No brightness in response")
    }

    suspend fun setBrightness(value: Int) {
        val payload = JsonObject()
        payload.addProperty("value", value)
        send("POST", "/xled/v1/led/out/brightness", payload)
    }

    /** Movies stored on the device, since firmware 2.5.6. */
    suspend fun getMovies(): MovieList {
        val response = send("GET", "/xled/v1/movies")
        val movies = response.getAsJsonArray("movies").map {
            val m = it.asJsonObject
            Movie(
                id = m.get("id").asInt,
                name = m.get("name").asString,
                uniqueId = m.get("unique_id").asString,
                frames = m.get("frames_number").asInt,
                fps = m.get("fps").asInt
            )
        }
        return MovieList(
            movies,
            availableFrames = response.get("available_frames").asInt,
            maxCapacity = response.get("max_capacity").asInt,
            maxMovies = response.get("max")?.asInt
        )
    }

    /** The rate the device shows frames at; movies stored faster play stretched to it. */
    suspend fun getFrameRate(): Double? = send("GET", "/xled/v1/gestalt").get("frame_rate")?.asDouble

    suspend fun getCurrentMovieId(): Int? = send("GET", "/xled/v1/movies/current").get("id")?.asInt

    suspend fun setCurrentMovie(id: Int) {
        val payload = JsonObject()
        payload.addProperty("id", id)
        send("POST", "/xled/v1/movies/current", payload)
    }

    /** Not in the xled docs, captured from the Twinkly app, which only offers it for the last movie. */
    suspend fun deleteMovie(uniqueId: String) {
        send("DELETE", "/xled/v1/movies/$uniqueId")
    }

    /** Creates a movie entry to upload frames to, like xled_plus; returns its id. */
    suspend fun createMovie(name: String, uniqueId: String, descriptorType: String, ledsPerFrame: Int, frames: Int, fps: Int): Int {
        val payload = JsonObject()
        payload.addProperty("name", name)
        payload.addProperty("unique_id", uniqueId)
        payload.addProperty("descriptor_type", descriptorType)
        payload.addProperty("leds_per_frame", ledsPerFrame)
        payload.addProperty("frames_number", frames)
        payload.addProperty("fps", fps)
        return send("POST", "/xled/v1/movies/new", payload).get("id").asInt
    }

    /** Uploads the frames of the movie created last, laid out like realtime frames. The device stores them slowly. */
    suspend fun uploadMovieFrames(frames: ByteArray) {
        val timeout = REQUEST_TIMEOUT + (frames.size / UPLOAD_BYTES_PER_SECOND).seconds
        send("POST", "/xled/v1/movies/full", Body("application/octet-stream", frames), timeout = timeout)
    }

    private class Body(val contentType: String, val bytes: ByteArray)

    private suspend fun send(method: String, path: String, payload: JsonObject? = null, retryAuth: Boolean = true): JsonObject =
        send(method, path, payload?.let { Body("application/json", gson.toJson(it).toByteArray(StandardCharsets.UTF_8)) }, retryAuth)

    /**
     * Sends an HTTP request with the current token, logging in first if there is none. A rejected token was replaced
     * by another client's login (the device keeps a single one), so the request is repeated once after logging in again.
     */
    private suspend fun send(
        method: String, path: String, body: Body?, retryAuth: Boolean = true, timeout: Duration = REQUEST_TIMEOUT
    ): JsonObject {
        if (retryAuth && authToken == null) reauthenticate(null)
        val token = authToken
        val response = try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(host + path))
                .timeout(timeout.toJavaDuration())
            if (body != null) {
                builder.header("Content-Type", body.contentType)
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body.bytes))
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            }
            token?.let { builder.header("X-Auth-Token", it) }
            httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("$method $path failed", e)
        }

        if (response.statusCode() == 401 && retryAuth) {
            reauthenticate(token)
            return send(method, path, body, retryAuth = false, timeout = timeout)
        }
        if (response.statusCode() !in 200..299) {
            throw IOException("$method $path returned HTTP ${response.statusCode()}: ${response.body()}")
        }
        // Some requests answer 204 without a body
        val body = response.body()
        val json = if (body.isBlank()) JsonObject() else gson.fromJson(body, JsonObject::class.java)
        val code = json.get("code")?.asInt
        if (code != null && code != CODE_OK) {
            throw TwinklyRefusedException("$method $path returned code $code", code)
        }
        return json
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
        refreshJob?.cancel()
        scope.cancel()
        // Frees its kept-alive connections, as the device's HTTP server accepts only a few
        httpClient.shutdownNow()
        socket.takeUnless { it.isClosed }?.close()
    }

    companion object {
        private const val RT_PORT = 7777
        private const val V3_MAX_CHUNK_SIZE = 900
        private const val CODE_OK = 1000
        private val REQUEST_TIMEOUT = 10.seconds
        /** A third of the rate a TWS400STP stored a movie at (864 KB in 15 s). */
        private const val UPLOAD_BYTES_PER_SECOND = 20_000
    }
}

/** The device answered a request with an application error code. */
class TwinklyRefusedException(message: String, val code: Int) : IOException(message)
