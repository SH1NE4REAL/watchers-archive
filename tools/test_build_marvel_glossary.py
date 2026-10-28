"""Meaningful generator/Android-contract checks using only temporary fixtures."""
from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch
from uuid import uuid4

import build_marvel_glossary as glossary
import fetch_fivethirtyeight_marvel as historical


class BuildMarvelGlossaryTests(unittest.TestCase):
    def setUp(self):
        self.test_root = Path(__file__).resolve().parents[1] / ".tools" / "tmp" / "marvel-builder-tests"
        self.test_root.mkdir(parents=True, exist_ok=True)
        # Python 3.12 TemporaryDirectory uses Windows' special mode=0o700
        # ACL, which excludes this environment's restricted sandbox token.
        # Ordinary workspace directories inherit the intended workspace ACL.
        self.root = self.test_root / ("watchers-glossary-builder-" + uuid4().hex)
        self.root.mkdir()
        self.assertTrue(self.root.resolve().is_relative_to(self.test_root.resolve()))
        self.addCleanup(self.cleanup_workspace_fixture)

    def cleanup_workspace_fixture(self):
        if not self.root.resolve().is_relative_to(self.test_root.resolve()):
            raise RuntimeError("Refusing recursive cleanup outside the workspace test directory")
        shutil.rmtree(self.root)

    def record(self, identifier="source:1", english="Cyclops", chinese="镭射眼", **changes):
        value = {
            "id": identifier, "englishName": english, "chineseName": chinese,
            "aliases": ["Scott Summers"], "chineseAliases": [], "kind": "CHARACTER",
            "englishSourceUrl": "https://example.test/character/1", "note": "Sourced synthetic test name.",
            "chineseEvidence": "COMMON" if chinese is not None else "UNTRANSLATED",
            "checkedAt": "2026-10-28", "universe": "Earth-616",
        }
        value.update(changes)
        return value

    def input(self, name, records):
        path = self.root / name
        path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in records) + "\n", encoding="utf-8")
        return path

    def decoded(self, artifacts, group, key):
        return json.loads(gzip.decompress(artifacts[f"{group}/{glossary.bucket(key)}.dict"]))

    def test_normalization_matches_the_android_contract_and_does_not_strip_identity(self):
        cases = {
            "ＣＹＣＬＯＰＳ!": "cyclops", "S.H.I.E.L.D.": "shield",
            "S.H.I.E.L.D.'s": "shield", "SPIDER—MAN’S": "spider man",
            "X-Men (Earth-616)": "x men earth 616", "The Thing": "the thing",
            "镭射眼／Scott Summers": "镭射眼 scott summers", "hero🐱２": "hero 2",
            "Storm's\nname": "storm name", "İRON MAN": "i ron man",
        }
        for source, expected in cases.items():
            with self.subTest(source=source):
                self.assertEqual(expected, glossary.normalize(source))
        # Kotlin takes 2,000 UTF-16 units, not 2,000 Python Unicode code points.
        self.assertEqual("", glossary.normalize("🐱" * 1000 + "Cyclops"))

    def test_build_is_byte_deterministic_across_input_order_and_gzip_has_fixed_headers(self):
        first = self.input("one.jsonl", [self.record()])
        second = self.input("two.jsonl", [self.record("source:2", "Storm", "暴风女", aliases=["Ororo Munroe"])])
        one, coverage = glossary.make_artifacts([first, second])
        two, _ = glossary.make_artifacts([second, first])
        self.assertEqual(one, two)
        self.assertEqual(512, coverage["shardCount"])
        self.assertEqual(2, coverage["entityCount"])
        for name, data in one.items():
            if name.endswith(".dict"):
                self.assertEqual(b"\x1f\x8b", data[:2])
                self.assertEqual(b"\0\0\0\0", data[4:8])
                self.assertEqual(255, data[9])

    def test_all_shards_hashes_buckets_and_entity_references_are_valid(self):
        source = self.input("entities.jsonl", [self.record(), self.record("source:2", "Wakanda", "瓦坎达", kind="PLACE", aliases=[])])
        artifacts, _ = glossary.make_artifacts([source])
        glossary.validate_artifacts(artifacts)
        manifest = json.loads(artifacts["manifest.json"])
        self.assertEqual(512, len(manifest["shards"]))
        for row in manifest["shards"]:
            data = artifacts[row["path"]]
            self.assertEqual(hashlib.sha256(data).hexdigest(), row["sha256"])
            self.assertLessEqual(row["uncompressedBytes"], 1_048_576)
        self.assertEqual(["source:1"], self.decoded(artifacts, "aliases", "cyclops")["cyclops"])
        self.assertEqual("镭射眼", self.decoded(artifacts, "entities", "source:1")["source:1"]["chineseName"])

    def test_same_name_different_ids_and_universes_never_copy_or_merge_chinese(self):
        translated = self.record("source:scott", "Cyclops", "镭射眼")
        other = self.record("source:lobe", "Cyclops", None, aliases=["Cyclops (Lobe)"], universe="Earth-616")
        variant = self.record("source:variant", "Cyclops", None, aliases=[], universe="Earth-1610")
        artifacts, coverage = glossary.make_artifacts([self.input("variants.jsonl", [translated, other, variant])])
        self.assertEqual(3, coverage["entityCount"])
        self.assertEqual(1, coverage["entitiesWithChineseName"])
        self.assertEqual(2, coverage["untranslatedEntityCount"])
        self.assertEqual(3, len(self.decoded(artifacts, "aliases", "cyclops")["cyclops"]))
        for identifier in ["source:lobe", "source:variant"]:
            row = self.decoded(artifacts, "entities", identifier)[identifier]
            self.assertIsNone(row["chineseName"])
            self.assertEqual("UNTRANSLATED", row["chineseEvidence"])

    def test_exact_duplicate_id_is_allowed_but_any_conflicting_record_is_refused(self):
        row = self.record()
        artifacts, coverage = glossary.make_artifacts([self.input("same.jsonl", [row, row])])
        self.assertEqual(1, coverage["entityCount"])
        self.assertEqual(1, coverage["identicalDuplicateRows"])
        self.assertEqual(1, len(self.decoded(artifacts, "entities", row["id"])))
        changed = dict(row, rawName="a conflicting source revision")
        with self.assertRaisesRegex(glossary.BuildError, "Conflicting duplicate"):
            glossary.make_artifacts([self.input("different.jsonl", [row, changed])])

    def test_alias_id_cap_is_reported_and_keeps_chinese_candidates_without_losing_entities(self):
        records = [self.record(f"source:{index:03d}", "Shared Name", None, aliases=[]) for index in range(70)]
        records.append(self.record("source:translated", "Shared Name", "有据中文", aliases=[]))
        artifacts, coverage = glossary.make_artifacts([self.input("collision.jsonl", records)])
        ids = self.decoded(artifacts, "aliases", "shared name")["shared name"]
        self.assertEqual(71, coverage["entityCount"])
        self.assertEqual(64, len(ids))
        self.assertEqual("source:translated", ids[0])
        self.assertEqual(1, coverage["aliasCollisionKeyCount"])
        self.assertEqual(1, coverage["aliasIdsTruncatedKeyCount"])
        self.assertEqual(7, coverage["discardedAliasMappings"])
        self.assertEqual(7, coverage["truncatedRecordCount"])

    def test_large_alias_lists_are_bounded_and_the_truncation_is_not_hidden(self):
        row = self.record(aliases=[f"Registered alias {number}" for number in range(70)])
        artifacts, coverage = glossary.make_artifacts([self.input("many.jsonl", [row])])
        self.assertEqual(6, coverage["discardedEntityAliases"])
        self.assertEqual(1, coverage["truncatedRecordCount"])
        self.assertEqual(64, len(self.decoded(artifacts, "entities", row["id"])[row["id"]]["aliases"]))

    def test_invalid_fields_and_unsafe_urls_fail_before_writing_output(self):
        cases = [dict(id="x" * 161), dict(englishName="x" * 241), dict(englishName="---"),
                 dict(aliases=["a\nname"]), dict(chineseName=None, chineseEvidence="COMMON"),
                 dict(chineseName="Cyclops", chineseEvidence="COMMUNITY"),
                 dict(chineseName="中文", chineseEvidence="UNTRANSLATED"),
                 dict(chineseEvidence="OFFICIAL_CHARACTER"), dict(chineseEvidence="WIKIDATA_LABEL"),
                 dict(englishSourceUrl="https://user:secret@example.test/file"),
                 dict(englishSourceUrl="file:///secret"), dict(englishSourceUrl="https://example.test/a b"),
                 dict(checkedAt="2026-02-31"), dict(kind="PLOT_GUESS"), dict(note="x" * 2001)]
        for index, changes in enumerate(cases):
            with self.subTest(changes=changes):
                output = self.root / f"output-{index}"
                with self.assertRaises(glossary.BuildError):
                    glossary.build([self.input(f"bad-{index}.jsonl", [self.record(**changes)])], output)
                self.assertFalse(output.exists())

    def test_normalized_alias_overflow_is_counted_instead_of_emitting_an_unreadable_key(self):
        # NFKC expands each square corporate glyph into four characters.
        row = self.record(aliases=["㍿" * 100])
        artifacts, coverage = glossary.make_artifacts([self.input("expansion.jsonl", [row])])
        self.assertEqual(1, coverage["discardedNormalizedNames"])
        self.assertEqual(1, coverage["truncatedRecordCount"])
        glossary.validate_artifacts(artifacts)

    def test_source_manifests_and_licences_are_hashed_and_raw_inputs_are_not_packaged(self):
        source = self.input("raw-records.jsonl", [self.record()])
        evidence = self.root / "source-manifest.json"
        evidence.write_text(json.dumps({"source": "https://example.test/dataset", "license": "CC-BY-4.0", "attribution": "Test source"}), encoding="utf-8")
        license_file = self.root / "LICENSE.txt"
        license_file.write_text("Attribution 4.0 International\nTest license fixture", encoding="utf-8")
        artifacts, _ = glossary.make_artifacts([source], [evidence], [license_file])
        manifest = json.loads(artifacts["manifest.json"])
        self.assertEqual(2, len(manifest["sources"]))
        self.assertEqual(sha(source.read_bytes()), manifest["inputs"][0]["sha256"])
        self.assertFalse(any("raw-records.jsonl" in name for name in artifacts))
        for row in manifest["sources"]:
            self.assertEqual(row["sha256"], sha(artifacts[row["path"]]))
            self.assertEqual(row["bytes"], len(artifacts[row["path"]]))

    def test_output_preserves_unmanaged_files_and_updates_only_generated_paths(self):
        output = self.root / "output"
        output.mkdir()
        sentinel = output / "personal-notes.txt"
        sentinel.write_text("User data, do not touch", encoding="utf-8")
        source = self.input("records.jsonl", [self.record()])
        glossary.build([source], output)
        before = (output / "manifest.json").read_bytes()
        glossary.build([source], output)
        self.assertEqual(before, (output / "manifest.json").read_bytes())
        self.assertEqual("User data, do not touch", sentinel.read_text(encoding="utf-8"))
        self.assertFalse(list(output.rglob("*.tmp")))
        with self.assertRaises(glossary.BuildError):
            glossary.managed_path(output, "../escape")

    def test_corrupt_payload_or_missing_entity_reference_is_detected(self):
        artifacts, _ = glossary.make_artifacts([self.input("records.jsonl", [self.record()])])
        broken = dict(artifacts)
        relative = f"aliases/{glossary.bucket('cyclops')}.dict"
        broken[relative] = broken[relative][:-2]
        with self.assertRaises((glossary.BuildError, EOFError)):
            glossary.validate_artifacts(broken)
        # Make the descriptor internally consistent so only the reference audit
        # can catch the invalid entity link.
        manifest = json.loads(artifacts["manifest.json"])
        missing = dict(artifacts)
        data = bytearray(gzip.compress(glossary.encoded_json({"cyclops": ["source:missing"]}), mtime=0))
        data[9] = 255
        missing[relative] = bytes(data)
        descriptor = next(row for row in manifest["shards"] if row["path"] == relative)
        descriptor.update(sha256=sha(bytes(data)), compressedBytes=len(data),
                          uncompressedBytes=len(glossary.encoded_json({"cyclops": ["source:missing"]})), count=1)
        missing["manifest.json"] = glossary.encoded_json(manifest, pretty=True)
        with self.assertRaisesRegex(glossary.BuildError, "missing entity"):
            glossary.validate_artifacts(missing)

    def test_shard_limit_is_enforced_before_any_output_directory_is_written(self):
        source = self.input("records.jsonl", [self.record()])
        output = self.root / "refused"
        with patch.object(glossary, "MAX_SHARD_BYTES", 64):
            with self.assertRaisesRegex(glossary.BuildError, "budget"):
                glossary.build([source], output)
        self.assertFalse(output.exists())

    def test_historical_conversion_preserves_ids_earth_and_raw_evidence_without_guessing_chinese(self):
        raw = ("page_id,name,urlslug,FIRST APPEARANCE,Year\n"
               "1,Spider-Man (Peter Parker),\\/Spider-Man_(Peter_Parker),Aug-62,1962\n"
               "2,Cyclops (Lobe) (Earth-616),\\/Cyclops_(Lobe)_(Earth-616),,\n"
               "3,Cyclops (Earth-1610),\\/Cyclops_(Earth-1610),,\n"
               "4,Reno (Secret Agent),\\/Reno_(Secret_Agent),,\n"
               "5,Gregor (resident of the Golden Star),\\/Gregor_(resident_of_the_Golden_Star),,\n").encode()
        rows = historical.convert_records(raw, "2026-10-28")
        self.assertEqual(5, len({row["id"] for row in rows}))
        self.assertEqual(["Spider-Man (Peter Parker)", "Peter Parker"], rows[0]["aliases"])
        self.assertEqual("Earth-616", rows[1]["universe"])
        self.assertEqual(["Cyclops (Lobe)"], rows[1]["aliases"])
        self.assertEqual("Cyclops", rows[2]["englishName"])
        self.assertEqual("Earth-1610", rows[2]["universe"])
        self.assertNotIn("Secret Agent", rows[3]["aliases"])
        self.assertNotIn("resident of the Golden Star", rows[4]["aliases"])
        for row in rows:
            self.assertIsNone(row["chineseName"])
            self.assertEqual("UNTRANSLATED", row["chineseEvidence"])
            self.assertEqual("2014-08-24", row["sourceCollectedAt"])
            self.assertEqual("CC-BY-4.0", row["license"])
            glossary.canonical_entity(row)

    def test_historical_checksum_guards_refuse_changed_sources_and_offline_mode_never_requests_network(self):
        raw = b"pinned fixture, without a network request\n"
        fixture = self.root / "source.csv"
        fixture.write_bytes(raw)
        source = ("comic-characters/marvel-wikia-data.csv", "marvel-wikia-data.csv", 1024, sha(raw))
        git_blob = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        with patch.dict(historical.SOURCES, {"csv": source}), patch.object(historical, "CSV_GIT_BLOB", git_blob):
            with patch.object(historical.urllib.request, "build_opener", side_effect=AssertionError("Offline must never make a request")):
                actual, metadata = historical.obtain("csv", fixture)
                self.assertEqual(raw, actual)
                self.assertEqual("offline", metadata["mode"])
            with self.assertRaisesRegex(historical.SourceError, "hash/size"):
                historical.verify_source("csv", raw + b"tampered")
        with self.assertRaises(historical.SourceError):
            historical.verify_source("license", b"unverified license text")

    def test_historical_conversion_refuses_conflicting_ids_and_bad_checked_dates(self):
        raw = b"page_id,name,urlslug,FIRST APPEARANCE,Year\n1,One,/One,,\n1,Other,/Other,,\n"
        with self.assertRaisesRegex(historical.SourceError, "duplicate page_id"):
            historical.convert_records(raw, "2026-10-28")
        with self.assertRaisesRegex(historical.SourceError, "YYYY-MM-DD"):
            historical.convert_records(raw, "20261028")

    def test_cross_source_identity_provenance_survives_packaging_and_is_bounded(self):
        record = self.record(sourceIds=["fivethirtyeight:1", "wikidata:Q1"],
                             sourceUrls=["https://marvel.fandom.com/wiki/Person_(Earth-616)", "https://www.wikidata.org/wiki/Q1"])
        result, _ = glossary.canonical_entity(record)
        self.assertEqual(record["sourceIds"], result["sourceIds"])
        self.assertEqual(record["sourceUrls"], result["sourceUrls"])
        for changes in ({"sourceIds": "a scalar"}, {"sourceIds": ["id"] * 9},
                        {"sourceUrls": ["file:///private"]}, {"sourceIds": []}):
            with self.subTest(changes=changes), self.assertRaises(glossary.BuildError):
                glossary.canonical_entity({**record, **changes})


def sha(data):
    return hashlib.sha256(data).hexdigest()


if __name__ == "__main__":
    unittest.main(verbosity=2)
