"""Regression checks for language-tagged but untranslated Wikidata labels."""
import unittest

from tools.build_marvel_glossary import has_han as builder_has_han
from tools.fetch_marvel_wikidata import has_han, select_chinese_names


class ChineseSourceLabelTest(unittest.TestCase):
    def test_latin_zh_label_is_preserved_without_becoming_a_translation(self):
        labels = {"en": "Alpha Flight", "zh": "Alpha Flight"}
        name, aliases, variants = select_chinese_names(labels, {})
        self.assertIsNone(name)
        self.assertEqual(["Alpha Flight"], aliases)
        self.assertEqual({"zh": "Alpha Flight"}, variants)

    def test_latin_preferred_variant_does_not_hide_later_han_label(self):
        name, aliases, variants = select_chinese_names(
            {"zh-hans": "Wakanda", "zh": "瓦坎达", "zh-hant": "瓦干達"},
            {"zh": ["瓦坎達", "Wakanda"]},
        )
        self.assertEqual("瓦坎达", name)
        self.assertEqual(["Wakanda", "瓦坎達", "瓦干達"], aliases)
        self.assertEqual("Wakanda", variants["zh-hans"])

    def test_alias_alone_cannot_be_promoted_to_source_label(self):
        name, aliases, _ = select_chinese_names({"zh": "Cyclops"}, {"zh": ["镭射眼"]})
        self.assertIsNone(name)
        self.assertEqual(["Cyclops", "镭射眼"], aliases)

    def test_han_definition_agrees_with_resource_builder(self):
        for name in (None, "", "Cyclops", "X-23", "Jean Grey", "凤凰女", "X战警", "𠀀", "㐀"):
            with self.subTest(name=name):
                self.assertEqual(builder_has_han(name), has_han(name))


if __name__ == "__main__":
    unittest.main()
