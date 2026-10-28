package io.github.colombus806.searchqbit.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.colombus806.searchqbit.AppContainer
import io.github.colombus806.searchqbit.data.DownloadedSubtitle
import io.github.colombus806.searchqbit.data.OpenSubtitlesAccount
import io.github.colombus806.searchqbit.data.Subtitle
import io.github.colombus806.searchqbit.data.SubtitleFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class SubtitlesUiState(
    /** `null` until the settings are loaded. */
    val account: OpenSubtitlesAccount? = null,
    val query: String = "",
    /** Most downloaded first. */
    val results: List<Subtitle> = emptyList(),
    val searched: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
    /** File ids being downloaded, or already saved. */
    val downloading: Set<Long> = emptySet(),
    val saved: Set<Long> = emptySet(),
    /** Downloads left today, after the first download. */
    val quota: String? = null,
)

sealed interface SubtitlesEvent {
    data class Message(val text: String) : SubtitlesEvent

    /** The screen opens the file picker, then calls [SubtitlesViewModel.save] with the chosen place. */
    data class ChooseDestination(val fileName: String) : SubtitlesEvent
}

class SubtitlesViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SubtitlesUiState())
    val state: StateFlow<SubtitlesUiState> = _state.asStateFlow()

    private val _events = Channel<SubtitlesEvent>(Channel.BUFFERED)
    val events: Flow<SubtitlesEvent> = _events.receiveAsFlow()

    private var searchJob: Job? = null
    private var generation = 0

    /** Files already downloaded: saving one again elsewhere does not use the quota again. */
    private val downloaded = mutableMapOf<Long, SubtitleFile>()

    /** The file waiting for the user to choose where to save it. */
    private var pending: Long? = null

    init {
        viewModelScope.launch {
            container.subtitles.account.collect { account -> _state.update { it.copy(account = account) } }
        }
    }

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    fun search() {
        val text = _state.value.query.trim()
        if (text.isEmpty()) return
        searchJob?.cancel()
        val current = ++generation
        searchJob = viewModelScope.launch {
            _state.update { it.copy(results = emptyList(), searched = true, running = true, error = null, saved = emptySet()) }
            try {
                val results = container.subtitles.search(text)
                _state.update { it.copy(results = results) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "unknown error") }
            } finally {
                if (current == generation) _state.update { it.copy(running = false) }
            }
        }
    }

    /** Downloads (and unpacks) the subtitle, then asks where to save it. One at a time: each opens the file picker. */
    fun download(subtitle: Subtitle) {
        val id = subtitle.fileId
        if (_state.value.downloading.isNotEmpty()) return
        downloaded[id]?.let { return chooseDestination(id, it) }
        _state.update { it.copy(downloading = it.downloading + id) }
        viewModelScope.launch {
            try {
                val result = container.subtitles.download(subtitle)
                downloaded[id] = result.file
                _state.update { it.copy(quota = quotaText(result) ?: it.quota) }
                chooseDestination(id, result.file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SubtitlesEvent.Message("Download failed: ${e.message}"))
            } finally {
                _state.update { it.copy(downloading = it.downloading - id) }
            }
        }
    }

    /** [uri] is `null` when the file picker was closed without choosing a place. */
    fun save(uri: Uri?) {
        val id = pending ?: return
        pending = null
        val file = downloaded[id] ?: return
        if (uri == null) {
            _events.trySend(SubtitlesEvent.Message("Not saved. Tap it again to choose a folder: it will not count as a new download."))
            return
        }
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    val resolver = container.contentResolver
                    resolver.openOutputStream(uri)?.use { it.write(file.bytes) } ?: throw IOException("cannot write there")
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: file.name
                }
                _state.update { it.copy(saved = it.saved + id) }
                _events.send(SubtitlesEvent.Message("Saved: $name"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SubtitlesEvent.Message("Could not save the file: ${e.message}"))
            }
        }
    }

    fun saveAccount(account: OpenSubtitlesAccount) {
        viewModelScope.launch {
            container.subtitles.saveAccount(account)
            _state.update { it.copy(quota = null) }
            _events.send(SubtitlesEvent.Message("Settings saved."))
        }
    }

    private fun chooseDestination(id: Long, file: SubtitleFile) {
        pending = id
        _events.trySend(SubtitlesEvent.ChooseDestination(file.name))
    }
}

private fun quotaText(result: DownloadedSubtitle): String? {
    val remaining = result.remaining?.coerceAtLeast(0) ?: return null
    val left = if (remaining == 1) "1 download left" else "$remaining downloads left"
    return if (result.resetTime.isBlank()) "$left today" else "$left · renewed in ${result.resetTime.trim()}"
}
