package com.anilili.data.remote

import com.anilili.playback.EpisodeDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Proves the #73 sharing: each mirror set keeps one ConnectionPool and one Dispatcher, and every
 * derived client holds exactly the deadlines it stated before it was derived. Nothing here
 * touches a network — a client built with `newBuilder()` shares the pool and dispatcher by
 * reference, which is the whole of what is being asserted.
 */
class MirrorConnectionPoolTest {
    @Test
    fun `the pipe client shares the catalog client's pool and dispatcher`() {
        val base = MiruroCatalogApi.client
        val pipe = OkHttpPipe.client
        assertSame(base.connectionPool, pipe.connectionPool)
        assertSame(base.dispatcher, pipe.dispatcher)
        assertEquals(30_000, pipe.callTimeoutMillis)
        assertEquals(15_000, pipe.connectTimeoutMillis)
        assertEquals(30_000, pipe.readTimeoutMillis)
    }

    @Test
    fun `the catalog client keeps its own deadlines`() {
        val client = MiruroCatalogApi.client
        assertEquals(20_000, client.callTimeoutMillis)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
    }

    @Test
    fun `the subtitle client shares the download client's pool and dispatcher`() {
        val base = EpisodeDownloads.downloadHttpClient
        val subtitle = EpisodeDownloads.subtitleClient
        assertSame(base.connectionPool, subtitle.connectionPool)
        assertSame(base.dispatcher, subtitle.dispatcher)
        assertEquals(20_000, subtitle.connectTimeoutMillis)
        assertEquals(30_000, subtitle.readTimeoutMillis)
    }

    @Test
    fun `the download client keeps its own deadlines`() {
        val client = EpisodeDownloads.downloadHttpClient
        assertEquals(20_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
    }
}
