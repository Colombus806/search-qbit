package io.github.colombus806.searchqbit.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.colombus806.searchqbit.AppContainer
import io.github.colombus806.searchqbit.data.CATEGORY_LABELS
import io.github.colombus806.searchqbit.data.SearchPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginsUiState(
    /** `null` until the list is loaded. */
    val plugins: List<SearchPlugin>? = null,
    val busy: Boolean = false,
)

class PluginsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            container.plugins.plugins.collect { plugins -> _state.update { it.copy(plugins = plugins) } }
        }
    }

    fun installFromUrl(url: String) = launchAction("Installing…") {
        val plugin = container.plugins.installFromUrl(url)
        "Plugin installed: ${plugin.fullName} ${plugin.version}".trim()
    }

    /** Several files at once, for example every .py copied from the PC. */
    fun installFromFiles(uris: List<Uri>) = launchAction("Installing…") {
        val installed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (uri in uris) {
            try {
                installed += container.plugins.installFromDocument(uri).fullName
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += e.message ?: "unknown error"
            }
        }
        listOfNotNull(
            installed.takeIf { it.isNotEmpty() }?.let { "Installed: ${it.joinToString()}." },
            failed.takeIf { it.isNotEmpty() }?.let { "Failed: ${it.joinToString("; ")}" },
        ).joinToString(" ")
    }

    fun updateAll() = launchAction("Updating…") { container.plugins.updateAll() }

    fun setEnabled(plugin: SearchPlugin, enabled: Boolean) {
        viewModelScope.launch { container.plugins.setEnabled(plugin.name, enabled) }
    }

    fun remove(plugin: SearchPlugin) = launchAction(null) {
        container.plugins.remove(plugin.name)
        "Plugin removed: ${plugin.fullName.ifBlank { plugin.name }}"
    }

    private fun launchAction(progress: String?, action: suspend () -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                if (progress != null) _messages.send(progress)
                _messages.send(action())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.message ?: "Unknown error")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(viewModel: PluginsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var installDialog by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<SearchPlugin?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.installFromFiles(uris)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(message) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plugins") },
                actions = {
                    IconButton(onClick = viewModel::updateAll, enabled = !state.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Update plugins")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!state.busy) installDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Install") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val plugins = state.plugins
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp)) {
                item {
                    Text(
                        "Same .py files as qBittorrent's search plugins. The app ships none: you choose " +
                            "your own and you are responsible for what you download.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plugins != null && plugins.isEmpty()) {
                    item { Text("No plugin installed.", Modifier.padding(16.dp)) }
                }
                items(plugins.orEmpty(), key = { it.name }) { plugin ->
                    PluginRow(
                        plugin = plugin,
                        onEnabledChange = { viewModel.setEnabled(plugin, it) },
                        onRemove = { toRemove = plugin },
                    )
                }
            }
        }
    }

    if (installDialog) {
        InstallDialog(
            onInstallUrl = {
                viewModel.installFromUrl(it)
                installDialog = false
            },
            onPickFile = {
                installDialog = false
                pickFile.launch(arrayOf("*/*"))
            },
            onDismiss = { installDialog = false },
        )
    }
    toRemove?.let { plugin ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remove \"${plugin.fullName.ifBlank { plugin.name }}\"?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.remove(plugin)
                    toRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PluginRow(plugin: SearchPlugin, onEnabledChange: (Boolean) -> Unit, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(plugin.fullName.ifBlank { plugin.name }) },
        supportingContent = {
            val categories = plugin.categories.filter { it != "all" }.mapNotNull { CATEGORY_LABELS[it] }
            val details = listOfNotNull(
                plugin.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                plugin.url.takeIf { it.isNotBlank() },
                categories.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = plugin.enabled, onCheckedChange = onEnabledChange)
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
            }
        },
    )
    HorizontalDivider()
}

@Composable
private fun InstallDialog(onInstallUrl: (String) -> Unit, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install a plugin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Link to the .py file (on GitHub, the \"Raw\" link), or .py files stored on the phone. " +
                        "Check the source: the plugin runs inside the app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://…/plugin.py") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedButton(onClick = onPickFile) { Text("Choose .py files…") }
            }
        },
        confirmButton = {
            Button(onClick = { onInstallUrl(url) }, enabled = url.isNotBlank()) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
