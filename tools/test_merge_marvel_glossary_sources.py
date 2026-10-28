"""Offline identity-crosswalk regression tests; all fixtures are synthetic."""
from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import shutil
import unittest
from uuid import uuid4

import merge_marvel_glossary_sources as merger


class MergeMarvelGlossarySourcesTests(unittest.TestCase):
    def setUp(self):
        self.fixture_root = Path(__file__).resolve().parents[1] / ".tools" / "tmp" / "marvel-merge-tests"
        self.fixture_root.mkdir(parents=True, exist_ok=True)
        # Inherit the workspace ACL; Python 3.12's mode=0700 TemporaryDirectory
        # excludes the restricted token used by this Windows test environment.
        self.root = self.fixture_root / uuid4().hex
        self.root.mkdir()
        self.addCleanup(self.cleanup_fixture)

    def cleanup_fixture(self):
        if not self.root.resolve().is_relative_to(self.fixture_root.resolve()):
            raise RuntimeError("Refusing recursive cleanup outside the workspace fixture root")
        shutil.rmtree(self.root)

    def five(self, identifier="fivethirtyeight:1", title="Scott_Summers_(Earth-616)", **changes):
        row = {"id": identifier, "englishName": "Scott Summers", "chineseName": None,
               "aliases": ["Historical Full Name"], "chineseAliases": [], "chineseEvidence": "UNTRANSLATED",
               "kind": "CHARACTER", "englishSourceUrl": "https://marvel.fandom.com/wiki/" + title,
               "universe": "Earth-616", "note": "Historical dataset note.", "rawName": "Original source label"}
        row.update(changes)
        return row

    def wiki(self, identifier="wikidata:Q1", title="Scott_Summers_(Earth-616)", **changes):
        row = {"id": identifier, "englishName": "Cyclops", "chineseName": "社区译名",
               "aliases": ["Scott Summers", "Current Alias"], "chineseAliases": ["另一个中文名"],
               "chineseEvidence": "COMMUNITY", "kind": "CHARACTER",
               "englishSourceUrl": "https://www.wikidata.org/wiki/" + identifier.split(":")[1],
               "fandomPageUrls": ["https://marvel.fandom.com/wiki/" + title],
               "universe": None, "realHuman": False, "note": "Wikidata community note.",
               "rawQid": identifier.split(":")[1], "scopes": ["MARVEL_FANDOM_ID"], "universeIds": []}
        row.update(changes)
        return row

    def run_merge(self, five, wiki):
        return merger.merge_records({row["id"]: row for row in five}, {row["id"]: row for row in wiki})

    def test_exact_single_page_pair_keeps_wikidata_identity_chinese_and_both_provenances(self):
        f = self.five(); w = self.wiki()
        before_f = deepcopy(f); before_w = deepcopy(w)
        rows, audit = self.run_merge([f], [w])
        self.assertEqual(1, len(rows))
        row = rows[0]
        self.assertEqual("wikidata:Q1", row["id"])
        self.assertEqual(w["englishName"], row["englishName"])
        self.assertEqual(w["chineseName"], row["chineseName"])
        self.assertEqual(w["chineseAliases"], row["chineseAliases"])
        self.assertEqual(w["chineseEvidence"], row["chineseEvidence"])
        self.assertEqual("Earth-616", row["universe"])
        self.assertEqual(["Scott Summers", "Current Alias", "Historical Full Name"], row["aliases"])
        self.assertEqual(["fivethirtyeight:1", "wikidata:Q1"], row["sourceIds"])
        self.assertIn(f["englishSourceUrl"], row["sourceUrls"])
        self.assertIn(w["englishSourceUrl"], row["sourceUrls"])
        self.assertFalse(row["sourceCrosswalk"]["individualPageRechecked"])
        self.assertIn("not been individually revisited", row["note"])
        self.assertEqual(1, audit["stats"]["mergedPairs"])
        self.assertEqual(before_f, f); self.assertEqual(before_w, w)

    def test_title_and_alias_similarity_never_merge_different_pages_or_earth_versions(self):
        f = self.five()
        variants = [self.wiki(title="Cyclops_(Lobe)_(Earth-616)"), self.wiki(title="Scott_Summers_(Earth-811)"),
                    self.wiki(title="Scott_Summers"), self.wiki(title="scott_summers_(Earth-616)")]
        for w in variants:
            with self.subTest(page=w["fandomPageUrls"]):
                rows, audit = self.run_merge([f], [w])
                self.assertEqual(2, len(rows))
                self.assertEqual(0, audit["stats"]["mergedPairs"])

    def test_wikidata_multiple_pages_are_rejected_even_when_only_one_matches(self):
        w = self.wiki()
        w["fandomPageUrls"].append("https://marvel.fandom.com/wiki/Scott_Summers_(Earth-30847)")
        rows, audit = self.run_merge([self.five()], [w])
        self.assertEqual(2, len(rows))
        self.assertIn("MULTIPLE_WIKIDATA_PAGES", audit["rejectedMatches"][0]["reasons"])

    def test_duplicate_same_page_entries_are_still_multiple_pages_and_rejected(self):
        w = self.wiki()
        w["fandomPageUrls"] *= 2
        _, audit = self.run_merge([self.five()], [w])
        self.assertEqual(0, audit["stats"]["mergedPairs"])
        self.assertIn("MULTIPLE_WIKIDATA_PAGES", audit["rejectedMatches"][0]["reasons"])

    def test_multiple_wikidata_entities_sharing_one_page_are_not_merged(self):
        rows, audit = self.run_merge([self.five()], [self.wiki(), self.wiki("wikidata:Q2")])
        self.assertEqual(3, len(rows))
        self.assertIn("MULTIPLE_ENTITIES", audit["rejectedMatches"][0]["reasons"])

    def test_multiple_historical_entities_sharing_one_page_are_not_merged(self):
        rows, audit = self.run_merge([self.five(), self.five("fivethirtyeight:2")], [self.wiki()])
        self.assertEqual(3, len(rows))
        self.assertEqual(2, len(audit["rejectedMatches"]))
        self.assertTrue(all("MULTIPLE_ENTITIES" in row["reasons"] for row in audit["rejectedMatches"]))

    def test_bella_donna_style_one_wikidata_entity_two_different_people_is_rejected(self):
        first = self.five(title="Bella_Donna_Boudreaux_(Earth-616)")
        second = self.five("fivethirtyeight:2", title="Narda_Ravanna_(Earth-616)")
        w = self.wiki(fandomPageUrls=[first["englishSourceUrl"], second["englishSourceUrl"]])
        rows, audit = self.run_merge([first, second], [w])
        self.assertEqual(3, len(rows))
        self.assertEqual(0, audit["stats"]["mergedPairs"])
        self.assertEqual(2, audit["stats"]["primarySkipReasonCounts"]["MULTIPLE_ENTITIES"])

    def test_real_people_and_non_character_or_different_kinds_are_rejected(self):
        cases = [(self.five(), self.wiki(realHuman=True), "REAL_HUMAN"),
                 (self.five(realHuman=True), self.wiki(), "REAL_HUMAN"),
                 (self.five(), self.wiki(kind="PLACE"), "KIND_MISMATCH_OR_NON_CHARACTER"),
                 (self.five(kind="UNKNOWN"), self.wiki(kind="UNKNOWN"), "KIND_MISMATCH_OR_NON_CHARACTER")]
        for f, w, reason in cases:
            with self.subTest(reason=reason):
                rows, audit = self.run_merge([f], [w])
                self.assertEqual(2, len(rows))
                self.assertIn(reason, audit["rejectedMatches"][0]["reasons"])

    def test_explicit_and_scoped_universe_conflicts_are_rejected(self):
        cases = [(self.five(universe="Earth-811"), self.wiki()),
                 (self.five(), self.wiki(universe="Earth-199999")),
                 (self.five(), self.wiki(universe="MCU")),
                 (self.five(), self.wiki(scopes=["MCU"])),
                 (self.five(), self.wiki(universeIds=["Q642878"]))]
        for f, w in cases:
            with self.subTest(fiveUniverse=f["universe"], wikiUniverse=w["universe"], scopes=w["scopes"]):
                _, audit = self.run_merge([f], [w])
                self.assertEqual(0, audit["stats"]["mergedPairs"])
                self.assertIn("UNIVERSE_CONFLICT", audit["rejectedMatches"][0]["reasons"])

    def test_exact_mcu_incarnation_is_compatible_and_preserves_earth_qualifier(self):
        f = self.five(title="Scott_Summers_(Earth-199999)", universe="Earth-199999")
        w = self.wiki(title="Scott_Summers_(Earth-199999)", universe="MCU", scopes=["MCU"])
        rows, audit = self.run_merge([f], [w])
        self.assertEqual(1, audit["stats"]["mergedPairs"])
        self.assertEqual("Earth-199999", rows[0]["universe"])

    def test_chinese_conflicts_do_not_silently_overwrite_a_source_label(self):
        _, audit = self.run_merge([self.five(chineseName="人工核对名")], [self.wiki()])
        self.assertEqual(0, audit["stats"]["mergedPairs"])
        self.assertIn("CHINESE_LABEL_CONFLICT", audit["rejectedMatches"][0]["reasons"])

    def test_missing_chinese_stays_missing_and_is_not_inferred_from_the_name(self):
        rows, audit = self.run_merge([self.five()], [self.wiki(chineseName=None, chineseEvidence="UNTRANSLATED", chineseAliases=[])])
        self.assertEqual(1, audit["stats"]["mergedPairs"])
        self.assertIsNone(rows[0]["chineseName"])
        self.assertEqual("UNTRANSLATED", rows[0]["chineseEvidence"])
        self.assertEqual(0, audit["stats"]["mergedPairsWithExistingChineseName"])

    def test_canonicalization_is_encoding_only_and_rejects_non_marvel_or_credential_urls(self):
        original = "https://marvel.fandom.com/wiki/Scott_Summers_(Earth-616)"
        self.assertEqual(original, merger.canonical_fandom_page("http://marvel.fandom.com/wiki/Scott%20Summers_%28Earth-616%29"))
        self.assertNotEqual(original, merger.canonical_fandom_page(original.lower()))
        for bad in ["https://other.fandom.com/wiki/Scott_Summers_(Earth-616)",
                    "https://secret@marvel.fandom.com/wiki/X", "file:///wiki/X", original + "?redirect=1",
                    original + "#section", "https://marvel.fandom.com/wiki/%0aX"]:
            with self.subTest(url=bad):
                self.assertIsNone(merger.canonical_fandom_page(bad))

    def input_file(self, name, rows):
        path = self.root / name
        path.write_bytes(b"\n".join(merger.json_bytes(row) for row in rows) + b"\n")
        return path

    def test_manifest_records_both_actual_input_hashes_attribution_and_exact_output_hash(self):
        f = self.input_file("five.jsonl", [self.five()]); w = self.input_file("wiki.jsonl", [self.wiki()])
        output, manifest = merger.prepare(f, w, "2026-10-28")
        self.assertEqual([hashlib.sha256(p.read_bytes()).hexdigest() for p in [f, w]], [row["sha256"] for row in manifest["inputs"]])
        self.assertEqual(hashlib.sha256(output).hexdigest(), manifest["output"]["sha256"])
        self.assertEqual(len(output), manifest["output"]["bytes"])
        self.assertEqual({"CC-BY-4.0", "CC0"}, {row["license"] for row in manifest["sources"]})
        self.assertIn("FiveThirtyEight", manifest["attribution"])
        self.assertEqual(0, manifest["stats"]["keptOriginalRecords"])
        self.assertEqual(1, manifest["stats"]["outputRecords"])

    def test_unmatched_originals_and_raw_fields_stay_intact_and_order_is_deterministic(self):
        fs = [self.five(), self.five("fivethirtyeight:2", "Different_(Earth-616)", rawName="Do not change")]
        ws = [self.wiki(), self.wiki("wikidata:Q2", "Other_(Earth-616)", rawQid="Preserve original field")]
        first, _ = self.run_merge(fs, ws); second, _ = self.run_merge(fs[::-1], ws[::-1])
        self.assertEqual(first, second)
        self.assertEqual(fs[1], next(row for row in first if row["id"] == fs[1]["id"]))
        self.assertEqual(ws[1], next(row for row in first if row["id"] == ws[1]["id"]))

    def test_duplicate_ids_fail_and_writes_preserve_unmanaged_files(self):
        bad = self.input_file("duplicates.jsonl", [self.five(), self.five()])
        with self.assertRaisesRegex(merger.MergeError, "duplicate input ID"):
            merger.read_input(bad, "fivethirtyeight")
        sentinel = self.root / "personal-notes.txt"
        sentinel.write_text("Keep user files", encoding="utf-8")
        merger.write_named(self.root, "merged-entities.jsonl", b"fixture\n")
        self.assertEqual("Keep user files", sentinel.read_text(encoding="utf-8"))
        self.assertEqual(b"fixture\n", (self.root / "merged-entities.jsonl").read_bytes())
        self.assertFalse(list(self.root.glob("*.tmp")))
        with self.assertRaises(merger.MergeError):
            merger.write_named(self.root, "../outside.jsonl", b"must not write")


if __name__ == "__main__":
    unittest.main(verbosity=2)
