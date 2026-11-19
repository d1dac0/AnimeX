package com.anilili.ui.home

import com.anilili.data.model.Media
import com.anilili.data.model.MediaPage
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

data class YearTopRatedState(
    val year: Int = LocalDate.now().year,
    val items: List<Media> = emptyList(),
    val page: Int = 0,
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val failed: Boolean = false,
)

/** Independent from the startup catalogue: this lower row never delays the rest of Home. */
internal class YearTopRated(
    private val scope: CoroutineScope,
    private val currentYear: Int = LocalDate.now().year,
    private val fetch: suspend (year: Int, page: Int, force: Boolean) -> MediaPage,
) {
    private val mutable = MutableStateFlow(YearTopRatedState(year = currentYear))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var started = false

    fun select(year: Int) {
        if (year !in 1900..currentYear) return
        if (year == state.value.year && started) return
        reset(year, force = false)
    }

    fun refresh() {
        if (started) reset(state.value.year, force = true)
    }

    private fun reset(year: Int, force: Boolean) {
        generation++
        job?.cancel()
        started = true
        mutable.value = YearTopRatedState(year = year)
        loadMore(force)
    }

    fun loadMore(force: Boolean = false) {
        val before = state.value
        if (before.loading || !before.hasMore) return
        started = true
        val requestGeneration = generation
        mutable.value = before.copy(loading = true, failed = false)
        job = scope.launch {
            try {
                // Filtering by actual rating count can empty a server page. Scan past those,
                // but bound each user action so sparse years cannot monopolise the API.
                var result: MediaPage
                var next = before.page + 1
                var scanned = 0
                withTimeout(30_000) {
                    do {
                        result = fetch(before.year, next, force)
                        next = result.page + 1
                        scanned++
                    } while (result.items.isEmpty() && result.hasNextPage && scanned < 5)
                    if (requestGeneration == generation) {
                        mutable.value = before.copy(
                            items = (before.items + result.items).distinctBy(Media::id),
                            page = result.page,
                            hasMore = result.hasNextPage,
                            loading = false,
                            failed = false,
                        )
                    }
                }
            } catch (e: Exception) {
                // A timeout is retryable; cancellation from changing years must stay cancelled.
                if (e !is kotlinx.coroutines.TimeoutCancellationException) e.rethrowIfCancellation()
                if (requestGeneration == generation) {
                    mutable.value = before.copy(loading = false, failed = true)
                }
            }
        }
    }
}
