#!/usr/bin/env python3
"""Reproduce the historical, English-only FiveThirtyEight glossary input.

Only four fixed, public FiveThirtyEight/data files may be requested: one 2.4 MB
CSV and three small attribution/metadata files. Each is downloaded at most once,
with bounded reads, a timeout, and pinned hashes. Supplying --csv, --readme,
--license and --comic-readme makes the entire operation offline. This tool does
not visit character pages, invent Chinese translations, or merge character IDs.
"""
from __future__ import annotations

import argparse
from collections import Counter
import csv
from datetime import date
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote, unquote
import urllib.request


BASE = "https://raw.githubusercontent.com/fivethirtyeight/data/master/"
DATASET = "https://github.com/fivethirtyeight/data/tree/master/comic-characters"
CSV_GIT_BLOB = "16071d50fcf1484e0824f7df28b2144f35763830"
CSV_ROW_COUNT = 16_376
SOURCES = {
    "csv": ("comic-characters/marvel-wikia-data.csv", "marvel-wikia-data.csv", 3_000_000,
            "f30f02ab3575aa145fe32dc0709b7931d72a664de0557150abc43a25113f8277"),
    "readme": ("README.md", "fivethirtyeight-readme.md", 65_536,
               "df6cc1332e12ee4bb5c38d1a9247dd7250ef6b937ecd31069d6532ec45bc9d60"),
    "license": ("LICENSE", "fivethirtyeight-license.txt", 65_536,
                "7e7170e3cebf88a9f60c7b8421418323c09304da1af4d5e90f4da1dc1c8a2661"),
    "comic_readme": ("comic-characters/README.md", "comic-characters-readme.md", 65_536,
                     "380b1d26227aaba8eea398e5e1fc2ebaf73b9567935dbd9a3637ab4ba87d7a35"),
}
NOTE = ("FiveThirtyEight comic-characters dataset; Marvel Wikia character snapshot collected 2014-08-24. "
        "English subject names only; no verified Chinese name. Historical source, not a complete current Marvel catalogue.")
# These identities are explicitly present in the named source's initial rows.
# Other brackets may describe a species, job, universe or duplicate, so a
# multiword bracket alone is insufficient to register another person's name.
IDENTITY_LABELS = frozenset({
    "Spider-Man (Peter Parker)", "Captain America (Steven Rogers)",
    'Wolverine (James "Logan" Howlett)', 'Iron Man (Anthony "Tony" Stark)',
    "Thor (Thor Odinson)", "Hulk (Robert Bruce Banner)", "Deadpool (Wade Wilson)",
    "Red Hulk (Thaddeus Ross)", "Sabretooth (Victor Creed)",
})


class SourceError(ValueError):
    pass


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def verify_source(kind: str, raw: bytes) -> None:
    _, _, maximum, expected = SOURCES[kind]
    if len(raw) > maximum or sha256(raw) != expected:
        raise SourceError(f"{kind}: source hash/size differs from the reviewed snapshot")
    if kind == "csv":
        actual_blob = hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()
        if actual_blob != CSV_GIT_BLOB:
            raise SourceError("csv: Git blob verification failed")


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise SourceError(f"Unexpected redirect from a fixed source (HTTP {code})")


def obtain(kind: str, local: Path | None) -> tuple[bytes, dict]:
    relative, filename, maximum, _ = SOURCES[kind]
    url = BASE + relative
    if local is not None:
        with local.open("rb") as stream:
            raw = stream.read(maximum + 1)
        record = {"name": filename, "url": url, "mode": "offline"}
    else:
        # The task environment contains a non-working proxy. Direct requests
        # go only to the fixed public source allowlist, with no fallback hosts.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())
        request = urllib.request.Request(url, headers={"User-Agent": "WatchersArchive-glossary-source/1.0", "Accept-Encoding": "identity"})
        with opener.open(request, timeout=20) as response:
            if response.status != 200 or response.url != url:
                raise SourceError(f"{kind}: expected a direct HTTP 200 from the fixed source")
            raw = response.read(maximum + 1)
        record = {"name": filename, "url": url, "mode": "network", "status": 200}
    verify_source(kind, raw)
    record.update(bytes=len(raw), sha256=sha256(raw))
    return raw, record


def convert_records(raw_csv: bytes, checked_at: str) -> list[dict]:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", checked_at):
        raise SourceError("checked-at must be YYYY-MM-DD")
    date.fromisoformat(checked_at)
    rows = csv.DictReader(io.StringIO(raw_csv.decode("utf-8-sig"), newline=""))
    if not {"page_id", "name", "urlslug", "FIRST APPEARANCE", "Year"}.issubset(rows.fieldnames or []):
        raise SourceError("CSV is missing reviewed identity columns")
    output = []
    seen = set()
    for row in rows:
        page_id = row["page_id"]
        if not re.fullmatch(r"[0-9]+", page_id) or page_id in seen:
            raise SourceError("Invalid or duplicate page_id; IDs must never be merged by title")
        seen.add(page_id)
        label = " ".join(html.unescape(row["name"]).replace('\\"', '"').split())
        earth = re.search(r"\s*\((Earth-[^)]+)\)\s*$", label)
        universe = earth.group(1) if earth else None
        source_label = label[:earth.start()].strip() if earth else label
        qualifiers = re.findall(r"\(([^()]*)\)", source_label)
        english = " ".join(re.sub(r"\s*\([^()]*\)", "", source_label).split())
        if not english:
            raise SourceError(f"{page_id}: empty English identity")
        aliases = [source_label] if source_label != english else []
        if universe is None and source_label in IDENTITY_LABELS:
            for qualifier in qualifiers:
                aliases.append(qualifier)
                without_nickname = " ".join(re.sub(r'"[^"]*"', "", qualifier).split())
                if without_nickname != qualifier:
                    aliases.append(without_nickname)
        # Keep the original slug and reference URL for an exact identity
        # crosswalk. These links have not been fetched or verified individually.
        slug = row["urlslug"].replace("\\/", "/").lstrip("/")
        source_url = "https://marvel.fandom.com/wiki/" + quote(unquote(slug), safe="/_()-':")
        year = row["Year"].strip()
        output.append({
            "id": "fivethirtyeight:" + page_id,
            "englishName": english, "chineseName": None,
            "aliases": list(dict.fromkeys(aliases)), "chineseAliases": [], "kind": "CHARACTER",
            "englishSourceUrl": source_url, "chineseEvidence": "UNTRANSLATED", "note": NOTE,
            "checkedAt": checked_at, "universe": universe,
            "rawName": row["name"], "rawUrl": row["urlslug"], "pageId": page_id,
            "sourceDataset": DATASET, "license": "CC-BY-4.0", "sourceCollectedAt": "2014-08-24",
            "sourceLabel": source_label, "sourceQualifier": qualifiers,
            "firstAppearance": row["FIRST APPEARANCE"].strip() or None,
            "firstAppearanceYear": int(year) if re.fullmatch(r"\d{4}", year) else None,
        })
    return output


def manifest_for(records: list[dict], raw: bytes, output: bytes, checked_at: str) -> dict:
    universes = Counter(row["universe"] or "unspecified" for row in records)
    years = [row["firstAppearanceYear"] for row in records if row["firstAppearanceYear"] is not None]
    return {
        "dataset": "FiveThirtyEight comic-characters Marvel data", "source": BASE + SOURCES["csv"][0],
        "sourceBytes": len(raw), "sourceGitBlob": CSV_GIT_BLOB, "sourceSha256": sha256(raw),
        "license": "CC-BY-4.0",
        "attribution": "FiveThirtyEight; original character-page metadata collected from Marvel Wikia, August 24, 2014. Adapted by The Watcher's Archive for local name matching.",
        "licenseUrl": "https://creativecommons.org/licenses/by/4.0/", "checkedAt": checked_at,
        "sourceCharacterSnapshot": "2014-08-24", "sourceAppearanceSnapshot": "2014-09-02",
        "sourceFirstIssueSnapshot": "2014-10-06", "rows": len(records), "entities": len(records), "skipped": [],
        "englishAliases": sum(len(row["aliases"]) for row in records), "chineseNames": 0,
        "distinctUniverses": sum(key != "unspecified" for key in universes), "universeCounts": dict(universes),
        "firstAppearanceYearMin": min(years) if years else None, "firstAppearanceYearMax": max(years) if years else None,
        "transforms": ["HTML/source-escaped quotes normalised", "Earth qualifier retained separately",
                       "qualifiers remain identity metadata; only reviewed source identity brackets promoted to aliases",
                       "stable page_id never merged by title", "no automatic Chinese translation"],
        "outputBytes": len(output), "outputSha256": sha256(output),
    }


def atomic_write(root: Path, name: str, raw: bytes) -> None:
    root.mkdir(parents=True, exist_ok=True)
    target = root / name
    if not target.resolve().is_relative_to(root.resolve()):
        raise SourceError("Output path escaped the requested directory")
    pending = None
    try:
        with tempfile.NamedTemporaryFile(dir=root, prefix="." + name, suffix=".tmp", delete=False) as stream:
            pending = Path(stream.name)
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        os.replace(pending, target)
    finally:
        if pending is not None and pending.exists():
            if not pending.resolve().is_relative_to(root.resolve()):
                raise SourceError("Refusing temporary cleanup outside output")
            pending.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for key in SOURCES:
        parser.add_argument("--" + key.replace("_", "-"), type=Path, help="Use a pinned local source; otherwise fetch this one fixed file once")
    parser.add_argument("--output", type=Path, default=Path(".tools/marvel-vocabulary"))
    parser.add_argument("--checked-at", default=date.today().isoformat())
    args = parser.parse_args()
    try:
        blobs = {}; fetches = []
        for kind in SOURCES:
            blobs[kind], record = obtain(kind, getattr(args, kind))
            fetches.append(record)
        records = convert_records(blobs["csv"], args.checked_at)
        if len(records) != CSV_ROW_COUNT:
            raise SourceError("CSV row count differs from the reviewed snapshot")
        output = ("\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in records) + "\n").encode("utf-8")
        manifest = manifest_for(records, blobs["csv"], output, args.checked_at)
        for kind, raw in blobs.items():
            atomic_write(args.output, SOURCES[kind][1], raw)
        atomic_write(args.output, "fivethirtyeight-entities.jsonl", output)
        atomic_write(args.output, "fivethirtyeight-manifest.json", (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        atomic_write(args.output, "fivethirtyeight-fetch.json", (json.dumps(fetches, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    except (OSError, ValueError) as failure:
        parser.exit(1, f"FiveThirtyEight preparation refused: {failure}\n")
    print(json.dumps({"entities": len(records), "englishAliases": manifest["englishAliases"], "chineseNames": 0,
                      "outputSha256": manifest["outputSha256"], "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
