package com.sungyoon.helper.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

fun interface FeedbackTransport {
    suspend fun send(message: FeedbackMessage): Boolean
}

internal object FeedbackProtocol {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class Reply(val status: String, val id: String = "")

    fun body(message: FeedbackMessage): ByteArray = json.encodeToString(message).toByteArray(Charsets.UTF_8)
    fun acknowledged(body: String, id: String): Boolean = runCatching {
        val reply = json.decodeFromString<Reply>(body)
        reply.id == id && reply.status in setOf("sent", "already_sent")
    }.getOrDefault(false)

    fun validEndpoint(endpoint: String): Boolean =
        Regex("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec").matches(endpoint)

    fun validRedirect(url: URI): Boolean = url.scheme == "https" &&
        url.host == "script.googleusercontent.com" && url.port in listOf(-1, 443) &&
        url.rawUserInfo == null && url.rawFragment == null && url.path == "/macros/echo"
}

/** Apps Script redirects its JSON response to ContentService; the POST body is never replayed there. */
class AppsScriptFeedbackTransport internal constructor(
    private val endpoint: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : FeedbackTransport {
    override suspend fun send(message: FeedbackMessage): Boolean {
        check(FeedbackProtocol.validEndpoint(endpoint))
        message.validate()
        var url = URL(endpoint)
        var isPost = true
        repeat(4) { hop ->
            val connection = openConnection(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.requestMethod = if (isPost) "POST" else "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "SungyoonFeedback/1")
                if (isPost) {
                    val body = FeedbackProtocol.body(message)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val response = connection.inputStream.use { it.readBytesBounded(16_384) }
                        return FeedbackProtocol.acknowledged(response.toString(Charsets.UTF_8), message.id)
                    }
                    301, 302, 303 -> {
                        if (hop == 3) return false
                        val location = connection.getHeaderField("Location") ?: return false
                        val redirected = url.toURI().resolve(location)
                        if (!FeedbackProtocol.validRedirect(redirected)) return false
                        url = redirected.toURL()
                        isPost = false
                    }
                    else -> return false
                }
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("Feedback response exceeds limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
