package com.anydown.downloader.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Progress channel between [DownloadService] and the UI.
 *
 * A process-wide singleton rather than a bound service or a database: downloads
 * are short-lived, there's at most a handful at a time, and nothing needs to
 * survive the process dying. If persistence across restarts is ever wanted,
 * this is the seam to replace.
 */
object DownloadBus {

    enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    data class Job(
        val id: String,
        val title: String,
        val label: String,
        val status: Status,
        val percent: Float = 0f,
        val etaSeconds: Long = -1,
        val message: String? = null,
        val filePath: String? = null,
    )

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    fun put(job: Job) = _jobs.update { current ->
        val index = current.indexOfFirst { it.id == job.id }
        if (index >= 0) current.toMutableList().apply { this[index] = job }
        else current + job
    }

    fun update(id: String, transform: (Job) -> Job) = _jobs.update { current ->
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) current
        else current.toMutableList().apply { this[index] = transform(this[index]) }
    }

    fun get(id: String): Job? = _jobs.value.firstOrNull { it.id == id }

    /** Drops finished entries; the active ones stay. */
    fun clearFinished() = _jobs.update { current ->
        current.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
    }
}
