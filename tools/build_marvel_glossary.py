#!/usr/bin/env python3
"""Build the bounded offline Marvel glossary consumed by MarvelGlossaryIndex.

Input files are JSON Lines containing sourced entities, not name/translation
pairs to be guessed or merged. This builder never copies Chinese labels between
IDs. Only explicitly supplied --source-manifest and --license files are copied;
large raw inputs stay build material rather than application resources.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import date
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import tempfile
import unicodedata
from urllib.parse import urlsplit, urlunsplit

FORMAT_VERSION = 1
NORMALIZATION_VERSION = 1
MAX_SHARD_BYTES = 1_048_576
MAX_MANIFEST_BYTES = 524_288
MAX_ALIAS_KEYS_PER_SHARD = 8192
MAX_ENTITIES_PER_SHARD = 2048
MAX_ALIAS_IDS = 64
MAX_ENTITY_ALIASES = 64
MAX_SOURCE_FILE_BYTES = 1_048_576
KINDS = frozenset({"CHARACTER", "TEAM", "ORGANIZATION", "PLACE", "ITEM", "EVENT", "CONCEPT", "UNKNOWN"})
EVIDENCE = frozenset({"COMMON", "USER_PREFERRED", "UNTRANSLATED", "COMMUNITY"})


class BuildError(ValueError):
    """An input or generated artifact violates the Android reader's contract."""


def java_length(value: str) -> int:
    return len(value.encode("utf-16-le", errors="surrogatepass")) // 2


def normalize(value: str) -> str:
    """MarvelGlossaryNameNormalizer VERSION 1, including Java UTF-16 take()."""
    prefix = value.encode("utf-16-le", errors="surrogatepass")[:4000].decode("utf-16-le", errors="surrogatepass")
    folded = unicodedata.normalize("NFKC", prefix).lower().replace("’", "'").replace("‘", "'")
    folded = re.sub(r"(?<![a-z])(?:[a-z]\.){2,}[a-z]?\.?", lambda match: match.group().replace(".", ""), folded)
    folded = re.sub(r"([a-z][a-z-]{1,80})'s\b", r"\1", folded)
    folded = "".join(char if unicodedata.category(char)[0] in {"L", "N"} else " " for char in folded)
    return " ".join(folded.split())


def bucket(value: str) -> str:
    return f"{hashlib.sha256(value.encode('utf-8')).digest()[0]:02x}"


def encoded_json(value: object, *, pretty: bool = False) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True,
                       indent=2 if pretty else None,
                       separators=None if pretty else (",", ":")) + ("\n" if pretty else "")).encode("utf-8")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def checked_text(value: object, maximum: int, field: str) -> str:
    if not isinstance(value, str) or not value.strip() or java_length(value) > maximum:
        raise BuildError(f"{field} must be nonblank text of at most {maximum} UTF-16 units")
    # Java Char.isISOControl rejects C0/C1. Lone surrogates cannot form a valid
    # reproducible UTF-8 resource and are refused before JSON serialization.
    if any(ord(char) < 32 or 0x7f <= ord(char) <= 0x9f or 0xd800 <= ord(char) <= 0xdfff for char in value):
        raise BuildError(f"{field} contains a control character or lone surrogate")
    return value


def optional_text(value: object, maximum: int, field: str) -> str | None:
    return None if value is None else checked_text(value, maximum, field)


def checked_names(value: object, field: str) -> tuple[list[str], int]:
    if value is None:
        return [], 0
    if not isinstance(value, list):
        raise BuildError(f"{field} must be a list")
    names = [checked_text(name, 240, field) for name in value]
    unique = list(dict.fromkeys(names))
    return unique[:MAX_ENTITY_ALIASES], max(0, len(unique) - MAX_ENTITY_ALIASES)


def checked_url(value: object) -> str:
    text = checked_text(value, 2048, "englishSourceUrl")
    if any(character.isspace() for character in text):
        raise BuildError("englishSourceUrl cannot contain whitespace")
    try:
        parsed = urlsplit(text)
        host = parsed.hostname
        if parsed.scheme not in {"http", "https"} or not host or parsed.username is not None or parsed.password is not None:
            raise ValueError("scheme, host or credentials")
        if not host.isascii() or not re.fullmatch(r"[a-zA-Z0-9.:-]+", host):
            raise ValueError("unsupported URI host")
        port = parsed.port
        if port is not None and not 1 <= port <= 65535:
            raise ValueError("port")
    except ValueError as failure:
        raise BuildError("englishSourceUrl must be HTTP(S) with a valid host and no credentials") from failure
    return urlunsplit(parsed)


def canonical_entity(row: object) -> tuple[dict, int]:
    if not isinstance(row, dict):
        raise BuildError("Each input row must be an object")
    identifier = checked_text(row.get("id"), 160, "id")
    english = checked_text(row.get("englishName"), 240, "englishName")
    if not normalize(english):
        raise BuildError(f"{identifier}: englishName has no searchable letters or numbers")
    chinese = optional_text(row.get("chineseName"), 240, "chineseName")
    if chinese is not None and not has_han(chinese):
        raise BuildError(f"{identifier}: Chinese name must contain a Han character; use null for untranslated names")
    aliases, trimmed = checked_names(row.get("aliases", []), "aliases")
    chinese_aliases, chinese_trimmed = checked_names(row.get("chineseAliases", []), "chineseAliases")
    kind = row.get("kind", "UNKNOWN")
    evidence = row.get("chineseEvidence", "UNTRANSLATED" if chinese is None else "COMMON")
    if kind not in KINDS:
        raise BuildError(f"{identifier}: unknown kind {kind!r}")
    if evidence not in EVIDENCE:
        raise BuildError(f"{identifier}: unknown Chinese-name evidence {evidence!r}")
    if chinese is None and evidence != "UNTRANSLATED":
        raise BuildError(f"{identifier}: absent Chinese name must be UNTRANSLATED")
    if chinese is not None and evidence == "UNTRANSLATED":
        raise BuildError(f"{identifier}: a Chinese-name field cannot be marked UNTRANSLATED")
    checked = checked_text(row.get("checkedAt"), 10, "checkedAt")
    try:
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", checked):
            raise ValueError("format")
        date.fromisoformat(checked)
    except ValueError as failure:
        raise BuildError(f"{identifier}: checkedAt is not a valid YYYY-MM-DD date") from failure
    result = {
        "id": identifier,
        "englishName": english,
        "chineseName": chinese,
        "aliases": aliases,
        "chineseAliases": chinese_aliases,
        "kind": kind,
        "englishSourceUrl": checked_url(row.get("englishSourceUrl")),
        "chineseEvidence": evidence,
        "note": checked_text(row.get("note"), 2000, "note"),
        "checkedAt": checked,
        "universe": optional_text(row.get("universe"), 160, "universe"),
    }
    # Preserve the identity evidence of conservative cross-source joins in the
    # packaged record too, not only in the ignored preparation JSONL.
    for field, maximum in (("sourceIds", 160), ("sourceUrls", 2048)):
        if field in row:
            values = row[field]
            if not isinstance(values, list) or not 1 <= len(values) <= 8:
                raise BuildError(f"{identifier}: {field} must contain 1..8 source references")
            result[field] = list(dict.fromkeys(
                checked_url(value) if field == "sourceUrls" else checked_text(value, maximum, field)
                for value in values
            ))
    return result, trimmed + chinese_trimmed


def source_artifact(path: Path, kind: str) -> tuple[str, bytes, dict]:
    data = path.read_bytes()
    if len(data) > MAX_SOURCE_FILE_BYTES:
        raise BuildError(f"{kind} file exceeds 1MiB: {path.name}")
    digest = sha256(data)
    safe_name = re.sub(r"[^a-zA-Z0-9._-]+", "_", path.name).strip("._")
    if not safe_name:
        raise BuildError("Invalid source evidence filename")
    relative = f"sources/{digest[:12]}-{safe_name}"
    descriptor = {"path": relative, "kind": kind, "bytes": len(data), "sha256": digest, "inputName": path.name}
    if kind == "source-manifest":
        try:
            source = json.loads(data)
        except (ValueError, UnicodeError) as failure:
            raise BuildError(f"Invalid source manifest JSON: {path.name}") from failure
        if not isinstance(source, dict):
            raise BuildError("A source manifest must be an object")
        for key in ("source", "license", "licenseUrl", "attribution", "checkedAt", "sourceCharacterSnapshot"):
            if isinstance(source.get(key), str):
                descriptor[key] = source[key]
    return relative, data, descriptor


def has_han(value: str | None) -> bool:
    ranges = ((0x3400, 0x4dbf), (0x4e00, 0x9fff), (0xf900, 0xfaff),
              (0x20000, 0x2a6df), (0x2a700, 0x2ebef), (0x2ebf0, 0x2ee5f),
              (0x2f800, 0x2fa1f), (0x30000, 0x323af))
    return bool(value and any(any(start <= ord(char) <= end for start, end in ranges) for char in value))


def make_artifacts(inputs: list[Path], source_manifests: list[Path] | None = None,
                   licenses: list[Path] | None = None) -> tuple[dict[str, bytes], dict]:
    if not inputs:
        raise BuildError("At least one --input JSONL is required")
    entities: dict[str, dict] = {}
    originals: dict[str, str] = {}
    input_metadata = []
    input_rows = 0
    duplicates = 0
    truncated_ids: set[str] = set()
    discarded_entity_aliases = 0
    for path in inputs:
        raw = path.read_bytes()
        count = 0
        for number, line in enumerate(raw.decode("utf-8-sig").splitlines(), 1):
            if not line.strip():
                continue
            if len(line.encode("utf-8")) > 1_048_576:
                raise BuildError(f"{path.name}:{number}: input row is too large")
            try:
                row = json.loads(line)
                entity, trimmed = canonical_entity(row)
            except (ValueError, TypeError) as failure:
                raise BuildError(f"{path.name}:{number}: {failure}") from failure
            count += 1
            input_rows += 1
            identifier = entity["id"]
            fingerprint = json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            if identifier in originals:
                if originals[identifier] != fingerprint:
                    raise BuildError(f"Conflicting duplicate entity ID: {identifier}")
                duplicates += 1
                continue
            originals[identifier] = fingerprint
            entities[identifier] = entity
            if trimmed:
                truncated_ids.add(identifier)
                discarded_entity_aliases += trimmed
        input_metadata.append({"name": path.name, "bytes": len(raw), "sha256": sha256(raw), "rows": count})

    alias_ids: dict[str, set[str]] = defaultdict(set)
    discarded_normalized_names = 0
    for identifier, entity in entities.items():
        for name in [entity["englishName"], entity["chineseName"], *entity["aliases"], *entity["chineseAliases"]]:
            if name is None:
                continue
            normalized = normalize(name)
            if not normalized or java_length(normalized) > 240:
                discarded_normalized_names += 1
                truncated_ids.add(identifier)
                continue
            alias_ids[normalized].add(identifier)

    alias_buckets: list[dict[str, list[str]]] = [{} for _ in range(256)]
    entity_buckets: list[dict[str, dict]] = [{} for _ in range(256)]
    truncated_alias_keys = 0
    discarded_alias_mappings = 0
    collisions = 0
    total_mappings_before = 0
    for name, identifiers in sorted(alias_ids.items()):
        total_mappings_before += len(identifiers)
        if len(identifiers) > 1:
            collisions += 1
        # All entities remain separate. Available Chinese labels only affect
        # which bounded candidates survive a highly ambiguous name's ID cap.
        ordered = sorted(identifiers, key=lambda identifier: (entities[identifier]["chineseName"] is None, identifier))
        if len(ordered) > MAX_ALIAS_IDS:
            truncated_alias_keys += 1
            discarded_alias_mappings += len(ordered) - MAX_ALIAS_IDS
            truncated_ids.update(ordered[MAX_ALIAS_IDS:])
        alias_buckets[int(bucket(name), 16)][name] = ordered[:MAX_ALIAS_IDS]
    for identifier, entity in sorted(entities.items()):
        entity_buckets[int(bucket(identifier), 16)][identifier] = entity

    artifacts: dict[str, bytes] = {}
    shards = []
    for label, buckets, maximum_count in (("aliases", alias_buckets, MAX_ALIAS_KEYS_PER_SHARD),
                                           ("entities", entity_buckets, MAX_ENTITIES_PER_SHARD)):
        for number, records in enumerate(buckets):
            decoded = encoded_json(records)
            if len(records) > maximum_count or len(decoded) > MAX_SHARD_BYTES:
                raise BuildError(f"{label}/{number:02x} exceeds Android decoded/count budget")
            # A .gz extension is deliberately avoided: Android's asset merger
            # otherwise expands it and removes the suffix before installation.
            encoded = bytearray(gzip.compress(decoded, compresslevel=9, mtime=0))
            # Python 3.11/3.12 may delegate mtime=0 to zlib, whose OS header
            # byte differs across hosts. That byte is not part of the payload.
            encoded[9] = 255
            encoded = bytes(encoded)
            if len(encoded) > MAX_SHARD_BYTES:
                raise BuildError(f"{label}/{number:02x} exceeds Android encoded budget")
            relative = f"{label}/{number:02x}.dict"
            artifacts[relative] = encoded
            shards.append({"path": relative, "sha256": sha256(encoded), "compressedBytes": len(encoded),
                           "uncompressedBytes": len(decoded), "count": len(records)})

    source_descriptors = []
    for kind, paths in (("source-manifest", source_manifests or []), ("license", licenses or [])):
        for path in paths:
            relative, data, descriptor = source_artifact(path, kind)
            if relative in artifacts and artifacts[relative] != data:
                raise BuildError(f"Conflicting source artifact: {relative}")
            artifacts[relative] = data
            if descriptor not in source_descriptors:
                source_descriptors.append(descriptor)
    source_descriptors.sort(key=lambda descriptor: (descriptor["kind"], descriptor["path"]))
    chinese_count = sum(entity["chineseName"] is not None for entity in entities.values())
    coverage = {
        "inputRows": input_rows,
        "identicalDuplicateRows": duplicates,
        "entityCount": len(entities),
        "entitiesWithChineseName": chinese_count,
        "entitiesWithHanName": sum(has_han(entity["chineseName"]) for entity in entities.values()),
        "untranslatedEntityCount": len(entities) - chinese_count,
        "kindCounts": dict(sorted(Counter(entity["kind"] for entity in entities.values()).items())),
        "chineseEvidenceCounts": dict(sorted(Counter(entity["chineseEvidence"] for entity in entities.values()).items())),
        "aliasKeyCount": len(alias_ids),
        "aliasMappingCount": total_mappings_before - discarded_alias_mappings,
        "aliasMappingCountBeforeCap": total_mappings_before,
        "aliasCollisionKeyCount": collisions,
        "aliasIdsTruncatedKeyCount": truncated_alias_keys,
        "discardedAliasMappings": discarded_alias_mappings,
        "discardedEntityAliases": discarded_entity_aliases,
        "discardedNormalizedNames": discarded_normalized_names,
        "truncatedRecordCount": len(truncated_ids),
        "maximumCompressedShardBytes": max(row["compressedBytes"] for row in shards),
        "maximumUncompressedShardBytes": max(row["uncompressedBytes"] for row in shards),
        "compressedShardBytes": sum(row["compressedBytes"] for row in shards),
        "shardCount": len(shards),
    }
    manifest = {"formatVersion": FORMAT_VERSION, "normalizationVersion": NORMALIZATION_VERSION,
                "inputs": sorted(input_metadata, key=lambda row: (row["name"], row["sha256"])),
                "sources": source_descriptors, "coverage": coverage, "shards": shards}
    artifacts["manifest.json"] = encoded_json(manifest, pretty=True)
    artifacts["coverage.json"] = encoded_json(coverage, pretty=True)
    if len(artifacts["manifest.json"]) > MAX_MANIFEST_BYTES:
        raise BuildError("Manifest exceeds Android read budget")
    validate_artifacts(artifacts)
    return artifacts, coverage


def validate_artifacts(artifacts: dict[str, bytes]) -> None:
    """Independently validate hashes, bucket membership and every ID reference."""
    manifest = json.loads(artifacts["manifest.json"])
    rows = manifest["shards"]
    if len(rows) != 512 or len({row["path"] for row in rows}) != 512:
        raise BuildError("Expected 512 distinct shards")
    decoded = {}
    entity_ids = set()
    for row in rows:
        path = row["path"]
        if not re.fullmatch(r"(?:aliases|entities)/[0-9a-f]{2}\.dict", path):
            raise BuildError("Unsafe shard path")
        encoded = artifacts[path]
        raw = gzip.decompress(encoded)
        if not 1 <= len(encoded) <= MAX_SHARD_BYTES or not 2 <= len(raw) <= MAX_SHARD_BYTES:
            raise BuildError("Shard is outside its read budget")
        records = json.loads(raw)
        if (sha256(encoded) != row["sha256"] or len(encoded) != row["compressedBytes"] or
                len(raw) != row["uncompressedBytes"] or len(records) != row["count"]):
            raise BuildError("Shard descriptor does not match its payload")
        limit = MAX_ALIAS_KEYS_PER_SHARD if path.startswith("aliases/") else MAX_ENTITIES_PER_SHARD
        if len(records) > limit:
            raise BuildError("Shard record limit exceeded")
        for key in records:
            if bucket(key) != Path(path).stem:
                raise BuildError("Key is in the wrong hash bucket")
        if path.startswith("entities/"):
            for identifier, entity in records.items():
                if entity["id"] != identifier:
                    raise BuildError("Entity key and ID differ")
                checked, _ = canonical_entity(entity)
                if checked != entity:
                    raise BuildError("Entity is not canonical")
                entity_ids.add(identifier)
        decoded[path] = records
    for path, records in decoded.items():
        if not path.startswith("aliases/"):
            continue
        for name, identifiers in records.items():
            if normalize(name) != name or java_length(name) not in range(1, 241):
                raise BuildError("Unsafe normalized name")
            if not 1 <= len(identifiers) <= MAX_ALIAS_IDS or len(identifiers) != len(set(identifiers)):
                raise BuildError("Alias has invalid candidate IDs")
            if any(identifier not in entity_ids for identifier in identifiers):
                raise BuildError("Alias points to a missing entity")
    coverage = manifest["coverage"]
    if len(entity_ids) != coverage["entityCount"]:
        raise BuildError("Coverage does not match entity collection")
    for source in manifest["sources"]:
        data = artifacts[source["path"]]
        if len(data) != source["bytes"] or sha256(data) != source["sha256"]:
            raise BuildError("Source/license evidence hash mismatch")


def managed_path(root: Path, relative: str) -> Path:
    parts = PurePosixPath(relative)
    if parts.is_absolute() or ".." in parts.parts or not parts.parts:
        raise BuildError("Unsafe output path")
    target = root.joinpath(*parts.parts)
    if not target.resolve().is_relative_to(root.resolve()):
        raise BuildError("Output escaped the named directory")
    return target


def write_artifacts(output: Path, artifacts: dict[str, bytes]) -> None:
    """Replace only the builder's named files; never recursively delete a tree."""
    output.mkdir(parents=True, exist_ok=True)
    root = output.resolve()
    for relative in artifacts:
        managed_path(root, relative)
    # Publish the manifest last. Build tasks must run after generation finishes.
    for relative in sorted(artifacts, key=lambda name: (name == "manifest.json", name)):
        target = managed_path(root, relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        pending: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(mode="wb", dir=target.parent, prefix="." + target.name + ".", suffix=".tmp", delete=False) as stream:
                pending = Path(stream.name)
                stream.write(artifacts[relative]); stream.flush(); os.fsync(stream.fileno())
            os.replace(pending, target)
        finally:
            if pending is not None and pending.exists():
                if not pending.resolve().is_relative_to(root):
                    raise BuildError("Refusing to clean a temporary file outside output")
                pending.unlink()


def build(inputs: list[Path], output: Path, source_manifests: list[Path] | None = None,
          licenses: list[Path] | None = None) -> dict:
    artifacts, coverage = make_artifacts(inputs, source_manifests, licenses)
    write_artifacts(output, artifacts)
    return coverage


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, action="append", required=True, help="Sourced entity JSONL; may be supplied multiple times")
    parser.add_argument("--source-manifest", type=Path, action="append", default=[])
    parser.add_argument("--license", type=Path, action="append", default=[])
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/marvel-glossary"))
    args = parser.parse_args()
    try:
        coverage = build(args.input, args.output, args.source_manifest, args.license)
    except (BuildError, OSError, UnicodeError) as failure:
        parser.exit(1, f"Marvel glossary build refused: {failure}\n")
    print(json.dumps(coverage, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
