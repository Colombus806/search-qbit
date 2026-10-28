package io.github.colombus806.searchqbit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.colombus806.searchqbit.AppContainer
import io.github.colombus806.searchqbit.data.ALL_CATEGORIES
import io.github.colombus806.searchqbit.data.CATEGORY_LABELS
import io.github.colombus806.searchqbit.data.SearchPlugin
import io.github.colombus806.searchqbit.data.SearchResult
import io.github.colombus806.searchqbit.data.SearchSink
import io.github.colombus806.searchqbit.data.TorrentTarget
import io.github.colombus806.searchqbit.data.appJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

enum class SortOrder(val label: String, val defaultDescending: Boolean) {
    SEEDERS("Seeders", true),
    SIZE("Size", true),
    NAME("Name", false),
}

data class SearchUiState(
    val pluginsLoaded: Boolean = false,
    val query: String = "",
    val category: String = ALL_CATEGORIES,
    /** Enabled plugins. */
    val plugins: List<SearchPlugin> = emptyList(),
    /** Empty = every enabled plugin. */
    val selectedPlugins: Set<String> = emptySet(),
    /** Results in arrival order; [visibleResults] is the sorted and filtered version. */
    val results: List<SearchResult> = emptyList(),
    val visibleResults: List<SearchResult> = emptyList(),
    val searched: Boolean = false,
    val running: Boolean = false,
    val sort: SortOrder = SortOrder.SEEDERS,
    val descending: Boolean = true,
    val filter: String = "",
    val error: String? = null,
    val pluginErrors: List<String> = emptyList(),
    /** Links (`fileUrl`) being prepared or already opened in LibreTorrent. */
    val opening: Set<String> = emptySet(),
    val opened: Set<String> = emptySet(),
) {
    private val chosenPlugins: List<SearchPlugin>
        get() = if (selectedPlugins.isEmpty()) plugins else plugins.filter { it.name in selectedPlugins }

    /** Categories supported by at least one chosen plugin, "all" first. */
    val categories: List<String>
        get() = CATEGORY_LABELS.keys.filter { id -> id == ALL_CATEGORIES || chosenPlugins.any { id in it.categories } }

    /** Plugins that will be queried for the chosen category. */
    val searchablePlugins: List<String>
        get() = chosenPlugins.filter { category in it.categories }.map { it.name }

    fun pluginLabel(name: String): String = plugins.find { it.name == name }?.fullName?.ifBlank { null } ?: name

    fun withValidCategory(): SearchUiState = if (category in categories) this else copy(category = ALL_CATEGORIES)
}

sealed interface SearchEvent {
    data class Message(val text: String) : SearchEvent
    data class Open(val target: TorrentTarget, val link: String) : SearchEvent
}

class SearchViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _events = Channel<SearchEvent>(Channel.BUFFERED)
    val events: Flow<SearchEvent> = _events.receiveAsFlow()

    private var searchJob: Job? = null
    private var currentSink: QueueSink? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            container.plugins.plugins.filterNotNull().collect { all ->
                val enabled = all.filter { it.enabled }
                val names = enabled.map { it.name }.toSet()
                update { state ->
                    state.copy(
                        pluginsLoaded = true,
                        plugins = enabled,
                        selectedPlugins = state.selectedPlugins.intersect(names),
                    ).withValidCategory()
                }
            }
        }
    }

    fun onQueryChange(query: String) = update { it.copy(query = query) }

    fun onCategoryChange(category: String) = update { it.copy(category = category) }

    fun togglePlugin(name: String) = update { state ->
        val selected = if (name in state.selectedPlugins) state.selectedPlugins - name else state.selectedPlugins + name
        state.copy(selectedPlugins = selected).withValidCategory()
    }

    fun selectAllPlugins() = update { it.copy(selectedPlugins = emptySet()).withValidCategory() }

    /** Tapping the active sort reverses its direction. */
    fun onSortChange(sort: SortOrder) = update { state ->
        if (state.sort == sort) state.copy(descending = !state.descending)
        else state.copy(sort = sort, descending = sort.defaultDescending)
    }

    fun onFilterChange(filter: String) = update { it.copy(filter = filter) }

    fun search() {
        val request = _state.value
        val pattern = request.query.trim()
        if (pattern.isEmpty()) return
        stop()
        val names = request.searchablePlugins
        if (names.isEmpty()) {
            val reason = if (request.plugins.isEmpty()) "No plugin enabled: install some in the Plugins tab."
            else "None of the chosen plugins supports the \"${CATEGORY_LABELS[request.category]}\" category."
            update { it.copy(searched = true, results = emptyList(), pluginErrors = emptyList(), error = reason) }
            return
        }
        val sink = QueueSink().also { currentSink = it }
        val current = ++generation
        searchJob = viewModelScope.launch {
            update {
                it.copy(
                    results = emptyList(), searched = true, running = true, error = null,
                    pluginErrors = emptyList(), opened = emptySet(),
                )
            }
            // Python blocks its thread until every plugin is done; the queue is drained regularly.
            launch(Dispatchers.IO) {
                try {
                    container.engine.search(names, pattern, request.category, sink)
                } catch (e: Exception) {
                    sink.onError("", e.message ?: "unknown error")
                } finally {
                    sink.finished = true
                }
            }
            try {
                while (true) {
                    val finished = sink.finished
                    flush(sink)
                    if (finished) break
                    delay(FLUSH_INTERVAL_MS)
                }
            } finally {
                if (current == generation) update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        currentSink?.cancelled = true
        currentSink = null
        searchJob?.cancel()
        update { it.copy(running = false) }
    }

    /** Prepares the link (or downloads the .torrent through the plugin), then asks the screen to open it. */
    fun open(result: SearchResult) {
        val link = result.fileUrl
        if (link.isBlank() || link in _state.value.opening) return
        update { it.copy(opening = it.opening + link) }
        viewModelScope.launch {
            try {
                val target = withContext(Dispatchers.IO) { container.torrents.prepare(result) }
                _events.send(SearchEvent.Open(target, link))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SearchEvent.Message("Could not get the torrent: ${e.message}"))
            } finally {
                update { it.copy(opening = it.opening - link) }
            }
        }
    }

    fun markOpened(link: String) = update { it.copy(opened = it.opened + link) }

    override fun onCleared() {
        currentSink?.cancelled = true
    }

    private fun flush(sink: QueueSink) {
        val results = generateSequence { sink.results.poll() }.toList()
        val errors = generateSequence { sink.errors.poll() }.toList()
        if (results.isEmpty() && errors.isEmpty()) return
        update { state ->
            val messages = errors.map { (plugin, message) ->
                if (plugin.isEmpty()) message else "${state.pluginLabel(plugin)}: $message"
            }
            state.copy(results = state.results + results, pluginErrors = state.pluginErrors + messages)
        }
    }

    private fun update(transform: (SearchUiState) -> SearchUiState) {
        _state.update { old ->
            val new = transform(old)
            val resort = new.results !== old.results || new.sort != old.sort ||
                new.descending != old.descending || new.filter != old.filter
            if (resort) new.copy(visibleResults = sortAndFilter(new.results, new.sort, new.descending, new.filter))
            else new
        }
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 300L
    }
}

/** Receives results from the Python threads; the ViewModel drains them in batches. */
private class QueueSink : SearchSink {
    val results = ConcurrentLinkedQueue<SearchResult>()
    val errors = ConcurrentLinkedQueue<Pair<String, String>>()

    @Volatile var cancelled = false
    @Volatile var finished = false

    override fun isCancelled(): Boolean = cancelled

    override fun onResult(plugin: String, json: String) {
        if (cancelled) return
        runCatching { appJson.decodeFromString<SearchResult>(json) }.getOrNull()
            ?.let { results += it.copy(engineName = plugin) }
    }

    override fun onError(plugin: String, message: String) {
        if (!cancelled) errors += plugin to message
    }
}

/** Local filter: every typed word must appear in the name (case-insensitive). */
internal fun sortAndFilter(
    results: List<SearchResult>,
    sort: SortOrder,
    descending: Boolean,
    filter: String,
): List<SearchResult> {
    val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
    val filtered = if (words.isEmpty()) results else results.filter { result ->
        val name = result.fileName.lowercase()
        words.all { it in name }
    }
    val comparator = when (sort) {
        SortOrder.SEEDERS -> compareBy<SearchResult> { it.nbSeeders }
        SortOrder.SIZE -> compareBy { it.fileSize }
        SortOrder.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }
    }
    return filtered.sortedWith(if (descending) comparator.reversed() else comparator)
}
