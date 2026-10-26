#!/usr/bin/env python3
"""Self-contained HTTP Range fixtures; no copyrighted comics or multi-GB files.

Run: python tools/range_server.py --bind 127.0.0.1 --port 8765
GET /metrics returns actual request/response transfer records; POST /reset clears
records and version-change state. /zip64.cbz uses virtual zero-filled offsets.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import socket
import struct
import threading
import time
import zipfile
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def make_png(page: int, width: int = 480, height: int = 720) -> bytes:
    """RGB image with visible TEST PAGE n glyphs and a diagnostic page border."""
    glyphs = {
        "T": [31, 4, 4, 4, 4, 4, 4], "E": [31, 16, 16, 30, 16, 16, 31],
        "S": [15, 16, 16, 14, 1, 1, 30], "P": [30, 17, 17, 30, 16, 16, 16],
        "A": [14, 17, 17, 31, 17, 17, 17], "G": [15, 16, 16, 23, 17, 17, 15],
        "1": [4, 12, 4, 4, 4, 4, 14], "2": [14, 17, 1, 2, 4, 8, 31],
        "3": [30, 1, 1, 14, 1, 1, 30], "4": [2, 6, 10, 18, 31, 2, 2],
        " ": [0] * 7,
    }
    text = f"TEST PAGE {page}"
    scale = 6
    origin_x, origin_y = 38, 80
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            red = x < 12 or x >= width - 12 or y < 12 or y >= height - 12
            glyph_x, glyph_y = (x - origin_x) // scale, (y - origin_y) // scale
            ink = False
            if 0 <= glyph_y < 7 and glyph_x >= 0:
                character, column = divmod(glyph_x, 6)
                if character < len(text) and column < 5:
                    ink = bool(glyphs[text[character]][glyph_y] & (1 << (4 - column)))
            if ink:
                color = (35, 35, 38)
            elif red:
                color = (190, 25 + page * 12, 35)
            else:
                stripe = (y // 72 + page) % 3
                color = (248 - stripe * 8, 247 - stripe * 7, 242 - stripe * 4)
            rows.extend(color)
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + png_chunk(b"tEXt", f"Description\0SYNTHETIC TEST CONTENT; PAGE {page}; NOT A MARVEL COMIC".encode())
            + png_chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + png_chunk(b"IEND", b""))


class VirtualArchive:
    def __init__(self, size: int, segments: list[tuple[int, bytes]], pages: list[bytes]):
        self.size = size
        self.segments = segments
        self.pages = pages
        fingerprint = hashlib.sha256()
        fingerprint.update(str(size).encode())
        for offset, data in segments:
            fingerprint.update(str(offset).encode())
            fingerprint.update(data)
        self.etag = '"' + fingerprint.hexdigest() + '"'

    def read(self, start: int, count: int) -> bytes:
        if start < 0 or count < 0 or start + count > self.size:
            raise ValueError("range outside virtual archive")
        if count > 1_048_576:
            raise ValueError("virtual reads are bounded to 1 MiB")
        result = bytearray(count)
        for offset, data in self.segments:
            begin, end = max(start, offset), min(start + count, offset + len(data))
            if begin < end:
                result[begin - start:end - start] = data[begin - offset:end - offset]
        return bytes(result)


def standard_archive(pages: list[bytes]) -> VirtualArchive:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        # Fixed metadata makes the ETag independent of server startup time.
        for i, data in enumerate(pages):
            info = zipfile.ZipInfo(f"TEST_PAGE_{i + 1:03d}.png", (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED if i % 2 == 0 else zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
        archive.writestr(zipfile.ZipInfo("TEST_CONTENT.txt", (2026, 1, 1, 0, 0, 0)),
                         "Self-generated technical test images. Not Marvel metadata or artwork.")
    data = buffer.getvalue()
    return VirtualArchive(len(data), [(0, data)], pages)


def zip64_archive(pages: list[bytes]) -> VirtualArchive:
    cursor = (1 << 32) + 8192
    segments: list[tuple[int, bytes]] = []
    central = bytearray()
    for i, data in enumerate(pages[:2]):
        name = f"TEST_PAGE_{i + 1:03d}.png".encode()
        crc = zlib.crc32(data) & 0xFFFFFFFF
        compressor = zlib.compressobj(wbits=-15)
        compressed = data if i == 0 else compressor.compress(data) + compressor.flush()
        method = 0 if i == 0 else 8
        local = struct.pack("<IHHHHHIIIHH", 0x04034B50, 45, 0, method, 0, 0, crc,
                            len(compressed), len(data), len(name), 0) + name + compressed
        segments.append((cursor, local))
        extra = struct.pack("<HHQ", 1, 8, cursor)
        central.extend(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 45, 45, 0, method,
                                   0, 0, crc, len(compressed), len(data), len(name), len(extra),
                                   0, 0, 0, 0, 0xFFFFFFFF) + name + extra)
        cursor += len(local)
    central_offset = cursor
    segments.append((cursor, bytes(central)))
    cursor += len(central)
    zip64_offset = cursor
    end64 = struct.pack("<IQHHIIQQQQ", 0x06064B50, 44, 45, 45, 0, 0, 2, 2, len(central), central_offset)
    locator = struct.pack("<IIQI", 0x07064B50, 0, zip64_offset, 1)
    end = struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, 0xFFFF, 0xFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0)
    trailer = end64 + locator + end
    segments.append((cursor, trailer))
    return VirtualArchive(cursor + len(trailer), segments, pages[:2])


class FixtureState:
    def __init__(self, log_file: str | None = None):
        self.lock = threading.Lock()
        self.records: list[dict] = []
        self.counts: dict[str, int] = {}
        self.log_file = log_file
        pages = [make_png(i) for i in range(1, 5)]
        self.standard = standard_archive(pages)
        self.zip64 = zip64_archive(pages)

    def next_count(self, path: str) -> int:
        with self.lock:
            count = self.counts.get(path, 0)
            self.counts[path] = count + 1
            return count

    def record(self, value: dict):
        with self.lock:
            self.records.append(value)
            if self.log_file:
                with open(self.log_file, "a", encoding="utf-8") as stream:
                    stream.write(json.dumps(value, ensure_ascii=False) + "\n")


def handler_for(state: FixtureState):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def json_response(self, value, status=200):
            payload = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.close_connection = True
            self.end_headers()
            self.wfile.write(payload)

        def do_POST(self):
            if urlsplit(self.path).path != "/reset":
                return self.json_response({"error": "not found"}, 404)
            with state.lock:
                state.records.clear()
                state.counts.clear()
            self.json_response({"reset": True})

        def do_HEAD(self):
            self.serve(body=False)

        def do_GET(self):
            path = urlsplit(self.path).path
            if path == "/metrics":
                with state.lock:
                    value = {"requests": list(state.records), "total_body_bytes_sent": sum(r["actual_bytes_sent"] for r in state.records),
                             "standard_size": state.standard.size, "zip64_logical_size": state.zip64.size,
                             "physical_fixture_bytes": sum(len(d) for _, d in state.standard.segments + state.zip64.segments)}
                return self.json_response(value)
            self.serve(body=True)

        def serve(self, body: bool):
            path = urlsplit(self.path).path
            routes = {"/standard.cbz", "/zip64.cbz", "/ignore.cbz", "/bad-range.cbz", "/truncated.cbz", "/changing.cbz", "/slow.cbz"}
            if path not in routes:
                return self.json_response({"error": "not found", "routes": sorted(routes)}, 404)
            archive = state.zip64 if path == "/zip64.cbz" else state.standard
            count = state.next_count(path) if body else 0
            etag = '"changed-test-object-v2"' if path == "/changing.cbz" and count > 0 else archive.etag
            raw_range = self.headers.get("Range")
            start, end = 0, archive.size - 1
            status = 200
            content_range = None
            if self.headers.get("If-Match") not in (None, "*", etag):
                status = 412
                end = -1
            elif raw_range and path != "/ignore.cbz":
                match = re.fullmatch(r"bytes=(\d*)-(\d*)", raw_range)
                if not match or not any(match.groups()):
                    status, end = 416, -1
                else:
                    a, b = match.groups()
                    if a:
                        start, end = int(a), min(int(b) if b else archive.size - 1, archive.size - 1)
                    else:
                        start, end = max(0, archive.size - int(b)), archive.size - 1
                    if start > end or start >= archive.size:
                        status, end = 416, -1
                    else:
                        status = 206
                        content_range = f"bytes {start}-{end}/{archive.size}"
            if status == 206 and self.headers.get("If-Range") not in (None, etag):
                status, start, end, content_range = 200, 0, archive.size - 1, None
            if path == "/bad-range.cbz" and status == 206:
                content_range = f"bytes {start + 1}-{end + 1}/{archive.size}"
            length = max(0, end - start + 1)
            self.send_response(status)
            self.send_header("Content-Type", "application/vnd.comicbook+zip")
            self.send_header("Content-Length", str(length))
            self.send_header("Connection", "close")
            self.close_connection = True
            self.send_header("ETag", etag)
            self.send_header("Last-Modified", "Thu, 01 Jan 2026 00:00:00 GMT")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Disposition", 'inline; filename="SYNTHETIC_TEST.cbz"')
            if content_range:
                self.send_header("Content-Range", content_range)
            if status == 416:
                self.send_header("Content-Range", f"bytes */{archive.size}")
            self.end_headers()
            record = {"time_unix": time.time(), "method": self.command, "path": path, "range": raw_range,
                      "if_match": self.headers.get("If-Match"), "status": status, "content_range": content_range,
                      "declared_bytes": length, "actual_bytes_sent": 0, "interrupted": False}
            try:
                remaining = length
                # One-byte probing succeeds. Larger responses deliberately close before Content-Length.
                if path == "/truncated.cbz" and status == 206 and length > 1:
                    remaining = max(1, length // 2)
                    record["interrupted"] = True
                cursor = start
                while body and remaining:
                    chunk_size = min(1024 if path == "/slow.cbz" else 16_384, remaining)
                    data = archive.read(cursor, chunk_size)
                    self.wfile.write(data)
                    self.wfile.flush()
                    record["actual_bytes_sent"] += len(data)
                    cursor += len(data)
                    remaining -= len(data)
                    if path == "/slow.cbz" and length > 1:
                        time.sleep(0.04)
                if record["interrupted"]:
                    self.close_connection = True
                    try:
                        self.connection.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass
            except (BrokenPipeError, ConnectionResetError, OSError):
                record["interrupted"] = True
            finally:
                state.record(record)

    return Handler


def make_server(bind: str = "127.0.0.1", port: int = 8765, log_file: str | None = None):
    state = FixtureState(log_file)
    server = ThreadingHTTPServer((bind, port), handler_for(state))
    server.daemon_threads = True
    server.fixture_state = state
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--log", help="optional JSONL request log")
    args = parser.parse_args()
    server = make_server(args.bind, args.port, args.log)
    print(json.dumps({"listening": f"http://{args.bind}:{server.server_port}",
                      "standard_bytes": server.fixture_state.standard.size,
                      "zip64_logical_bytes": server.fixture_state.zip64.size,
                      "notice": "SYNTHETIC TEST CONTENT ONLY"}), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
