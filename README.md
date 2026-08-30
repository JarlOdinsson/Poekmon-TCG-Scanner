# CardDex — Pokémon TCG Scanner

CardDex is an Android-first Kotlin/Jetpack Compose collection manager with on-device card scanning. The runnable app is in `android/`; the earlier Expo code remains in `app/` and `src/` as migration reference.

## Current application

- Quality-first CameraX capture and bundled ML Kit OCR for card name, alphanumeric collector number, printed set total, set code, and regulation mark
- Local candidate ranking with confidence/margin reporting, explicit confirmation, and review flow
- Offline English physical Pokémon TCG catalog with FTS5 search
- Set, rarity, owned, and missing filters; physical storage locations; named scan sessions; and review queue
- Manual add/edit/remove, transactional quantity/status/location moves, location editing, and CSV/JSON export
- Versioned collection backup/restore with relationship and catalogue-identity validation
- Checksum-verified, rollback-resistant offline catalogue update bundles
- Separate databases: an immutable/reinstallable catalog and a Room database containing user collection data

## Production catalog v2

The bundled catalog is generated from the TCGdex cards database tag `v2.47.0`, pinned to commit `649339eb6263dd378dbc8f155c567e7d3f2da894`. Its asset index is checksum-locked in `tools/catalog-builder/source-lock.json`. Missing finish records are supplemented only by explicit TCGplayer printing rows obtained through TCGCSV; that committed evidence overlay is separately checksum-locked.

Current validated output:

- 20 physical series
- 199 sets, including physical promos and special subsets
- 20,964 English cards
- 37,780 stable physical variant identities
- 37,734 verified variants: TCGdex records plus 11,452 explicit TCGplayer finish records
- 46 physical printings marked `Finish not catalogued`, with no finish guessed
- Pokémon TCG Pocket/digital series excluded
- 29,052,928-byte SQLite database; 4,440,490-byte release archive; 4,441,052-byte Android update bundle

TCGdex does not provide a physical-finish record for 6,961 of these cards. The pinned evidence overlay verifies 6,915 of them. The remaining 46 receive a stable, explicitly unclassified identity so they can be collected without pretending their finish is known. The UI requires an explicit choice for these records and preserves them as reviewable until verified evidence becomes available. The source also lacks a confirmed English image for 1,457 cards and a set code for 7 sets.

The schema is defined in `tools/catalog-builder/schema.sql`. It includes normalized `series`, `sets`, `cards`, and `card_variants` tables, source and stable internal IDs, per-variant provenance/evidence status, catalog metadata, indexes, an FTS5 `card_search` table, and the Android-facing `card_app` view.

## Rebuild or update the catalog

Requirements are Python 3, Node/npm, and network access for a clean first build. The pipeline invokes the pinned `bun@1.2.22` compiler through `npx`.

From the repository root:

```powershell
python tools/catalog-builder/build_catalog.py
python -m unittest discover -s tools/catalog-builder -p "test_*.py"
```

The first command downloads the locked TCGdex source and asset index when absent, compiles the English snapshot, verifies the committed `variant-evidence.json` checksum, normalizes and validates SQLite, creates release artifacts, and copies the database/manifest into Android assets. Normal builds never fetch variant evidence from the network. Subsequent builds reuse the ignored source cache. Use `--rebuild-snapshot` after intentionally updating the pins.

To intentionally refresh the secondary evidence, run `python tools/catalog-builder/fetch_variant_evidence.py --refresh`, audit its unmatched and unsupported-printing report, then update the overlay checksum in `build_catalog.py` and `source-lock.json` before rebuilding. Cached reruns preserve the retrieval timestamp for deterministic output.

To adopt a new TCGdex release, update the tag, commit, asset-index checksum, and compiler pin in both `build_catalog.py` and `source-lock.json`; then increment `--catalog-version`, rebuild the snapshot, inspect validation warnings/count changes, run tests, and commit the new release and Android assets.

Generated release files are in `tools/catalog-builder/output/`:

- `catalog-en-v2.sqlite`
- `catalog-en-v2.sqlite.zip`
- `catalog-en-v2.sqlite.zip.sha256`
- `catalog-en-v2.bundle.zip` (Android-importable database plus manifest)
- `catalog-en-v2.bundle.zip.sha256`
- `catalog-manifest.json`
- `catalog-build-report.json`

Validation fails on database integrity errors, foreign-key/orphan failures, duplicate card IDs, missing required card identity fields, or malformed variants. The artifact tests also verify manifest hashes, physical/English-only scope, FTS lookup, legacy ID compatibility, and SQLite integrity.

## Android catalog lifecycle

On first use, the app atomically copies `assets/databases/catalog-en.sqlite` to a read-only application database. It compares `catalogVersion` from the asset manifest on later launches and atomically replaces only the catalog when the bundled version is newer. The user’s collection database is never replaced.

Users can import `catalog-en-vN.bundle.zip` from the Home screen. The installer rejects rollback, unsupported schema/language, path traversal, oversized entries, hash/count/metadata mismatch, missing provenance, cards without variant identities, and failed SQLite integrity checks before performing an atomic swap. Imported catalogues remain installed until a newer bundled or imported version is available.

Collection backups are selected through Android’s document provider. Restore validates the format, internal relationships, and current catalogue card/variant identities before replacing collection data in one transaction. Review metadata is included, but private cache image files are deliberately not embedded in JSON backups.

Browse search uses SQLite FTS5 and returns a bounded result set. Scanner recognition can still load the complete local catalog for offline candidate ranking.

## Build Android

From `android/`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Release process

CI in `.github/workflows/ci.yml` checks GitHub’s tracked-file size limit, validates the committed catalogue and update bundle, runs JVM tests and Android lint, and builds the debug app and instrumentation-test APK. `.github/workflows/release.yml` builds a minimized signed AAB for version tags or manual runs.

Release builds intentionally fail unless `CARDEX_KEYSTORE_PATH`, `CARDEX_KEYSTORE_PASSWORD`, `CARDEX_KEY_ALIAS`, and `CARDEX_KEY_PASSWORD` are set. GitHub also requires the base64-encoded keystore in `CARDEX_KEYSTORE_BASE64`. Version values can be overridden with `CARDEX_VERSION_CODE` and `CARDEX_VERSION_NAME`. Production releases never fall back to the debug key.
