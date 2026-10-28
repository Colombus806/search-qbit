package io.github.colombus806.searchqbit

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.util.Log
import io.github.colombus806.searchqbit.data.OpenSubtitles
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
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val contentResolver: ContentResolver = context.contentResolver
    val engine = PythonEngine(context)
    val plugins = PluginRepository(
        engine = engine,
        stateFile = File(context.filesDir, "plugins.json"),
        http = http,
        contentResolver = contentResolver,
    )
    val torrents = TorrentFiles(context, engine)
    val subtitles = OpenSubtitles(
        http = http,
        accountFile = File(context.filesDir, "opensubtitles.json"),
        userAgent = "SearchQBit v${BuildConfig.VERSION_NAME}",
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Loads the saved settings, then starts Python ahead of time so the first search is fast.
        appScope.launch {
            subtitles.load()
            plugins.load()
            runCatching { engine.warmUp() }.onFailure { Log.e("SearchQBit", "Python failed to start", it) }
        }
    }
}
