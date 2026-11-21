package com.anilili.data.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Encodes and writes the store's JSON lists off the caller's thread (#65).
 *
 * The playback position poll persists the whole history every few seconds from the main
 * thread; the encode was the expensive half. The caller keeps its in-memory state
 * synchronous and hands this queue only the latest list. One in-flight write per key: a
 * newer call cancels the older one's job before it has encoded, so a fresh list is never
 * queued behind a stale one, and because encode and write run under one lock the last
 * write accepted for a key is always the one that lands (LibraryPersistQueueTest).
 */
internal class LibraryPersistQueue(
    private val scope: CoroutineScope,
    private val write: (key: String, encoded: String) -> Unit,
) {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    /**
     * Schedule an encode + write for [key]. Returns as soon as the job is registered, so
     * [flush] called after the last persist always sees it. Supersedes an older call for
     * the same key; other keys are independent.
     */
    fun persist(key: String, encode: () -> String) {
        synchronized(lock) {
            jobs[key]?.cancel()
            // Lazy start under the same lock, so the job body cannot run before it is
            // registered — a flush taken after persist() returns cannot miss it.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                synchronized(lock) {
                    ensureActive()
                    write(key, encode())
                }
            }
            jobs[key] = job
            job.start()
        }
    }

    /** Wait for every accepted write to have reached [write]. Cancelled jobs return at once. */
    suspend fun flush() {
        val snapshot = synchronized(lock) { jobs.values.toList() }
        snapshot.forEach { job -> runCatching { job.join() } }
    }
}
