"""Runs qBittorrent search plugins (nova3 format) on the phone.

Called from Kotlin (PythonEngine.kt). Plugins are the <name>.py files in <nova_dir>/engines, imported
as "engines.<name>", whose class is named after the file, exactly as in qBittorrent.
"""

import contextlib
import importlib
import io
import json
import os
import re
import socket
import sys
import tempfile
import threading
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

import helpers
import novaprinter

CATEGORIES = ("all", "anime", "books", "games", "movies", "music", "pictures", "software", "tv")
MAX_PARALLEL_PLUGINS = 8

_engines_dir = ""
_import_lock = threading.Lock()


def init(nova_dir, temp_dir):
    global _engines_dir
    _engines_dir = os.path.join(nova_dir, "engines")
    os.makedirs(_engines_dir, exist_ok=True)
    init_file = os.path.join(_engines_dir, "__init__.py")
    if not os.path.exists(init_file):
        open(init_file, "w").close()
    if nova_dir not in sys.path:
        sys.path.insert(0, nova_dir)
    os.makedirs(temp_dir, exist_ok=True)
    tempfile.tempdir = temp_dir
    socket.setdefaulttimeout(20)


def info(name):
    """Plugin metadata as JSON. Raises if the file is not a valid plugin."""
    plugin = _plugin_class(name, reload=True)
    for attribute in ("name", "url"):
        if not hasattr(plugin, attribute):
            raise ValueError(f'missing "{attribute}" attribute')
    if not callable(getattr(plugin, "search", None)):
        raise ValueError("missing search() method")
    supported = getattr(plugin, "supported_categories", None)
    return json.dumps({
        "name": name,
        "fullName": str(plugin.name),
        "url": str(plugin.url),
        "version": _version(name),
        "categories": [c for c in CATEGORIES if supported is None or c in supported],
    })


def search(names, query, category, sink):
    """Runs the plugins in parallel and returns once they have all finished.

    sink is a Kotlin object (SearchSink): isCancelled(), onResult(plugin, json), onError(plugin, message).
    """
    names = [str(name) for name in names]
    if not names:
        return
    what = urllib.parse.quote(query)
    with ThreadPoolExecutor(max_workers=min(len(names), MAX_PARALLEL_PLUGINS)) as pool:
        list(pool.map(lambda name: _run(name, what, category, sink), names))


def _run(name, what, category, sink):
    context = novaprinter.Context(
        on_result=lambda result: sink.onResult(name, json.dumps(result)),
        is_cancelled=sink.isCancelled,
    )
    novaprinter.set_context(context)
    try:
        plugin = _plugin_class(name)()
        supported = getattr(plugin, "supported_categories", None)
        # Same rule as nova2.py: a plugin that does not support the category is not queried.
        if supported is None:
            plugin.search(what)
        elif category in supported:
            plugin.search(what, category)
        else:
            return
        if context.count == 0 and context.errors and not sink.isCancelled():
            sink.onError(name, context.errors[0])
    except novaprinter.SearchCancelled:
        pass
    except Exception as error:
        if not sink.isCancelled():
            sink.onError(name, _describe(error))
    finally:
        novaprinter.set_context(None)


def download(name, url):
    """Returns a magnet link, or the path of the .torrent file fetched by the plugin."""
    if url.startswith("magnet:"):
        return url
    plugin = _plugin_class(name)()
    if hasattr(plugin, "download_torrent"):
        # As in nova2dl.py: the plugin prints "path url" (or "magnet magnet") to standard output.
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            plugin.download_torrent(url)
        lines = [line.strip() for line in output.getvalue().splitlines() if line.strip()]
        if not lines:
            raise RuntimeError("the plugin returned no file")
        line = lines[-1]
    else:
        line = helpers.download_file(url)
    target = line.split(" ", 1)[0]
    if target.startswith("magnet:") or os.path.isfile(target):
        return target
    raise RuntimeError(line[:200])


def _plugin_class(name, reload=False):
    module_name = "engines." + name
    with _import_lock:
        if reload:
            importlib.invalidate_caches()
            sys.modules.pop(module_name, None)
        module = importlib.import_module(module_name)
    plugin = getattr(module, name, None)
    if plugin is None:
        raise ValueError(f'no "{name}" class in the file (it must be named after the file)')
    return plugin


_VERSION_LINE = re.compile(r"^#\s*VERSION:\s*(\S+)", re.IGNORECASE)


def _version(name):
    """Reads "#VERSION: 1.2" or "# VERSION: 1.2" at the top of the file, as qBittorrent does."""
    try:
        with open(os.path.join(_engines_dir, name + ".py"), encoding="utf-8", errors="replace") as file:
            for _ in range(5):
                match = _VERSION_LINE.match(file.readline())
                if match:
                    return match.group(1)
    except OSError:
        pass
    return ""


def _describe(error):
    return f"{type(error).__name__}: {error}"[:300]
