package io.github.colombus806.searchqbit.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.colombus806.searchqbit.data.CATEGORY_LABELS
import io.github.colombus806.searchqbit.data.SearchResult
import io.github.colombus806.searchqbit.data.TorrentTarget
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val LIBRETORRENT = "org.proninyaroslav.libretorrent"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel, onOpenPlugins: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var details by remember { mutableStateOf<SearchResult?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchEvent.Message -> launch { snackbar.showSnackbar(event.text) }
                is SearchEvent.Open ->
                    if (openInTorrentApp(context, event.target)) viewModel.markOpened(event.link)
                    else launch { snackbar.showSnackbar("No app can open this link: install LibreTorrent.") }
            }
        }
    }
    val copyLink: (SearchResult) -> Unit = { result ->
        copyToClipboard(context, result.fileUrl)
        // Android 13+ already shows its own confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar("Link copied") }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Search") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.pluginsLoaded && state.plugins.isEmpty()) {
                NoPlugins(onOpenPlugins)
                return@Column
            }
            QueryField(state, viewModel)
            SearchOptions(state, viewModel)
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            state.error?.let { ErrorCard(it) }
            if (state.searched) ResultsHeader(state, viewModel)
            LazyColumn(Modifier.weight(1f)) {
                items(state.visibleResults) { result ->
                    ResultRow(
                        result = result,
                        pluginLabel = state.pluginLabel(result.engineName),
                        opening = result.fileUrl in state.opening,
                        opened = result.fileUrl in state.opened,
                        onOpen = { viewModel.open(result) },
                        onClick = { details = result },
                    )
                }
                if (state.searched && !state.running && state.results.isEmpty() && state.error == null) {
                    item { Hint("No results.") }
                }
                if (state.pluginErrors.isNotEmpty()) {
                    item { PluginErrors(state.pluginErrors) }
                }
            }
        }
    }

    details?.let { result ->
        ResultDialog(
            result = result,
            pluginLabel = state.pluginLabel(result.engineName),
            opening = result.fileUrl in state.opening,
            onOpen = { viewModel.open(result) },
            onCopy = { copyLink(result) },
            onDismiss = { details = null },
        )
    }
}

@Composable
private fun QueryField(state: SearchUiState, viewModel: SearchViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val launch = {
        keyboard?.hide()
        viewModel.search()
    }
    OutlinedTextField(
        value = state.query,
        onValueChange = viewModel::onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        label = { Text("Keywords") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { launch() }),
        trailingIcon = {
            if (state.running) {
                IconButton(onClick = viewModel::stop) { Icon(Icons.Filled.Close, contentDescription = "Stop") }
            } else {
                IconButton(onClick = launch, enabled = state.query.isNotBlank()) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            }
        },
    )
}

@Composable
private fun SearchOptions(state: SearchUiState, viewModel: SearchViewModel) {
    var categoryMenu by remember { mutableStateOf(false) }
    var pluginMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            FilterChip(
                selected = false,
                onClick = { categoryMenu = true },
                label = { Text("Category: ${CATEGORY_LABELS[state.category]}") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                state.categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(CATEGORY_LABELS[category] ?: category) },
                        onClick = {
                            viewModel.onCategoryChange(category)
                            categoryMenu = false
                        },
                        leadingIcon = {
                            if (category == state.category) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                    )
                }
            }
        }
        Box {
            val label = if (state.selectedPlugins.isEmpty()) "all" else state.selectedPlugins.size.toString()
            FilterChip(
                selected = false,
                onClick = { pluginMenu = true },
                label = { Text("Plugins: $label") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = pluginMenu, onDismissRequest = { pluginMenu = false }) {
                DropdownMenuItem(
                    text = { Text("All enabled plugins") },
                    onClick = {
                        viewModel.selectAllPlugins()
                        pluginMenu = false
                    },
                    leadingIcon = { Checkbox(checked = state.selectedPlugins.isEmpty(), onCheckedChange = null) },
                )
                state.plugins.forEach { plugin ->
                    DropdownMenuItem(
                        text = { Text(plugin.fullName.ifBlank { plugin.name }) },
                        onClick = { viewModel.togglePlugin(plugin.name) },
                        leadingIcon = { Checkbox(checked = plugin.name in state.selectedPlugins, onCheckedChange = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsHeader(state: SearchUiState, viewModel: SearchViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        val count = state.results.size
        val status = buildString {
            append(if (state.running) "Searching… " else "")
            append(if (count == 1) "1 result" else "$count results")
            if (state.filter.isNotBlank()) append(" · ${state.visibleResults.size} shown")
        }
        Text(status, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortOrder.entries.forEach { sort ->
                val selected = sort == state.sort
                val arrow = if (!selected) "" else if (state.descending) " ↓" else " ↑"
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.onSortChange(sort) },
                    label = { Text(sort.label + arrow) },
                )
            }
        }
        OutlinedTextField(
            value = state.filter,
            onValueChange = viewModel::onFilterChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Filter results (e.g. 1080p)") },
            singleLine = true,
            trailingIcon = {
                if (state.filter.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onFilterChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear filter")
                    }
                }
            },
        )
    }
}

@Composable
private fun ResultRow(
    result: SearchResult,
    pluginLabel: String,
    opening: Boolean,
    opened: Boolean,
    onOpen: () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(result.fileName, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val details = "${formatSize(result.fileSize)} · S ${formatCount(result.nbSeeders)} · " +
                "L ${formatCount(result.nbLeechers)} · $pluginLabel"
            Text(details, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when {
                    opening -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    opened -> Icon(Icons.Filled.Check, contentDescription = "Opened", tint = MaterialTheme.colorScheme.primary)
                    else -> FilledTonalIconButton(onClick = onOpen) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Open in LibreTorrent")
                    }
                }
            }
        },
    )
    HorizontalDivider()
}

@Composable
private fun ResultDialog(
    result: SearchResult,
    pluginLabel: String,
    opening: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result.fileName, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Detail("Size", formatSize(result.fileSize))
                Detail("Seeders", formatCount(result.nbSeeders))
                Detail("Leechers", formatCount(result.nbLeechers))
                Detail("Plugin", pluginLabel)
                if (result.pubDate > 0) Detail("Published", formatDate(result.pubDate))
                Detail("Type", if (result.fileUrl.startsWith("magnet:")) "magnet link" else ".torrent file")
                Row {
                    TextButton(onClick = onCopy) { Text("Copy link") }
                    if (result.descrLink.isNotBlank()) {
                        TextButton(onClick = { runCatching { uriHandler.openUri(result.descrLink) } }) {
                            Text("Site page")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onOpen()
                    onDismiss()
                },
                enabled = !opening,
            ) { Text("Open in LibreTorrent") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NoPlugins(onOpenPlugins: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No search plugin enabled. Install the .py files of your qBittorrent plugins.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onOpenPlugins) { Text("Manage plugins") }
    }
}

@Composable
private fun PluginErrors(errors: List<String>) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Failed plugins", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Opens the link in LibreTorrent, or failing that in any installed torrent app. */
private fun openInTorrentApp(context: Context, target: TorrentTarget): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (target.mimeType != null) setDataAndType(target.uri, target.mimeType) else data = target.uri
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(Intent(intent).setPackage(LIBRETORRENT))
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Torrent link", text))
}

internal fun formatSize(bytes: Long): String {
    if (bytes < 0) return "unknown size"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

private fun formatCount(count: Long): String = if (count < 0) "?" else count.toString()

private fun formatDate(epochSeconds: Long): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
        .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
