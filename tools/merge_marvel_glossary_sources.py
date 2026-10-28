#!/usr/bin/env python3
"""Conservatively cross-reference offline Marvel glossary source identities.

No network access, title matching, redirects, case folding, or Chinese-name
inference. Only a single exact Marvel Fandom page, globally one-to-one in both
inputs, can join two CHARACTER records. Input records stay in the output unless
a checked pair is replaced by a Wikidata-ID record containing both source IDs.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from copy import deepcopy
from datetime import date
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote, unquote, urlsplit


FORMAT_VERSION = 1
MAX_INPUT_BYTES = 128 * 1024 * 1024
MAX_ROW_BYTES = 1_048_576
MERGE_NOTE = ("Additional historical English names are cross-referenced from FiveThirtyEight via one exact Marvel Fandom page. "
              "Both source IDs and URLs are retained; pages have not been individually revisited. "
              "Chinese labels remain Wikidata community labels, not inferred or verified publisher translations.")


class MergeError(ValueError):
    pass


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def json_bytes(value: object, pretty: bool = False) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2 if pretty else None,
                       separators=None if pretty else (",", ":")) + ("\n" if pretty else "")).encode("utf-8")


def canonical_fandom_page(url: object) -> str | None:
    """Encoding/underscore equivalence only; preserve every title qualifier."""
    if not isinstance(url, str) or len(url) > 2048 or any(ord(char) < 32 or ord(char) == 127 for char in url):
        return None
    try:
        parsed = urlsplit(url)
        if (parsed.scheme not in {"http", "https"} or parsed.hostname != "marvel.fandom.com" or
                parsed.username is not None or parsed.password is not None or parsed.port is not None or
                parsed.query or parsed.fragment or not parsed.path.startswith("/wiki/")):
            return None
        title = unquote(parsed.path[len("/wiki/"):], errors="strict").replace("_", " ")
        if not title.strip() or any(ord(char) < 32 or ord(char) == 127 for char in title):
            return None
        title = re.sub(" +", " ", title).strip(" ")
        return "https://marvel.fandom.com/wiki/" + quote(title.replace(" ", "_"), safe="/_()-':")
    except (ValueError, UnicodeError):
        return None


def read_input(path: Path, prefix: str) -> tuple[dict[str, dict], dict]:
    with path.open("rb") as stream:
        raw = stream.read(MAX_INPUT_BYTES + 1)
    if len(raw) > MAX_INPUT_BYTES:
        raise MergeError(f"{path.name}: input exceeds the bounded preparation budget")
    rows = {}
    for number, line in enumerate(raw.decode("utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        if len(line.encode("utf-8")) > MAX_ROW_BYTES:
            raise MergeError(f"{path.name}:{number}: row exceeds the preparation budget")
        row = json.loads(line)
        identifier = row.get("id") if isinstance(row, dict) else None
        if not isinstance(identifier, str) or not identifier.startswith(prefix + ":"):
            raise MergeError(f"{path.name}:{number}: unexpected source identity")
        if identifier in rows:
            raise MergeError(f"{path.name}:{number}: duplicate input ID {identifier}")
        if not isinstance(row.get("englishName"), str) or not isinstance(row.get("aliases", []), list):
            raise MergeError(f"{path.name}:{number}: invalid name fields")
        rows[identifier] = row
    return rows, {"name": path.name, "bytes": len(raw), "sha256": sha256(raw), "records": len(rows)}


def page_universe(page: str) -> str | None:
    title = unquote(urlsplit(page).path[len("/wiki/"):]).replace("_", " ")
    qualifiers = re.findall(r"\((Earth-[^)]+)\)", title)
    return qualifiers[0] if len(qualifiers) == 1 else None


def universe_conflict(five: dict, wikidata: dict, page: str) -> bool:
    title = unquote(urlsplit(page).path[len("/wiki/"):]).replace("_", " ")
    qualifiers = re.findall(r"\((Earth-[^)]+)\)", title)
    if len(qualifiers) > 1:
        return True
    page_earth = page_universe(page)
    explicit = [value for value in (five.get("universe"), wikidata.get("universe")) if value is not None]
    if any(not isinstance(value, str) or not value.strip() for value in explicit):
        return True
    explicit = ["Earth-199999" if value == "MCU" else value for value in explicit]
    if len(set(explicit)) > 1 or (page_earth is not None and any(value != page_earth for value in explicit)):
        return True
    # This source explicitly marks MCU membership in either of these fields.
    # Fandom identifies MCU incarnations as Earth-199999; do not fold a marked
    # MCU record into Earth-616 even when another source's page ID says so.
    mcu = (wikidata.get("universe") == "MCU" or "MCU" in wikidata.get("scopes", []) or
           "Q642878" in wikidata.get("universeIds", []))
    return bool(mcu and page_earth is not None and page_earth != "Earth-199999")


def merged_record(five: dict, wikidata: dict, page: str) -> dict:
    result = deepcopy(wikidata)
    names = [*wikidata.get("aliases", []), five["englishName"], *five.get("aliases", [])]
    result["aliases"] = list(dict.fromkeys(name for name in names if name != wikidata["englishName"]))
    result["universe"] = five.get("universe") if five.get("universe") is not None else wikidata.get("universe")
    result["note"] = (wikidata.get("note", "").strip() + " " + MERGE_NOTE).strip()
    result["sourceIds"] = sorted({five["id"], wikidata["id"], *five.get("sourceIds", []), *wikidata.get("sourceIds", [])})
    result["sourceUrls"] = sorted({url for url in [five.get("englishSourceUrl"), wikidata.get("englishSourceUrl"),
                                                       *wikidata.get("fandomPageUrls", [])] if isinstance(url, str)})
    result["sourceCrosswalk"] = {
        "method": "EXACT_SINGLE_MARVEL_FANDOM_PAGE", "canonicalPage": page,
        "fivethirtyeightId": five["id"], "wikidataId": wikidata["id"],
        "individualPageRechecked": False,
    }
    return result


def merge_records(five: dict[str, dict], wikidata: dict[str, dict]) -> tuple[list[dict], dict]:
    five_pages = defaultdict(set)
    wiki_pages = defaultdict(set)
    page_lists = {}
    for identifier, row in five.items():
        page = canonical_fandom_page(row.get("englishSourceUrl"))
        if page is not None:
            five_pages[page].add(identifier)
    for identifier, row in wikidata.items():
        pages = row.get("fandomPageUrls", [])
        if not isinstance(pages, list):
            raise MergeError(f"{identifier}: fandomPageUrls must be a list")
        page_lists[identifier] = pages
        for url in pages:
            page = canonical_fandom_page(url)
            if page is not None:
                wiki_pages[page].add(identifier)
    five_to_wiki = defaultdict(set)
    wiki_to_five = defaultdict(set)
    shared = set(five_pages) & set(wiki_pages)
    for page in shared:
        for f_id in five_pages[page]:
            for w_id in wiki_pages[page]:
                five_to_wiki[f_id].add(w_id)
                wiki_to_five[w_id].add(f_id)

    replacements = {}
    removed = set()
    rejected = []
    chinese_pairs = 0
    for f_id, candidates in sorted(five_to_wiki.items()):
        f_row = five[f_id]
        page = canonical_fandom_page(f_row.get("englishSourceUrl"))
        reasons = []
        if len(candidates) != 1 or any(len(wiki_to_five[w_id]) != 1 for w_id in candidates):
            reasons.append("MULTIPLE_ENTITIES")
        if len(five_pages[page]) != 1 or len(wiki_pages[page]) != 1:
            if "MULTIPLE_ENTITIES" not in reasons:
                reasons.append("MULTIPLE_ENTITIES")
        for w_id in sorted(candidates):
            w_row = wikidata[w_id]
            if len(page_lists[w_id]) != 1:
                reasons.append("MULTIPLE_WIKIDATA_PAGES")
            if f_row.get("realHuman", False) or w_row.get("realHuman", False):
                reasons.append("REAL_HUMAN")
            if f_row.get("kind") != "CHARACTER" or w_row.get("kind") != "CHARACTER":
                reasons.append("KIND_MISMATCH_OR_NON_CHARACTER")
            if universe_conflict(f_row, w_row, page):
                reasons.append("UNIVERSE_CONFLICT")
            # Avoid discarding a future source's independently supplied Chinese
            # label. Current Five38 inputs contain none; no label is copied.
            if f_row.get("chineseName") is not None and f_row.get("chineseName") != w_row.get("chineseName"):
                reasons.append("CHINESE_LABEL_CONFLICT")
        reasons = list(dict.fromkeys(reasons))
        if reasons:
            rejected.append({"fivethirtyeightId": f_id, "wikidataIds": sorted(candidates),
                             "canonicalPage": page, "reasons": reasons})
            continue
        w_id = next(iter(candidates))
        if w_id in replacements:
            raise MergeError("Internal one-to-one invariant failed")
        replacements[w_id] = merged_record(f_row, wikidata[w_id], page)
        removed.add(f_id)
        chinese_pairs += wikidata[w_id].get("chineseName") is not None

    retained = [deepcopy(row) for identifier, row in five.items() if identifier not in removed]
    retained += [deepcopy(replacements.get(identifier, row)) for identifier, row in wikidata.items()]
    retained.sort(key=lambda row: row["id"])
    stats = {
        "inputRecords": len(five) + len(wikidata), "fivethirtyeightInputRecords": len(five), "wikidataInputRecords": len(wikidata),
        "sharedCanonicalPageKeys": len(shared), "matchedFivethirtyeightRecords": len(five_to_wiki),
        "mergedPairs": len(replacements), "mergedPairsWithExistingChineseName": chinese_pairs,
        "keptOriginalRecords": len(five) + len(wikidata) - 2 * len(replacements),
        "keptFivethirtyeightRecords": len(five) - len(replacements), "keptWikidataRecords": len(wikidata) - len(replacements),
        "outputRecords": len(retained), "skippedMatchedFivethirtyeightRecords": len(rejected),
        "primarySkipReasonCounts": dict(sorted(Counter(row["reasons"][0] for row in rejected).items())),
        "allSkipReasonCounts": dict(sorted(Counter(reason for row in rejected for reason in row["reasons"]).items())),
        "unmatchedFivethirtyeightRecords": len(five) - len(five_to_wiki), "unmatchedWikidataRecords": len(wikidata) - len(wiki_to_five),
    }
    return retained, {"stats": stats, "rejectedMatches": rejected}


def prepare(five_path: Path, wiki_path: Path, checked_at: str) -> tuple[bytes, dict]:
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", checked_at):
        raise MergeError("checked-at must be YYYY-MM-DD")
    date.fromisoformat(checked_at)
    five, f_metadata = read_input(five_path, "fivethirtyeight")
    wikidata, w_metadata = read_input(wiki_path, "wikidata")
    rows, audit = merge_records(five, wikidata)
    output = b"\n".join(json_bytes(row) for row in rows) + b"\n"
    manifest = {
        "formatVersion": FORMAT_VERSION, "checkedAt": checked_at,
        "source": "Exact offline Marvel Fandom page cross-reference of FiveThirtyEight and Wikidata inputs",
        "attribution": "FiveThirtyEight (CC BY 4.0), historical Marvel Wikia character metadata; Wikidata contributors (CC0). Cross-reference and index preparation by The Watcher's Archive.",
        "inputs": [f_metadata, w_metadata],
        "sources": [
            {"source": "https://github.com/fivethirtyeight/data/tree/master/comic-characters", "license": "CC-BY-4.0",
             "licenseUrl": "https://creativecommons.org/licenses/by/4.0/", "sourceCharacterSnapshot": "2014-08-24",
             "attribution": "FiveThirtyEight; original character-page metadata collected from Marvel Wikia. Historical names adapted for local matching."},
            {"source": "https://www.wikidata.org/", "license": "CC0", "licenseUrl": "https://www.wikidata.org/wiki/Wikidata:Licensing",
             "attribution": "Wikidata contributors. Community labels are not verified publisher translations."},
        ],
        "rules": ["one exact single Marvel Fandom page; preserve full title and universe qualifiers",
                  "globally one-to-one in both inputs; no real humans; CHARACTER kinds must agree",
                  "reject explicit universe or Chinese-label conflicts; no inferred Chinese labels",
                  "retain Wikidata ID and Chinese fields; union historical English names; retain both source IDs and URLs",
                  "no network requests or individual page rechecks; other records remain separate"],
        **audit,
        "output": {"name": "merged-entities.jsonl", "bytes": len(output), "sha256": sha256(output)},
    }
    return output, manifest


def write_named(root: Path, name: str, raw: bytes) -> None:
    root.mkdir(parents=True, exist_ok=True)
    target = root / name
    if not target.resolve().is_relative_to(root.resolve()):
        raise MergeError("Output escaped the named directory")
    pending = None
    try:
        with tempfile.NamedTemporaryFile(dir=root, prefix="." + name, suffix=".tmp", delete=False) as stream:
            pending = Path(stream.name)
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        os.replace(pending, target)
    finally:
        if pending is not None and pending.exists():
            if not pending.resolve().is_relative_to(root.resolve()):
                raise MergeError("Refusing temporary cleanup outside output")
            pending.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fivethirtyeight", type=Path, default=Path(".tools/marvel-vocabulary/fivethirtyeight-entities.jsonl"))
    parser.add_argument("--wikidata", type=Path, default=Path(".tools/marvel-vocabulary/wikidata/records.jsonl"))
    parser.add_argument("--output-dir", type=Path, default=Path(".tools/marvel-vocabulary"))
    parser.add_argument("--checked-at", default=date.today().isoformat())
    args = parser.parse_args()
    try:
        output, manifest = prepare(args.fivethirtyeight, args.wikidata, args.checked_at)
        write_named(args.output_dir, "merged-entities.jsonl", output)
        write_named(args.output_dir, "merge-manifest.json", json_bytes(manifest, pretty=True))
    except (ValueError, OSError) as failure:
        parser.exit(1, f"Marvel source merge refused: {failure}\n")
    print(json.dumps(manifest["stats"], ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
