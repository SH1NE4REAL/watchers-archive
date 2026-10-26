"""Tests the Python fixture server only; Kotlin reader tests run separately."""
import http.client
import io
import json
import struct
import threading
import unittest
import zipfile

from range_server import make_server


class RangeServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = make_server(port=0)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, path, headers=None, method="GET"):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        conn.request(method, path, headers=headers or {})
        response = conn.getresponse()
        return conn, response

    def test_standard_fixture_has_four_real_png_pages(self):
        archive = self.server.fixture_state.standard
        with zipfile.ZipFile(io.BytesIO(archive.read(0, archive.size))) as file:
            pages = [n for n in file.namelist() if n.endswith(".png")]
            self.assertEqual(4, len(pages))
            for name in pages:
                data = file.read(name)
                self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8])
                self.assertEqual((480, 720), struct.unpack(">II", data[16:24]))

    def test_206_exact_range(self):
        conn, response = self.request("/standard.cbz", {"Range": "bytes=2-19"})
        self.assertEqual(206, response.status)
        self.assertEqual(f"bytes 2-19/{self.server.fixture_state.standard.size}", response.getheader("Content-Range"))
        self.assertEqual(self.server.fixture_state.standard.read(2, 18), response.read())
        self.assertTrue(response.getheader("ETag").startswith('"'))
        conn.close()

    def test_zip64_virtual_offset_and_structures(self):
        archive = self.server.fixture_state.zip64
        self.assertGreater(archive.size, 1 << 32)
        self.assertLess(sum(len(d) for _, d in archive.segments), 50_000)
        end = archive.read(archive.size - 98, 98)
        self.assertEqual(b"PK\x06\x06", end[:4])
        self.assertEqual(b"PK\x06\x07", end[56:60])
        self.assertEqual(b"PK\x05\x06", end[76:80])
        offset = (1 << 32) + 8192
        conn, response = self.request("/zip64.cbz", {"Range": f"bytes={offset}-{offset + 3}"})
        self.assertEqual(206, response.status)
        self.assertEqual(b"PK\x03\x04", response.read())
        conn.close()

    def test_ignored_range_returns_200(self):
        conn, response = self.request("/ignore.cbz", {"Range": "bytes=0-0"})
        self.assertEqual(200, response.status)
        self.assertIsNone(response.getheader("Content-Range"))
        response.close()
        conn.close()

    def test_bad_range_header(self):
        conn, response = self.request("/bad-range.cbz", {"Range": "bytes=0-3"})
        self.assertEqual(206, response.status)
        self.assertTrue(response.getheader("Content-Range").startswith("bytes 1-4/"))
        response.read()
        conn.close()

    def test_truncated_response(self):
        conn, response = self.request("/truncated.cbz", {"Range": "bytes=0-99"})
        self.assertEqual(206, response.status)
        with self.assertRaises(http.client.IncompleteRead) as error:
            response.read()
        self.assertEqual(50, len(error.exception.partial))
        conn.close()

    def test_changed_object_rejects_if_match(self):
        conn, response = self.request("/reset", method="POST")
        response.read()
        conn.close()
        conn, response = self.request("/changing.cbz", {"Range": "bytes=0-0"})
        etag = response.getheader("ETag")
        response.read()
        conn.close()
        conn, response = self.request("/changing.cbz", {"Range": "bytes=0-0", "If-Match": etag})
        self.assertEqual(412, response.status)
        self.assertNotEqual(etag, response.getheader("ETag"))
        self.assertEqual(b"", response.read())
        conn.close()

    def test_out_of_bounds_range(self):
        conn, response = self.request("/standard.cbz", {"Range": "bytes=999999-1000000"})
        self.assertEqual(416, response.status)
        self.assertTrue(response.getheader("Content-Range").startswith("bytes */"))
        response.read()
        conn.close()

    def test_metrics_are_bounded_real_transfers(self):
        conn, response = self.request("/metrics")
        metrics = json.loads(response.read())
        conn.close()
        self.assertGreater(metrics["zip64_logical_size"], 1 << 32)
        self.assertLess(metrics["physical_fixture_bytes"], 100_000)


if __name__ == "__main__":
    unittest.main(verbosity=2)
