"""Build bounded offline lookup shards from the publicly licensed ECDICT CSV.

The CSV is deliberately not stored in Git. Fetch the upstream public file into
an ignored working directory, then run this script with that path. Its exact
Git blob and SHA-256 are checked before creating application resources.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
from pathlib import Path
import re
import unicodedata

UPSTREAM_BLOB = "0e527e22e0e0d22f012b0ed251a8dddd5891773b"
UPSTREAM_BYTES = 66_009_548
SOURCE = "https://github.com/skywind3000/ECDICT/blob/master/ecdict.csv"


def normalized(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).lower()
    value = value.replace("’", "'").replace("‘", "'")
    value = re.sub("[‐‑‒–—−]", "-", value)
    value = " ".join(value.split()).strip(" \t\r\n.,!?;:\"()[]{}")
    if len(value) >= 2 and value.startswith("'") and value.endswith("'"):
        value = value[1:-1].strip()
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    parser.add_argument("--license", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/english-dictionary"))
    args = parser.parse_args()
    raw = args.csv.read_bytes()
    blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
    if len(raw) != UPSTREAM_BYTES or blob != UPSTREAM_BLOB:
        raise SystemExit("Input does not match the verified ECDICT source blob")
    licence = args.license.read_text(encoding="utf-8")
    if not licence.startswith("MIT License") or "Copyright" not in licence:
        raise SystemExit("Expected upstream MIT license text")

    buckets: list[dict[str, list[str]]] = [{} for _ in range(256)]
    skipped = 0
    with args.csv.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            word = row.get("word", "").strip()
            key = normalized(word)
            if not key or len(key) > 120 or len(key.split()) > 12 or any(ord(c) < 32 for c in key):
                skipped += 1
                continue
            # Keep only lookup data; frequency/rank/word-list tags are not needed.
            value = [word[:160], row.get("phonetic", "")[:160],
                     row.get("translation", "")[:3000], row.get("definition", "")[:2000],
                     row.get("pos", "")[:200]]
            bucket = buckets[hashlib.sha256(key.encode()).digest()[0]]
            previous = bucket.get(key)
            if previous is None or (not previous[2] and value[2]):
                bucket[key] = value

    args.output.mkdir(parents=True, exist_ok=True)
    files = []
    for number, bucket in enumerate(buckets):
        packed = json.dumps(bucket, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        if len(packed) > 8 * 1024 * 1024 or len(bucket) > 8192:
            raise SystemExit("A generated shard exceeds the Android decoder budget")
        compressed = gzip.compress(packed, compresslevel=9, mtime=0)
        # Android's resource merger transparently expands *.gz assets and drops
        # the extension. Keep a neutral suffix while retaining gzip encoding.
        name = f"{number:02x}.dict"
        (args.output / name).write_bytes(compressed)
        files.append({"name": name, "entries": len(bucket), "uncompressedBytes": len(packed),
                      "compressedBytes": len(compressed), "sha256": hashlib.sha256(compressed).hexdigest()})
    manifest = {"formatVersion": 1, "source": SOURCE, "upstreamGitBlob": blob,
                "upstreamBytes": len(raw), "upstreamSha256": hashlib.sha256(raw).hexdigest(),
                "license": "MIT", "entries": sum(len(b) for b in buckets), "skipped": skipped,
                "maximumShardBytes": max(f["uncompressedBytes"] for f in files), "shards": files}
    (args.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.output / "LICENSE.txt").write_text(licence, encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in ["entries", "skipped", "upstreamSha256", "maximumShardBytes"]}))
    print("compressedBytes=" + str(sum(f["compressedBytes"] for f in files)))


if __name__ == "__main__":
    main()
