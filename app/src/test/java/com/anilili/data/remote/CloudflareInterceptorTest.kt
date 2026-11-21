package com.anilili.data.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

class CloudflareInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        CloudflareChallengeCoordinator.resetForTest()
        CloudflareClearanceStore.resetForTest()
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(CloudflareInterceptor())
            .eventListenerFactory(
                NonInteractiveChallenge.eventListenerFactory(
                    object : EventListener.Factory {
                        override fun create(call: Call): EventListener = EventListener.NONE
                    },
                ),
            )
            .build()
    }

    @After
    fun tearDown() {
        CloudflareChallengeCoordinator.resetForTest()
        server.shutdown()
    }

    @Test
    fun aTaggedRequestGetsTheChallengeBackWithoutADialog() {
        server.enqueue(challengeResponse())
        val request = Request.Builder()
            .url(server.url("/episode"))
            .tag(NonInteractiveChallenge::class.java, NonInteractiveChallenge)
            .build()

        val code = client.newCall(request).execute().use { it.code }

        assertEquals(503, code)
        assertNull(CloudflareChallengeCoordinator.challenge.value)
    }

    @Test
    fun aRequestMadeUnderTheContextElementGetsTheChallengeBackWithoutADialog() {
        server.enqueue(challengeResponse())
        val request = Request.Builder().url(server.url("/episode")).build()

        val code = runBlocking {
            withContext(NonInteractiveChallenge.asContextElement()) {
                client.newCall(request).execute().use { it.code }
            }
        }

        assertEquals(503, code)
        assertNull(CloudflareChallengeCoordinator.challenge.value)
    }

    @Test
    fun aCallCreatedUnderTheMarkStaysMarkedWhenEnqueued() {
        // enqueue runs the interceptor on OkHttp's dispatcher thread, where the thread-local is
        // never set; the call itself has to carry the mark there.
        server.enqueue(challengeResponse())
        val request = Request.Builder().url(server.url("/episode")).build()
        val call = runBlocking {
            withContext(NonInteractiveChallenge.asContextElement()) { client.newCall(request) }
        }
        val code = AtomicReference<Int?>()
        val done = CountDownLatch(1)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done.countDown()

            override fun onResponse(call: Call, response: Response) {
                response.use { code.set(it.code) }
                done.countDown()
            }
        })

        done.await(5, TimeUnit.SECONDS)
        assertEquals(503, code.get())
        assertNull(CloudflareChallengeCoordinator.challenge.value)
    }

    @Test
    fun anUnmarkedRequestStillRaisesTheChallenge() {
        server.enqueue(challengeResponse())
        val request = Request.Builder().url(server.url("/episode")).build()
        val code = AtomicReference<Int?>()
        val worker = Thread {
            code.set(client.newCall(request).execute().use { it.code })
        }
        worker.start()

        val deadline = System.currentTimeMillis() + 5_000L
        var challenge = CloudflareChallengeCoordinator.challenge.value
        while (challenge == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            challenge = CloudflareChallengeCoordinator.challenge.value
        }
        assertNotNull(challenge)
        // Dismissed, the request falls through with the challenge it was given.
        CloudflareChallengeCoordinator.cancel(challenge!!.id)
        worker.join(5_000L)

        assertEquals(503, code.get())
    }

    private fun challengeResponse() = MockResponse()
        .setResponseCode(503)
        .setHeader("Server", "cloudflare")
        .setHeader("cf-mitigated", "challenge")
        .setBody("<html><title>Just a moment...</title></html>")
}
