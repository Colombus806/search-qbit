package io.github.colombus806.searchqbit

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.colombus806.searchqbit.data.PluginRepository
import io.github.colombus806.searchqbit.data.PythonEngine
import io.github.colombus806.searchqbit.data.TorrentFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class QbitApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Dependencies shared by the screens. */
class AppContainer(context: Context) {
    val engine = PythonEngine(context)
    val plugins = PluginRepository(
        engine = engine,
        stateFile = File(context.filesDir, "plugins.json"),
        http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
        contentResolver = context.contentResolver,
    )
    val torrents = TorrentFiles(context, engine)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Loads the plugin list, then starts Python ahead of time so the first search is fast.
        appScope.launch {
            plugins.load()
            runCatching { engine.warmUp() }.onFailure { Log.e("SearchQBit", "Python failed to start", it) }
        }
    }
}
