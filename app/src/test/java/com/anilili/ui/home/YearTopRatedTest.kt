package com.anilili.ui.home

import com.anilili.data.model.Media
import com.anilili.data.model.MediaPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class YearTopRatedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() { scope.cancel() }

    @Test fun `defaults to current year and skips filtered pages without losing the cursor`() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val row = YearTopRated(scope, 2026) { year, page, _ ->
            calls += year to page
            MediaPage(if (page == 2) listOf(Media(1)) else emptyList(), page < 3, page)
        }
        assertEquals(2026, row.state.value.year)
        assertTrue(calls.isEmpty())
        row.select(2026)
        assertEquals(listOf(2026 to 1, 2026 to 2), calls)
        assertEquals(listOf(1), row.state.value.items.map { it.id })
        row.loadMore()
        assertEquals(2026 to 3, calls.last())
        assertFalse(row.state.value.hasMore)
    }

    @Test fun `sparse years scan at most five pages per action`() {
        var calls = 0
        val row = YearTopRated(scope, 2026) { _, page, _ ->
            calls++
            MediaPage(emptyList(), true, page)
        }
        row.select(2026)
        assertEquals(5, calls)
        assertEquals(5, row.state.value.page)
        assertTrue(row.state.value.hasMore)
        row.loadMore()
        assertEquals(10, row.state.value.page)
    }

    @Test fun `paging failure retains cards and retries the same page`() {
        var fail = true
        val pages = mutableListOf<Int>()
        val row = YearTopRated(scope, 2026) { _, page, _ ->
            pages += page
            if (page == 2 && fail) error("offline")
            MediaPage(listOf(Media(page)), page < 2, page)
        }
        row.select(2026)
        row.loadMore()
        assertTrue(row.state.value.failed)
        assertEquals(listOf(1), row.state.value.items.map { it.id })
        fail = false
        row.loadMore()
        assertEquals(listOf(1, 2, 2), pages)
        assertEquals(listOf(1, 2), row.state.value.items.map { it.id })
        assertFalse(row.state.value.failed)
    }

    @Test fun `late response from an old year cannot overwrite a new selection`() = runBlocking {
        val old = CompletableDeferred<MediaPage>()
        val row = YearTopRated(scope, 2026) { year, _, _ ->
            if (year == 2026) withContext(NonCancellable) { old.await() }
            else MediaPage(listOf(Media(2025)), false, 1)
        }
        row.select(2026)
        row.select(2025)
        old.complete(MediaPage(listOf(Media(2026)), false, 1))
        assertEquals(2025, row.state.value.year)
        assertEquals(listOf(2025), row.state.value.items.map { it.id })
        assertFalse(row.state.value.loading)
    }

    @Test fun `repeated selection and paging during loading do not duplicate requests`() {
        val response = CompletableDeferred<MediaPage>()
        var calls = 0
        val row = YearTopRated(scope, 2026) { _, _, _ -> calls++; response.await() }
        row.select(2026)
        row.select(2026)
        row.loadMore()
        assertEquals(1, calls)
        response.complete(MediaPage(emptyList(), false, 1))
        assertTrue(row.state.value.items.isEmpty())
        assertFalse(row.state.value.hasMore)
    }

    @Test fun `refresh restarts the selected year with a forced request`() {
        val calls = mutableListOf<Triple<Int, Int, Boolean>>()
        val row = YearTopRated(scope, 2026) { year, page, force ->
            calls += Triple(year, page, force)
            MediaPage(listOf(Media(page)), true, page)
        }
        row.select(2025)
        row.loadMore()
        row.refresh()
        assertEquals(Triple(2025, 1, true), calls.last())
        assertEquals(listOf(1), row.state.value.items.map { it.id })
    }
}
