"""Tests for the plugin engine, runnable without Android:

    py -3.13 -m unittest discover -s app/src/test/python -t app/src/test/python
"""

import json
import os
import pathlib
import sys
import tempfile
import textwrap
import threading
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "main" / "python"))

import engine  # noqa: E402
import novaprinter  # noqa: E402

PLUGINS = {
    "fakeplugin": '''
        # VERSION: 1.2
        from multiprocessing.dummy import Pool
        from novaprinter import prettyPrinter

        class fakeplugin:
            url = "https://fake.example"
            name = "Fake"
            supported_categories = {"all": "0", "movies": "1"}

            def search(self, what, cat="all"):
                prettyPrinter({"link": "magnet:?xt=urn:btih:aaa", "name": "Ubuntu " + what, "size": "1.5 GB",
                               "seeds": "12", "leech": 3, "engine_url": self.url, "desc_link": "https://fake.example/a"})
                # A result emitted from a thread the plugin starts itself.
                with Pool(2) as pool:
                    pool.map(lambda n: prettyPrinter({"link": "https://fake.example/%d.torrent" % n, "name": "Debian %d" % n,
                                                      "size": 700, "seeds": -1, "leech": -1, "engine_url": self.url}), [1])

            def download_torrent(self, info):
                print(info + " " + info)
    ''',
    "brokenplugin": '''
        class brokenplugin:
            url = "https://broken.example"
            name = "Broken"

            def search(self, what, cat="all"):
                raise RuntimeError("site down")
    ''',
    "filenoclass": '''
        class SomethingElse:
            pass
    ''',
    "filedownload": '''
        import tempfile
        from novaprinter import prettyPrinter

        class filedownload:
            url = "https://files.example"
            name = "Files"

            def search(self, what, cat="all"):
                for n in range(100):
                    prettyPrinter({"link": "x%d" % n, "name": "n", "size": 1, "seeds": 1, "leech": 1, "engine_url": self.url})

            def download_torrent(self, info):
                handle, path = tempfile.mkstemp()
                with open(handle, "wb") as file:
                    file.write(b"d4:infod4:name1:xee")
                print("Connection error: ignored line")
                print(path + " " + info)
    ''',
}


class Sink:
    def __init__(self, cancel_after=None):
        self.results, self.errors = [], []
        self.cancel_after = cancel_after
        self.lock = threading.Lock()

    def isCancelled(self):
        return self.cancel_after is not None and len(self.results) >= self.cancel_after

    def onResult(self, plugin, data):
        with self.lock:
            self.results.append((plugin, json.loads(data)))

    def onError(self, plugin, message):
        self.errors.append((plugin, message))


class EngineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        nova = os.path.join(cls.tmp.name, "nova3")
        engine.init(nova, os.path.join(cls.tmp.name, "tmp"))
        for name, code in PLUGINS.items():
            with open(os.path.join(nova, "engines", name + ".py"), "w", encoding="utf-8") as file:
                file.write(textwrap.dedent(code).lstrip())

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_info_reads_metadata(self):
        info = json.loads(engine.info("fakeplugin"))
        self.assertEqual("Fake", info["fullName"])
        self.assertEqual("1.2", info["version"])
        self.assertEqual(["all", "movies"], info["categories"])
        # Without supported_categories, the plugin is queried for every category.
        self.assertEqual(len(engine.CATEGORIES), len(json.loads(engine.info("brokenplugin"))["categories"]))

    def test_info_rejects_file_without_matching_class(self):
        with self.assertRaises(ValueError):
            engine.info("filenoclass")

    def test_search_collects_results_from_plugin_threads_and_errors(self):
        sink = Sink()
        engine.search(["fakeplugin", "brokenplugin"], "24.04 lts", "all", sink)
        names = sorted(result["fileName"] for _, result in sink.results)
        self.assertEqual(["Debian 1", "Ubuntu 24.04%20lts"], names)
        ubuntu = next(result for _, result in sink.results if result["fileName"].startswith("Ubuntu"))
        self.assertEqual(1610612736, ubuntu["fileSize"])
        self.assertEqual(12, ubuntu["nbSeeders"])
        self.assertEqual(-1, ubuntu["pubDate"])
        self.assertEqual([("brokenplugin", "RuntimeError: site down")], sink.errors)

    def test_plugins_without_the_category_are_skipped(self):
        sink = Sink()
        engine.search(["fakeplugin"], "x", "games", sink)
        self.assertEqual([], sink.results)
        self.assertEqual([], sink.errors)

    def test_cancellation_stops_the_plugin(self):
        sink = Sink(cancel_after=5)
        engine.search(["filedownload"], "x", "all", sink)
        self.assertEqual(5, len(sink.results))
        self.assertEqual([], sink.errors)

    def test_download_returns_magnet_or_file(self):
        self.assertEqual("magnet:?xt=urn:btih:aaa", engine.download("fakeplugin", "magnet:?xt=urn:btih:aaa"))
        path = engine.download("filedownload", "https://files.example/1")
        with open(path, "rb") as file:
            self.assertEqual(b"d4:infod4:name1:xee", file.read())

    def test_sizes(self):
        self.assertEqual(1024, novaprinter.anySizeToBytes("1 KB"))
        self.assertEqual(1536, novaprinter.anySizeToBytes("1.5 KiB"))
        self.assertEqual(-1, novaprinter.anySizeToBytes("unknown"))
        self.assertEqual(42, novaprinter.anySizeToBytes(42))


if __name__ == "__main__":
    unittest.main()
