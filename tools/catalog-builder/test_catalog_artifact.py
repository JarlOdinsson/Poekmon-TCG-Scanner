import hashlib
import json
import sqlite3
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
DATABASE = ROOT / "output" / "catalog-en-v1.sqlite"
MANIFEST = ROOT / "output" / "catalog-manifest.json"


@unittest.skipUnless(DATABASE.exists() and MANIFEST.exists(), "build the catalog artifact first")
class CatalogArtifactTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.connection = sqlite3.connect(DATABASE)
        cls.manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.connection.close()

    def test_manifest_matches_database(self):
        self.assertEqual(self.manifest["cardCount"], self.connection.execute("SELECT COUNT(*) FROM cards").fetchone()[0])
        digest = hashlib.sha256(DATABASE.read_bytes()).hexdigest()
        self.assertEqual(self.manifest["databaseSha256"], digest)

    def test_only_physical_english_catalog_is_included(self):
        pocket = self.connection.execute(
            "SELECT COUNT(*) FROM series WHERE lower(id) IN ('tcgp','pocket') OR lower(name) LIKE '%pocket%'"
        ).fetchone()[0]
        self.assertEqual(0, pocket)
        self.assertEqual(0, self.connection.execute("SELECT COUNT(*) FROM cards WHERE language <> 'en'").fetchone()[0])

    def test_fts_and_legacy_ids(self):
        charizard = self.connection.execute(
            "SELECT COUNT(*) FROM card_search WHERE card_search MATCH 'charizard*'"
        ).fetchone()[0]
        self.assertGreater(charizard, 0)
        self.assertIsNotNone(self.connection.execute("SELECT 1 FROM cards WHERE internal_id='sv3pt5-7'").fetchone())

    def test_database_integrity(self):
        self.assertEqual("ok", self.connection.execute("PRAGMA integrity_check").fetchone()[0])
        self.assertEqual([], self.connection.execute("PRAGMA foreign_key_check").fetchall())


if __name__ == "__main__":
    unittest.main()
