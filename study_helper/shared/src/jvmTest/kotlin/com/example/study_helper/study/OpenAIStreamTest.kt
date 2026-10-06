package com.example.study_helper.study

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAIStreamTest {
    private class Connection(private val code: Int, private val payload: String) : HttpURLConnection(URL("https://api.openai.com/v1/responses")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(payload.toByteArray())
    }

    @Test fun sendsBearerAuthAndDeliversSseEventsWithoutMetadata() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val connection = Connection(200, "event: response.output_text.delta\r\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"한국어 표\"}\r\n\r\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
            val events = mutableListOf<String>()
            val done = CompletableDeferred<String>()
            OpenAIStream(this) { connection }.generate("test-only-key", "gpt-test", "{\"stream\":true}", { events += it }, { done.complete(it) })
            assertEquals("", done.await())
            assertEquals("Bearer test-only-key", connection.getRequestProperty("Authorization"))
            assertNull(connection.getRequestProperty("x-goog-api-key"))
            assertEquals("{\"stream\":true}", connection.sent.toString("UTF-8"))
            assertEquals("한국어 표" to false, PdfStudyProtocol.eventText(events[0]))
            assertEquals("" to true, PdfStudyProtocol.eventText(events[1]))
            assertTrue(connection.disconnected)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun quotaFailureDoesNotForwardServerBodyOrCredentials() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val connection = Connection(429, "server echoes test-only-key")
            val done = CompletableDeferred<String>()
            OpenAIStream(this) { connection }.generate("test-only-key", "gpt-test", "{}", { fail("Must not forward error body") }, { done.complete(it) })
            val message = done.await()
            assertTrue(message.contains("한도")); assertFalse(message.contains("test-only-key"))
            assertTrue(connection.disconnected)
        } finally { Dispatchers.resetMain() }
    }
}
