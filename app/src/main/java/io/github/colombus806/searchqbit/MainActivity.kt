package io.github.colombus806.searchqbit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.colombus806.searchqbit.ui.AppIcons
import io.github.colombus806.searchqbit.ui.PluginsScreen
import io.github.colombus806.searchqbit.ui.PluginsViewModel
import io.github.colombus806.searchqbit.ui.SearchScreen
import io.github.colombus806.searchqbit.ui.SearchViewModel
import io.github.colombus806.searchqbit.ui.SubtitlesScreen
import io.github.colombus806.searchqbit.ui.SubtitlesViewModel
import io.github.colombus806.searchqbit.ui.theme.SearchQBitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as QbitApp).container
        setContent { SearchQBitTheme { MainScreen(container) } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    SEARCH("Search", Icons.Filled.Search),
    SUBTITLES("Subtitles", AppIcons.Subtitles),
    PLUGINS("Plugins", Icons.AutoMirrored.Filled.List),
}

@Composable
private fun MainScreen(container: AppContainer) {
    val searchViewModel = viewModel { SearchViewModel(container) }
    val subtitlesViewModel = viewModel { SubtitlesViewModel(container) }
    val pluginsViewModel = viewModel { PluginsViewModel(container) }
    var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.SEARCH -> SearchScreen(searchViewModel, onOpenPlugins = { tab = Tab.PLUGINS })
                Tab.SUBTITLES -> SubtitlesScreen(subtitlesViewModel)
                Tab.PLUGINS -> PluginsScreen(pluginsViewModel)
            }
        }
    }
}
