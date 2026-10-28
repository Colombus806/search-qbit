package io.github.colombus806.searchqbit.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.colombus806.searchqbit.data.OpenSubtitlesAccount
import io.github.colombus806.searchqbit.data.Subtitle
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private const val API_KEYS_PAGE = "https://www.opensubtitles.com/en/consumers"
private const val SUBRIP_MIME_TYPE = "application/x-subrip"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitlesScreen(viewModel: SubtitlesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var settings by remember { mutableStateOf(false) }
    val chooseDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SUBRIP_MIME_TYPE)) { uri ->
        viewModel.save(uri)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel) {
        // A download that ends while the app is in the background opens the picker on return.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is SubtitlesEvent.Message -> {
                        snackbar.currentSnackbarData?.dismiss()
                        launch { snackbar.showSnackbar(event.text) }
                    }
                    is SubtitlesEvent.ChooseDestination -> chooseDestination.launch(event.fileName)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subtitles") },
                actions = {
                    IconButton(onClick = { settings = true }, enabled = state.account != null) {
                        Icon(Icons.Filled.Settings, contentDescription = "OpenSubtitles settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val account = state.account
            if (account != null && account.apiKey.isBlank()) {
                NoApiKey(onOpenSettings = { settings = true })
                return@Column
            }
            QueryField(state, viewModel)
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            state.error?.let { ErrorCard(it) }
            if (state.searched && !state.running && state.error == null) ResultsHeader(state)
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(state.results, key = { _, subtitle -> subtitle.fileId }) { index, subtitle ->
                    SubtitleRow(
                        subtitle = subtitle,
                        best = index == 0,
                        downloading = subtitle.fileId in state.downloading,
                        saved = subtitle.fileId in state.saved,
                        onDownload = { viewModel.download(subtitle) },
                    )
                }
                if (!state.searched) {
                    item {
                        Hint(
                            "English subtitles from OpenSubtitles, most downloaded first. For an episode, add " +
                                "the season and episode: \"The Office S02E03\". Tap a subtitle to download it, then " +
                                "choose where to save it.",
                        )
                    }
                }
            }
        }
    }

    val account = state.account
    if (settings && account != null) {
        SettingsDialog(
            account = account,
            onSave = {
                viewModel.saveAccount(it)
                settings = false
            },
            onDismiss = { settings = false },
        )
    }
}

@Composable
private fun QueryField(state: SubtitlesUiState, viewModel: SubtitlesViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val launch = {
        keyboard?.hide()
        viewModel.search()
    }
    OutlinedTextField(
        value = state.query,
        onValueChange = viewModel::onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        label = { Text("Movie or episode") },
        placeholder = { Text("Inception 2010, The Office S02E03…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { launch() }),
        trailingIcon = {
            IconButton(onClick = launch, enabled = state.query.isNotBlank() && !state.running) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        },
    )
}

@Composable
private fun ResultsHeader(state: SubtitlesUiState) {
    val count = state.results.size
    val status = when (count) {
        0 -> "No English subtitles found: check the spelling, or try without the year."
        1 -> "1 subtitle"
        else -> "$count subtitles"
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(status, style = MaterialTheme.typography.labelLarge)
        state.quota?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubtitleRow(
    subtitle: Subtitle,
    best: Boolean,
    downloading: Boolean,
    saved: Boolean,
    onDownload: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = !downloading, onClick = onDownload),
        overlineContent = if (best) {
            { Text("Most downloaded", color = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        headlineContent = { Text(subtitle.release, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val details = listOfNotNull(
                subtitle.feature,
                "${NumberFormat.getIntegerInstance(Locale.US).format(subtitle.downloads)} downloads",
                "hearing impaired".takeIf { subtitle.hearingImpaired },
                "trusted".takeIf { subtitle.trusted },
                "AI translated".takeIf { subtitle.aiTranslated },
                "${subtitle.fileCount} files, first one only".takeIf { subtitle.fileCount > 1 },
            ).joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when {
                    downloading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    saved -> Icon(Icons.Filled.Check, contentDescription = "Saved", tint = MaterialTheme.colorScheme.primary)
                    else -> FilledTonalIconButton(onClick = onDownload) {
                        Icon(AppIcons.Download, contentDescription = "Download")
                    }
                }
            }
        },
    )
    HorizontalDivider()
}

@Composable
private fun NoApiKey(onOpenSettings: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Subtitles come from opensubtitles.com, which requires a free API key: create an account there, " +
                "then open \"API consumers\" in your profile and create one.",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(onClick = { runCatching { uriHandler.openUri(API_KEYS_PAGE) } }) { Text("Open opensubtitles.com") }
        Button(onClick = onOpenSettings) { Text("Enter the API key") }
    }
}

@Composable
private fun SettingsDialog(
    account: OpenSubtitlesAccount,
    onSave: (OpenSubtitlesAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var apiKey by remember { mutableStateOf(account.apiKey) }
    var username by remember { mutableStateOf(account.username) }
    var password by remember { mutableStateOf(account.password) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenSubtitles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "API key: free, in your opensubtitles.com profile under \"API consumers\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { runCatching { uriHandler.openUri(API_KEYS_PAGE) } }) { Text("Open the API keys page") }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key") },
                    singleLine = true,
                )
                Card {
                    Text(
                        "Optional: without an account, OpenSubtitles allows 5 downloads per day, if \"Allow " +
                            "anonymous downloads\" is checked on your API key. Signing in raises that limit. " +
                            "Kept only on this phone.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(OpenSubtitlesAccount(apiKey, username, password)) },
                enabled = apiKey.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
