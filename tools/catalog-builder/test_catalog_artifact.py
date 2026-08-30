import hashlib
import json
import sqlite3
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
DATABASE = ROOT / "output" / "catalog-en-v2.sqlite"
LEGACY_DATABASE = ROOT / "output" / "catalog-en-v1.sqlite"
MANIFEST = ROOT / "output" / "catalog-manifest.json"
BUNDLE = ROOT / "output" / "catalog-en-v2.bundle.zip"


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

    @unittest.skipUnless(BUNDLE.exists(), "build the Android update bundle first")
    def test_android_update_bundle_contains_only_locked_database_and_manifest(self):
        with zipfile.ZipFile(BUNDLE) as bundle:
            self.assertEqual(
                {"catalog-manifest.json", self.manifest["databaseFile"]},
                set(bundle.namelist()),
            )
            self.assertEqual(
                self.manifest["databaseSha256"],
                hashlib.sha256(bundle.read(self.manifest["databaseFile"])).hexdigest(),
            )

    def test_every_card_has_an_auditable_variant_identity(self):
        missing = self.connection.execute(
            "SELECT COUNT(*) FROM cards c WHERE NOT EXISTS "
            "(SELECT 1 FROM card_variants v WHERE v.card_id=c.internal_id)"
        ).fetchone()[0]
        self.assertEqual(0, missing)
        self.assertEqual(
            0,
            self.connection.execute(
                "SELECT COUNT(*) FROM card_variants WHERE trim(provenance_source)=''"
            ).fetchone()[0],
        )
        self.assertEqual(
            self.manifest["unclassifiedVariantCount"],
            self.connection.execute(
                "SELECT COUNT(*) FROM card_variants WHERE evidence_status='unclassified'"
            ).fetchone()[0],
        )
        self.assertEqual(46, self.manifest["unclassifiedVariantCount"])

    @unittest.skipUnless(LEGACY_DATABASE.exists(), "v1 artifact unavailable for identity regression test")
    def test_v1_variant_identities_are_preserved(self):
        with sqlite3.connect(LEGACY_DATABASE) as legacy:
            old_ids = {row[0] for row in legacy.execute("SELECT id FROM card_variants")}
        current_ids = {row[0] for row in self.connection.execute("SELECT id FROM card_variants")}
        self.assertTrue(old_ids.issubset(current_ids))


if __name__ == "__main__":
    unittest.main()
