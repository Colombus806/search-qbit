"""Replacement for qBittorrent's novaprinter.py: plugins hand their results to prettyPrinter().

In qBittorrent, prettyPrinter() writes each result to standard output. Here it passes the result to
the "context" of the running search, attached to the plugin's thread (and inherited by any thread the
plugin starts itself), so that every result reaches the right search.
"""

import re
import threading

_local = threading.local()


class SearchCancelled(Exception):
    """Raised inside the plugin once the search has been stopped, to interrupt it quickly."""


class Context:
    def __init__(self, on_result, is_cancelled):
        self.on_result = on_result
        self.is_cancelled = is_cancelled
        self.count = 0
        self.errors = []


def set_context(context):
    _local.context = context


def current_context():
    return getattr(_local, "context", None)


def report_error(message):
    """Keeps a plugin's network errors, shown if the plugin finds nothing."""
    context = current_context()
    if context is not None:
        context.errors.append(message)


def prettyPrinter(dictionary):
    context = current_context()
    if context is None:
        return
    if context.is_cancelled():
        raise SearchCancelled()
    context.count += 1
    context.on_result({
        "fileUrl": str(dictionary["link"]),
        "fileName": str(dictionary["name"]),
        "fileSize": anySizeToBytes(dictionary.get("size", -1)),
        "nbSeeders": _to_int(dictionary.get("seeds", -1)),
        "nbLeechers": _to_int(dictionary.get("leech", -1)),
        "siteUrl": str(dictionary.get("engine_url", "")),
        "descrLink": str(dictionary.get("desc_link", "")),
        "pubDate": _to_int(dictionary.get("pub_date", -1)),
    })


sizeUnitRegex = re.compile(r"^(?P<size>\d*\.?\d+) *(?P<unit>[a-z]+)?", re.IGNORECASE)


def anySizeToBytes(size_string):
    """Converts "1.5 GB" to 1610612736. Numbers are accepted too, as in qBittorrent."""
    if isinstance(size_string, bool):
        return -1
    if isinstance(size_string, int):
        return size_string
    if isinstance(size_string, float):
        return round(size_string)
    match = sizeUnitRegex.match(str(size_string).strip().replace(",", ""))
    if match is None:
        return -1
    size = float(match.group("size"))
    unit = match.group("unit")
    if unit is not None:
        size *= 2 ** {"T": 40, "G": 30, "M": 20, "K": 10}.get(unit[0].upper(), 0)
    return round(size)


def _to_int(value):
    try:
        return int(str(value).strip().replace(",", ""))
    except ValueError:
        return -1


# Threads started by a plugin (multiprocessing.dummy, concurrent.futures...) inherit the context of
# the thread that starts them, so their results reach the right search.
_original_start = threading.Thread.start


def _start_with_context(self):
    context = current_context()
    if context is not None:
        run = self.run

        def run_with_context():
            set_context(context)
            run()

        self.run = run_with_context
    _original_start(self)


threading.Thread.start = _start_with_context
