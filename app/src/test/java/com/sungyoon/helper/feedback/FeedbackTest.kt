package com.sungyoon.helper.feedback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class FeedbackTest {
    private fun message(text: String = "한국어 요청사항\n줄바꿈 😀") = FeedbackMessage(text = text, appVersion = "1.3", versionCode = 23)

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        var failWrites = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            val next = transform(data.value)
            if (failWrites) throw IOException("Storage unavailable")
            data.value = next
            next
        }
    }

    @Test fun textAndMetadataSurviveDiskRestart() = runBlocking {
        val directory = Files.createTempDirectory("feedback-test").toFile()
        val file = directory.resolve("feedback.preferences_pb")
        val message = message("줄바꿈\n😀" + "가".repeat(9993))
        message.validate()
        val firstJob = Job()
        val first = FeedbackOutbox(PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO)) { file })
        first.append(message)
        firstJob.cancelAndJoin()
        val nextJob = Job()
        try {
            val reopened = FeedbackOutbox(PreferenceDataStoreFactory.create(scope = CoroutineScope(nextJob + Dispatchers.IO)) { file })
            assertEquals(listOf(message), reopened.pending())
        } finally {
            nextJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun validationRejectsBlankOverlongAndUnsafeMetadata() {
        listOf(message(" \n\t"), message("가".repeat(10001)), message().copy(id = "invalid"),
            message().copy(appVersion = "1.3\nInjected"), message().copy(versionCode = 0)).forEach {
            assertTrue(runCatching { it.validate() }.isFailure)
        }
        message("가".repeat(10000)).validate()
    }

    @Test fun concurrentSubmissionsNeverLoseMessagesAndDuplicateIdsAreIdempotent() = runBlocking {
        val outbox = FeedbackOutbox(MemoryStore())
        val messages = (1..100).map { message("message $it") }
        messages.map { async(Dispatchers.Default) { outbox.append(it) } }.awaitAll()
        assertEquals(messages.toSet(), outbox.pending().toSet())
        outbox.append(messages.first())
        assertEquals(100, outbox.pending().size)
        assertTrue(runCatching { outbox.append(message()) }.exceptionOrNull() is FeedbackQueueFullException)
        assertTrue(runCatching { outbox.append(messages.first().copy(text = "changed")) }.isFailure)
        assertEquals(messages.toSet(), outbox.pending().toSet())
    }

    @Test fun failedWriteOrMalformedDataNeverResetAnExistingQueue() = runBlocking {
        val store = MemoryStore()
        val outbox = FeedbackOutbox(store)
        val saved = message()
        outbox.append(saved)
        store.failWrites = true
        assertTrue(runCatching { outbox.append(message()) }.isFailure)
        assertEquals(listOf(saved), outbox.pending())
        val malformed = MemoryStore(mutablePreferencesOf(stringPreferencesKey("pending_messages") to "broken"))
        assertTrue(runCatching { FeedbackOutbox(malformed).append(message()) }.isFailure)
        assertEquals("broken", malformed.data.value[stringPreferencesKey("pending_messages")])
    }

    @Test fun unavailableNetworkAndUnacknowledgedResponseKeepMessageForRetry() = runBlocking {
        val outbox = FeedbackOutbox(MemoryStore())
        val saved = message()
        outbox.append(saved)
        assertFalse(FeedbackDelivery(outbox) { false }.deliver(saved.id))
        assertTrue(runCatching { FeedbackDelivery(outbox) { throw IOException("Offline") }.deliver(saved.id) }.isFailure)
        assertEquals(listOf(saved), outbox.pending())
    }

    @Test fun acknowledgementRemovesOnlyItsMessageEvenWhileAnotherIsSubmitted() = runBlocking {
        val outbox = FeedbackOutbox(MemoryStore())
        val first = message()
        val next = message("next")
        outbox.append(first)
        val delivery = FeedbackDelivery(outbox) { outbox.append(next); true }
        assertTrue(delivery.deliver(first.id))
        assertEquals(listOf(next), outbox.pending())
        assertTrue(delivery.deliver(first.id))
    }

    @Test fun onlyExplicitMatchingAcknowledgementsAreAccepted() {
        val id = message().id
        assertTrue(FeedbackProtocol.acknowledged("{\"status\":\"sent\",\"id\":\"$id\"}", id))
        assertTrue(FeedbackProtocol.acknowledged("{\"status\":\"already_sent\",\"id\":\"$id\"}", id))
        listOf("{\"status\":\"retry\",\"id\":\"$id\"}", "{\"status\":\"sent\"}",
            "{\"status\":\"sent\",\"id\":\"other\"}", "<html>Google sign-in</html>", "", "{}").forEach {
            assertFalse(FeedbackProtocol.acknowledged(it, id))
        }
    }

    @Test fun trustedEndpointAndResponseRedirectsCannotLeakThePostToOtherOrigins() {
        assertTrue(FeedbackProtocol.validEndpoint("https://script.google.com/macros/s/deployment_123/exec"))
        listOf("", "http://script.google.com/macros/s/id/exec", "https://script.google.com.evil/macros/s/id/exec",
            "https://user@script.google.com/macros/s/id/exec", "https://script.google.com/macros/s/id/dev",
            "https://script.google.com/macros/s/id/exec?to=other").forEach { assertFalse(FeedbackProtocol.validEndpoint(it)) }
        assertTrue(FeedbackProtocol.validRedirect(URI("https://script.googleusercontent.com/macros/echo?key=123")))
        listOf("https://evil.example/macros/echo", "https://script.googleusercontent.com.evil/macros/echo",
            "http://script.googleusercontent.com/macros/echo", "https://user@script.googleusercontent.com/macros/echo",
            "https://script.googleusercontent.com:8443/macros/echo", "https://script.googleusercontent.com/macros/echo#secret",
            "https://accounts.google.com/login").forEach { assertFalse(FeedbackProtocol.validRedirect(URI(it))) }
    }

    private class Connection(url: URL, private val code: Int, private val response: String, private val location: String? = null) : HttpURLConnection(url) {
        val written = ByteArrayOutputStream()
        var disconnected = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = written
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }

    @Test fun contentServiceRedirectReadsAckWithoutReplayingTextPost() = runBlocking {
        val saved = message()
        val connections = mutableListOf<Connection>()
        val transport = AppsScriptFeedbackTransport("https://script.google.com/macros/s/id/exec") { url ->
            Connection(url, if (connections.isEmpty()) 302 else 200,
                "{\"status\":\"sent\",\"id\":\"${saved.id}\"}",
                "https://script.googleusercontent.com/macros/echo?key=123").also { connections += it }
        }
        assertTrue(transport.send(saved))
        assertEquals(2, connections.size)
        assertEquals("POST", connections[0].requestMethod)
        assertTrue(connections[0].written.toString("UTF-8").contains("한국어"))
        assertEquals("GET", connections[1].requestMethod)
        assertEquals(0, connections[1].written.size())
        assertTrue(connections.all { it.disconnected && !it.instanceFollowRedirects && it.readTimeout == 10000 })
    }

    @Test fun unexpectedRedirectAndOversizedResponsesNeverAcknowledge() = runBlocking {
        val saved = message()
        var opens = 0
        val endpoint = "https://script.google.com/macros/s/id/exec"
        val unsafe = AppsScriptFeedbackTransport(endpoint) { url ->
            opens++; Connection(url, 302, "", "https://evil.example/steal")
        }
        assertFalse(unsafe.send(saved))
        assertEquals(1, opens)
        val oversized = AppsScriptFeedbackTransport(endpoint) { Connection(it, 200, "x".repeat(16385)) }
        assertTrue(runCatching { oversized.send(saved) }.exceptionOrNull() is IOException)
    }
}
