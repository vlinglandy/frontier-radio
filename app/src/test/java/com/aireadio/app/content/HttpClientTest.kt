package com.aireadio.app.content

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class HttpClientTest {
    @Test fun `rate limit preserves retry deadline for repository backoff`() {
        val client = OkHttpClient.Builder().addInterceptor {
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(429)
                .message("Too Many Requests").header("Retry-After", "120").body("".toResponseBody()).build()
        }.build()
        val started = System.currentTimeMillis()
        val error = runCatching { FeedClient(client).get("https://example.com/feed") }.exceptionOrNull()
        assertTrue(error is HttpFailure)
        error as HttpFailure
        assertEquals(429, error.status)
        assertTrue(error.retryAt >= started + 120_000)
    }
    @Test fun `oversized payload is rejected before parsing`() {
        val client = OkHttpClient.Builder().addInterceptor {
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("x".repeat(4 * 1024 * 1024 + 1).toResponseBody()).build()
        }.build()
        assertTrue(runCatching { FeedClient(client).get("https://example.com/feed") }.exceptionOrNull()?.message?.contains("4 MB") == true)
    }
    @Test fun `HTTPS downgrade in final response is rejected`() {
        val client = OkHttpClient.Builder().addInterceptor {
            Response.Builder().request(it.request().newBuilder().url("http://example.com/feed").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body("bad".toResponseBody()).build()
        }.build()
        assertTrue(runCatching { FeedClient(client).get("https://example.com/feed") }.isFailure)
    }
}
