"""Replacement for qBittorrent's helpers.py, with the same functions for plugins."""

import datetime
import gzip
import html
import http.cookiejar
import os
import tempfile
import urllib.error
import urllib.parse
import urllib.request

import novaprinter


def getBrowserUserAgent():
    """User-Agent of a recent Firefox: some sites reject clients they do not recognise."""
    version = 125 + (datetime.date.today() - datetime.date(2024, 4, 16)).days // 30
    return f"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:{version}.0) Gecko/20100101 Firefox/{version}.0"


# Old and new names, depending on the qBittorrent version the plugin targets.
_getBrowserUserAgent = getBrowserUserAgent
headers = {"User-Agent": getBrowserUserAgent()}
_headers = headers
htmlentitydecode = html.unescape


def enable_socks_proxy(enable):
    """No SOCKS proxy on the phone; kept for plugins that call it."""


def _fetch(url, custom_headers=None, request_data=None, ssl_context=None, referer=None):
    if isinstance(request_data, dict):
        request_data = urllib.parse.urlencode(request_data)
    if isinstance(request_data, str):
        request_data = request_data.encode()
    request = urllib.request.Request(url, data=request_data, headers={**headers, **(custom_headers or {})})
    if referer:
        request.add_header("Referer", referer)
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        urllib.request.HTTPSHandler(context=ssl_context),
    )
    with opener.open(request) as response:
        data = response.read()
        charset = response.headers.get_content_charset() or "utf-8"
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return data, charset


def retrieve_url(url, custom_headers={}, request_data=None, ssl_context=None, unescape_html_entities=True):
    """Page content as text; an empty string on network errors, as in qBittorrent."""
    try:
        data, charset = _fetch(url, custom_headers, request_data, ssl_context)
    except (urllib.error.URLError, OSError) as error:
        host = urllib.parse.urlsplit(url).netloc
        novaprinter.report_error(f"cannot connect to {host} ({getattr(error, 'reason', error)})")
        return ""
    try:
        text = data.decode(charset, "replace")
    except LookupError:
        text = data.decode("utf-8", "replace")
    return html.unescape(text) if unescape_html_entities else text


def download_file(url, referer=None, ssl_context=None):
    """Downloads the file to a temporary file and returns "path url"."""
    data, _ = _fetch(url, referer=referer, ssl_context=ssl_context)
    handle, path = tempfile.mkstemp(suffix=".torrent")
    with os.fdopen(handle, "wb") as file:
        file.write(data)
    return path + " " + url
