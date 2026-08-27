package com.anydown.downloader.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anydown.downloader.data.YtDlpSource
import com.anydown.downloader.domain.Errors
import com.anydown.downloader.domain.FormatPlanner
import com.anydown.downloader.domain.Platforms
import com.anydown.downloader.service.DownloadBus
import com.anydown.downloader.service.DownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Stage {
        data object Idle : Stage
        data object Resolving : Stage
        data class Ready(val media: YtDlpSource.Resolved) : Stage
    }

    data class UiState(
        val acknowledged: Boolean = false,
        val url: String = "",
        val stage: Stage = Stage.Idle,
        /** Carries yt-dlp's raw output so the UI can offer "show details". */
        val error: Errors.Classified? = null,
        val notice: String? = null,
        val engineReady: Boolean = false,
        /** False when ffmpeg didn't load: quality is capped and merges fail. */
        val canMerge: Boolean = false,
    )

    private val prefs = application.getSharedPreferences("anydown", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(acknowledged = prefs.getBoolean(KEY_ACK, false))
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var resolveJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { YtDlpSource.ensureInitialised(getApplication()) }
                .onSuccess {
                    _state.update {
                        it.copy(engineReady = true, canMerge = YtDlpSource.canMerge)
                    }
                }
                .onFailure { error -> _state.update { it.copy(error = classify(error)) } }
        }
    }

    fun acknowledge() {
        prefs.edit().putBoolean(KEY_ACK, true).apply()
        _state.update { it.copy(acknowledged = true) }
    }

    fun onUrlChange(url: String) {
        _state.update { it.copy(url = url, error = null) }
    }

    fun resolve() {
        val url = _state.value.url.trim()
        Platforms.rejectionReason(url)?.let { reason ->
            _state.update {
                it.copy(
                    stage = Stage.Idle,
                    error = Errors.Classified(Errors.Code.UNSUPPORTED_URL, reason),
                )
            }
            return
        }

        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            _state.update { it.copy(stage = Stage.Resolving, error = null, notice = null) }
            runCatching { YtDlpSource.resolve(getApplication(), url) }
                .onSuccess { media ->
                    _state.update {
                        it.copy(
                            stage = Stage.Ready(media),
                            engineReady = true,
                            canMerge = YtDlpSource.canMerge,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    _state.update { it.copy(stage = Stage.Idle, error = classify(error)) }
                }
        }
    }

    fun download(option: FormatPlanner.Option) {
        val current = _state.value
        val media = (current.stage as? Stage.Ready)?.media ?: return
        DownloadService.enqueue(
            context = getApplication(),
            url = current.url.trim(),
            title = media.title,
            option = option,
        )
        _state.update { it.copy(notice = "${option.label} queued — progress is below.") }
    }

    /** Updates the bundled yt-dlp, which is how broken extractors get fixed. */
    fun updateEngine() {
        viewModelScope.launch {
            _state.update { it.copy(notice = "Updating yt-dlp…", error = null) }
            runCatching { YtDlpSource.updateEngine(getApplication()) }
                .onSuccess { message -> _state.update { it.copy(notice = message) } }
                .onFailure { error ->
                    _state.update { it.copy(notice = null, error = classify(error)) }
                }
        }
    }

    fun clearFinishedJobs() = DownloadBus.clearFinished()

    fun dismissMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /** Keeps yt-dlp's raw text attached so the UI can reveal it on demand. */
    private fun classify(error: Throwable): Errors.Classified =
        (error as? YtDlpSource.SourceException)?.classified
            ?: Errors.classify(error.message)

    private companion object {
        const val KEY_ACK = "rights_acknowledged_v1"
    }
}
