import sqlite3
import tempfile
import unittest
from pathlib import Path

from build_catalog import internal_card_id, is_physical_series, normalize, supported_variants
from fetch_variant_evidence import evidence_variant, match_products
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

    def test_same_number_products_are_disambiguated_by_card_name(self):
        products = [
            {"productId": 1, "name": "Bisharp", "extendedData": [{"name": "Number", "value": "15"}]},
            {"productId": 2, "name": "Wigglytuff", "extendedData": [{"name": "Number", "value": "15"}]},
        ]
        card = {"name": "Bisharp", "localId": "15", "set": {"name": "XY Trainer Kit"}}
        self.assertEqual([1], [item["productId"] for item in match_products(card, products)])

    def test_distinct_prerelease_and_staff_products_keep_distinct_ids(self):
        prerelease = evidence_variant({"productId": 10, "name": "Pikachu Prerelease"}, "Holofoil")
        staff = evidence_variant({"productId": 11, "name": "Pikachu Staff"}, "Holofoil")
        self.assertNotEqual(prerelease["sourceVariantId"], staff["sourceVariantId"])
        self.assertEqual(["prerelease"], prerelease["stamps"])
        self.assertEqual(["staff"], staff["stamps"])

    def test_unknown_marketplace_finish_is_not_guessed(self):
        self.assertIsNone(evidence_variant({"productId": 1, "name": "Pikachu"}, "Cosmos Holofoil"))


if __name__ == "__main__":
    unittest.main()
