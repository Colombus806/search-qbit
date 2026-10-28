# Search qBit

Torrent search **directly on an Android phone**, without a PC, using the same `.py` plugins as
qBittorrent's built-in search. One tap on a result opens it in **LibreTorrent**, which does the
download. A second tab finds the most downloaded **English subtitles** on OpenSubtitles.

The app ships no site and no plugin: you install your own and you are responsible for what you
download.

## How it works

- The app embeds Python 3.13 (Chaquopy) and runs qBittorrent plugins unchanged, with its own
  `helpers.py` and `novaprinter.py`, compatible with those of qBittorrent 4.x and 5.x.
- Every enabled plugin is queried in parallel; results appear as they arrive.
- **Open in LibreTorrent**: a magnet link is passed as is; for a `.torrent` link, the app downloads the
  file through the plugin (as qBittorrent does) and hands it to LibreTorrent. If LibreTorrent is not
  installed, Android offers another torrent app.
- **Copy link** (in a result's details): the magnet or `.torrent` URL, to paste elsewhere.

## Installing plugins

**Plugins › Install** tab:

- **From files**: "Choose .py files…", several at once. On a PC, qBittorrent keeps its plugins in
  `%LOCALAPPDATA%\qBittorrent\nova3\engines`; copy the `.py` files to the phone (USB cable or cloud),
  then import them.
- **From a URL**: the direct link to the `.py` file (on GitHub, the "Raw" button; a link to the GitHub
  page is converted automatically). The URL is kept for the update button (⟳).

A switch enables or disables each plugin; the bin removes it. The **Jackett** plugin cannot work here:
it queries a Jackett server running on a PC.

## Subtitles

The **Subtitles** tab searches [opensubtitles.com](https://www.opensubtitles.com) for English
subtitles, most downloaded first.

- Type the movie, with its year if you like (`Inception 2010`), or the episode (`The Office S02E03`,
  `Lost 4x12`). A pasted file name works too: `The.Office.US.S02E03.720p.WEB-DL` is understood.
- Tap a subtitle: the app downloads it, unpacks it if it comes as a `.zip` or `.gz`, then opens the
  Android file picker to choose the folder and the name (rename it like the video so the player loads
  it on its own).
- If you close the picker without saving, tap the subtitle again: it does not count as a new download.

### API key and quota

OpenSubtitles requires a free API key (see their
[Getting started](https://opensubtitles.stoplight.io/docs/opensubtitles-api/e3750fd63a100-getting-started)
page): create an account on opensubtitles.com (the current site, not .org), open **API consumers** in
your profile menu, create a consumer and copy its key into the tab's settings (⚙). Searching only
needs the key. Each download uses the daily quota, reset at midnight UTC: 5 without an account, 100
while the consumer is set to "Under Development", 20 with a free account whose username and password
are entered in the settings (kept in the app's private storage, on the phone only). The number of
downloads left appears after each download.

The key belongs to whoever builds the app: OpenSubtitles bans apps that make their users create keys,
so a build shared with other people should have its own key built in rather than this setting.

## Building the APK

### On a PC

You need the Android SDK (path in `local.properties`, e.g. `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`)
and **Python 3.13** (`py -3.13` on Windows), which Chaquopy uses during the build.

```bat
gradlew.bat assembleDebug testDebugUnitTest
py -3.13 -m unittest discover -s app/src/test/python -t app/src/test/python
```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk` (about 33 MB, Python included).

### With GitHub Actions

Every push runs the "Build APK" workflow (it can also be started from the **Actions** tab with
"Run workflow"). When it is green, download the **search-qbit-debug** artifact: a zip containing the
APK.

## Installing the APK

- **With a cable** (USB debugging on): `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- **Without a cable**: copy the APK to the phone, open it from Files and allow "Install unknown apps"
  when Android asks.

This is a debug APK signed with a development key: fine for personal use. It only contains the 64-bit
ARM version of Python (every recent phone).

## Troubleshooting

| Message | Likely cause |
| --- | --- |
| cannot connect to … (Connection refused) | site blocked by your internet provider's DNS (it answers 127.0.0.1); qBittorrent on a PC fails the same way |
| cannot connect to … (Forbidden) | the site rejects automated requests |
| is not a valid search plugin | not a qBittorrent plugin, or its class is not named after the file |
| This link returns a web page | link to the GitHub page instead of the raw file |
| the site returned a web page instead of a .torrent file | protected `.torrent` link; try "Copy link" or another result |
| OpenSubtitles refused the API key | key mistyped, or the consumer was deleted |
| You have downloaded your allowed … subtitles | daily quota used up; enter your account in the settings for a larger one |
| OpenSubtitles refused the sign-in | wrong username or password, or downloads without login switched off on the key |

## Checks to run on the phone

1. Import plugins with "Choose .py files…": they appear with their version and categories.
2. A search shows results arriving live, sorted by seeders.
3. The Size and Name sorts, and the filter, reorder the list without searching again.
4. "Open in LibreTorrent" on a magnet link opens LibreTorrent's "Add torrent" screen.
5. Same on a ".torrent file" result (see a result's details).
6. Disabling a plugin removes it from the plugin choice in the Search tab.
7. After closing and reopening the app, plugins and their state are kept.
8. Subtitles: with the API key entered, `Inception 2010` lists English subtitles, the most downloaded
   on top; tapping one opens the file picker and the saved `.srt` opens in a video player.
9. `The Office S02E03` only lists subtitles for that episode.

## Layout

```text
app/src/main/python/
  engine.py                   runs plugins in parallel, metadata, .torrent downloads
  helpers.py, novaprinter.py  equivalents of qBittorrent's modules used by plugins
app/src/main/java/io/github/colombus806/searchqbit/
  data/PythonEngine.kt        Kotlin <-> Python bridge (Chaquopy)
  data/PluginRepository.kt    installs, enables, updates and removes plugins
  data/TorrentFiles.kt        prepares the magnet link or .torrent file for LibreTorrent
  data/OpenSubtitles.kt       opensubtitles.com client: query parsing, search, sign-in, download
  data/SubtitleArchive.kt     unpacks .zip and .gz subtitles
  ui/SearchViewModel.kt       search, batched results, sorting and filtering
  ui/SubtitlesViewModel.kt    subtitle search, download, then save where the user chooses
  ui/SearchScreen.kt, ui/SubtitlesScreen.kt, ui/PluginsScreen.kt
app/src/test/                 Kotlin (JVM) and Python (plugin engine) tests
.github/workflows/build.yml
```

Versions: Kotlin 2.4, Android Gradle Plugin 9.4, Gradle 9.8, Chaquopy 17.2 (Python 3.13), Compose BOM
2026.09, minSdk 26 (Android 8), compileSdk and targetSdk 37.
