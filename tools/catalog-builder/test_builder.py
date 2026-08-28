import sqlite3
import tempfile
import unittest
from pathlib import Path

from build_catalog import internal_card_id, is_physical_series, normalize, supported_variants
from validators import validate_database


class BuilderRulesTest(unittest.TestCase):
    def test_normalization_is_ocr_friendly(self):
        self.assertEqual("pokemon 151", normalize("Pokémon—151"))
        self.assertEqual("007 165", normalize("007/165"))

    def test_digital_pocket_series_is_excluded(self):
        self.assertFalse(is_physical_series({"id": "tcgp", "name": "Pokémon TCG Pocket"}))
        self.assertTrue(is_physical_series({"id": "sv", "name": "Scarlet & Violet"}))

    def test_missing_variant_data_is_not_invented(self):
        self.assertEqual([], list(supported_variants({"sourceVariants": None, "variants_detailed": [{"type": "normal"}]})))

    def test_compiled_first_edition_is_not_duplicated(self):
        variants = [
            {"type": "normal", "variantId": "normal"},
            {"type": "normal", "variantId": "normal-1st", "stamp": ["1st-edition"]},
        ]
        card = {"sourceVariants": {"normal": True, "firstEdition": True}, "variants_detailed": variants}
        self.assertEqual(variants, list(supported_variants(card)))

    def test_legacy_seed_ids_stay_stable(self):
        self.assertEqual("sv3pt5-7", internal_card_id("sv03.5-007"))
        self.assertEqual("tcgdex-en:xy1-1", internal_card_id("xy1-1"))


if __name__ == "__main__":
    unittest.main()
