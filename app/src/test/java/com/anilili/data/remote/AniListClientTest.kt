package com.anilili.data.remote

import com.anilili.data.model.AiringDayCount
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val DAY_SEC = 86_400L

class AniListClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AniListClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = AniListClient(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            endpoint = server.url("/graphql").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `MAL ids are matched in bounded 50 item requests`() = runBlocking {
        server.enqueue(graphQlMedia(id = 101, malId = 1))
        server.enqueue(graphQlMedia(id = 151, malId = 51))

        val result = client.mediaByMalIds((1..51).toList())

        assertEquals(listOf(1, 51), result.mapNotNull { it.idMal })
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/graphql", first.path)
        assertEquals("/graphql", second.path)
        assertTrue(first.body.readUtf8().contains("\"ids\":[1,2,3"))
        assertTrue(second.body.readUtf8().contains("\"ids\":[51]"))
    }

    @Test
    fun `popular this season is scoped to the season and upcoming rides the same request`() = runBlocking {
        server.enqueue(
            graphQlBody(
                """{"data":{"popular":{"media":[{"id":1,"isAdult":false}]},
                    "upcoming":{"media":[{"id":2,"isAdult":false}]}}}""",
            ),
        )

        val rows = client.homeCollections(AiringSeason("FALL", 2026))

        assertEquals(listOf(1), rows.popular.map { it.id })
        assertEquals(listOf(2), rows.upcoming.map { it.id })
        assertEquals(1, server.requestCount)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"season\":\"FALL\""))
        assertTrue(body.contains("\"seasonYear\":2026"))
        assertTrue(body.contains("status: NOT_YET_RELEASED"))
    }

    @Test
    fun `year ranking requires 5000 actual scores and preserves server paging`() = runBlocking {
        server.enqueue(graphQlBody("""{"data":{"Page":{
            "pageInfo":{"hasNextPage":true},"media":[
              {"id":1,"averageScore":99,"popularity":100000,"stats":{"scoreDistribution":[{"score":100,"amount":4999}]}},
              {"id":2,"averageScore":90,"format":"MOVIE","stats":{"scoreDistribution":[{"score":90,"amount":2500},{"score":100,"amount":2500}]}},
              {"id":3,"averageScore":89,"format":"OVA","stats":{"scoreDistribution":[{"score":90,"amount":6000}]}},
              {"id":4,"averageScore":88},
              {"id":5,"stats":{"scoreDistribution":[{"amount":6000}]}}
            ]}}}"""))
        val result = client.topRatedByYear(2025, page = 3, hideAdult = true)
        assertEquals(listOf(2, 3), result.items.map { it.id })
        assertTrue(result.hasNextPage)
        assertEquals(3, result.page)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"year\":2025"))
        assertTrue(body.contains("\"page\":3"))
        assertTrue(body.contains("\"isAdult\":false"))
        assertTrue(body.contains("SCORE_DESC, ID"))
        assertTrue(body.contains("scoreDistribution"))
        assertTrue(!body.contains("format:"))
    }

    @Test
    fun `a filtered empty ranking page can still have more results`() = runBlocking {
        server.enqueue(graphQlBody("""{"data":{"Page":{"pageInfo":{"hasNextPage":true},
            "media":[{"id":1,"averageScore":100,"stats":{"scoreDistribution":[{"amount":2}]}}]}}}"""))
        val result = client.topRatedByYear(2026)
        assertTrue(result.items.isEmpty())
        assertTrue(result.hasNextPage)
    }

    @Test
    fun `a date falls in AniList's calendar-quarter season`() {
        assertEquals(AiringSeason("WINTER", 2026), AiringSeason.of(java.time.LocalDate.of(2026, 3, 31)))
        assertEquals(AiringSeason("SPRING", 2026), AiringSeason.of(java.time.LocalDate.of(2026, 4, 1)))
        assertEquals(AiringSeason("SUMMER", 2026), AiringSeason.of(java.time.LocalDate.of(2026, 9, 27)))
        assertEquals(AiringSeason("FALL", 2026), AiringSeason.of(java.time.LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `a stalled AniList request remains cancellable`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(150) { client.mediaByMalIds(listOf(1)) }
            }
        }
    }

    @Test
    fun `a whole calendar month is asked for as one request, one alias per day`() = runBlocking {
        // Each day carries a different number of episodes, so a positional mix-up cannot pass.
        val pages = (0 until 42).joinToString(",") { index ->
            val entries = (0..index).joinToString(",") { n ->
                """{"airingAt":${index * DAY_SEC + n},"media":{"id":$n,"isAdult":false}}"""
            }
            """"d$index":{"pageInfo":{"hasNextPage":false},"airingSchedules":[$entries]}"""
        }
        server.enqueue(graphQlBody("""{"data":{$pages}}"""))

        val counts = client.airingCounts(dayWindows(42))

        assertEquals((1..42).toList(), counts.map { it?.total })
        assertEquals(1, server.requestCount)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("the first day has no alias of its own", body.contains("d0: Page"))
        assertTrue("the last day has no alias of its own", body.contains("d41: Page"))
    }

    @Test
    fun `more days than one query carries are chunked rather than dropped`() = runBlocking {
        server.enqueue(graphQlBody(emptyDays(0 until AniListClient.MAX_COUNT_ALIASES)))
        server.enqueue(graphQlBody(emptyDays(0 until 3)))

        val counts = client.airingCounts(dayWindows(AniListClient.MAX_COUNT_ALIASES + 3))

        assertEquals(AniListClient.MAX_COUNT_ALIASES + 3, counts.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a day's tally keeps the adult split and flags a day that did not fit`() = runBlocking {
        server.enqueue(
            graphQlBody(
                """{"data":{"d0":{"pageInfo":{"hasNextPage":true},"airingSchedules":[
                    {"airingAt":100,"media":{"id":1,"isAdult":false}},
                    {"airingAt":100,"media":{"id":1,"isAdult":false}},
                    {"airingAt":200,"media":{"id":1,"isAdult":false}},
                    {"airingAt":300,"media":{"id":2,"isAdult":true}}]}}}""",
            ),
        )

        val counts = client.airingCounts(dayWindows(1))

        // The repeat at 100 is one episode, the same title an hour later is another, and the adult
        // entry is counted but held apart so the setting can be applied when the cache is read.
        assertEquals(AiringDayCount(total = 3, nonAdult = 2, truncated = true), counts.single())
    }

    @Test
    fun `a day AniList did not answer for is not a day with nothing on it`() = runBlocking {
        server.enqueue(
            graphQlBody("""{"data":{"d0":{"pageInfo":{"hasNextPage":false},"airingSchedules":[]}}}"""),
        )

        val counts = client.airingCounts(dayWindows(2))

        assertEquals(AiringDayCount(total = 0, nonAdult = 0, truncated = false), counts[0])
        assertNull(counts[1])
    }

    private fun dayWindows(days: Int): List<LongRange> =
        (0 until days).map { it * DAY_SEC until (it + 1) * DAY_SEC }

    private fun emptyDays(indices: IntRange): String {
        val pages = indices.joinToString(",") { index ->
            """"d$index":{"pageInfo":{"hasNextPage":false},"airingSchedules":[]}"""
        }
        return """{"data":{$pages}}"""
    }

    private fun graphQlBody(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun graphQlMedia(id: Int, malId: Int): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"data":{"Page":{"media":[{"id":$id,"idMal":$malId,"title":{"english":"Anime $malId"}}]}}}""",
            )
}

/**
 * AniList disabled its API on 2 Aug 2026 and said so in the 403 body. Telling people to switch
 * networks on top of that sends them chasing a connection fault that isn't there.
 */
class AniListFailureGuidanceTest {
    private val explained = listOf("The AniList API has been temporarily disabled due to severe stability issues.")

    @Test
    fun `a bare 403 still suggests the network`() {
        assertTrue(anilistFailureGuidance(403, emptyList()).contains("switch networks"))
    }

    @Test
    fun `an explained 403 is never answered with sign-in advice`() {
        // It was, and it was wrong twice over. Signing in appeared to help during the 2 Aug
        // shutdown only because a token is a header, and the refusal is of header-less requests;
        // the app now always sends a Referer, so there is nothing left for the reader to do. And
        // the people who saw this were most often signed in with MyAnimeList, who hold no AniList
        // token and were being told to adopt the service they had chosen against.
        assertEquals("", anilistFailureGuidance(403, explained))
    }

    @Test
    fun `other failure codes never get extra advice`() {
        assertEquals("", anilistFailureGuidance(500, emptyList()))
        assertEquals("", anilistFailureGuidance(404, emptyList()))
    }
}
