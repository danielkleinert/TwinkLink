package io.twinklink

import com.google.gson.JsonObject
import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

object TwinklyCloudAPI {
    private const val API_BASE = "https://api.twinkly.com"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(10.seconds.toJavaDuration())
        .build()

    suspend fun getLayouts(username: String, password: String): List<LayoutFacade> {
        val credentials = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
        }
        val token = send(request("/v2/auth").POST(HttpRequest.BodyPublishers.ofString(gson.toJson(credentials))))
            .get("access_token").asString

        val objects = send(request("/v3/objects?mine=true&fields=layout,devices,capabilities")
            .header("Authorization", "Bearer $token")
            .GET())
        return parseLayouts(objects)
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI.create(API_BASE + path))
        .header("Content-Type", "application/json")

    private suspend fun send(builder: HttpRequest.Builder): JsonObject {
        val request = builder.build()
        val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) {
            throw IOException("${request.method()} ${request.uri().path} returned ${response.statusCode()}: ${response.body()}")
        }
        return gson.fromJson(response.body(), JsonObject::class.java)
    }
}
