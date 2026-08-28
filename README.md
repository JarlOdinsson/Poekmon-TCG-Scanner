# CardDex — Pokémon TCG Scanner

CardDex is an Android-first Kotlin/Jetpack Compose collection manager with on-device card scanning. The runnable app is in `android/`; the earlier Expo code remains in `app/` and `src/` as migration reference.

## Current application

- CameraX capture and bundled ML Kit OCR for card name, collector number, set code, and regulation mark
- Local candidate ranking with an explicit confirmation/review flow
- Offline English physical Pokémon TCG catalog with FTS5 search
- Set, rarity, owned, and missing filters; physical storage locations; named scan sessions; and review queue
- Transactional quantity moves and CSV/JSON collection export
- Separate databases: an immutable/reinstallable catalog and a Room database containing user collection data

## Production catalog v1

The bundled catalog is generated from the official TCGdex cards database tag `v2.47.0`, pinned to commit `649339eb6263dd378dbc8f155c567e7d3f2da894`. Its asset index is checksum-locked in `tools/catalog-builder/source-lock.json`.

Current validated output:

- 20 physical series
- 199 sets, including physical promos and special subsets
- 20,964 English cards
- 26,282 source-supported physical variants
- Pokémon TCG Pocket/digital series excluded
- 25,190,400-byte SQLite database; 4,111,462-byte release archive

TCGdex does not provide a physical-finish record for 6,961 of these cards, so the catalog intentionally leaves those variants unknown. It also lacks a confirmed English image for 1,457 cards and a set code for 7 sets. These are reported as warnings rather than filled with guesses.

The schema is defined in `tools/catalog-builder/schema.sql`. It includes normalized `series`, `sets`, `cards`, and `card_variants` tables, source and stable internal IDs, catalog metadata, indexes, an FTS5 `card_search` table, and the Android-facing `card_app` view.

## Rebuild or update the catalog

Requirements are Python 3, Node/npm, and network access for a clean first build. The pipeline invokes the pinned `bun@1.2.22` compiler through `npx`.

From the repository root:

```powershell
python tools/catalog-builder/build_catalog.py
python -m unittest discover -s tools/catalog-builder -p "test_*.py"
```

The first command downloads the locked TCGdex source and asset index when absent, compiles the English snapshot, normalizes and validates SQLite, creates release artifacts, and copies the database/manifest into Android assets. Subsequent builds reuse the ignored source cache. Use `--rebuild-snapshot` after intentionally updating the pins.

To adopt a new TCGdex release, update the tag, commit, asset-index checksum, and compiler pin in both `build_catalog.py` and `source-lock.json`; then increment `--catalog-version`, rebuild the snapshot, inspect validation warnings/count changes, run tests, and commit the new release and Android assets.

Generated release files are in `tools/catalog-builder/output/`:

- `catalog-en-v1.sqlite`
- `catalog-en-v1.sqlite.zip`
- `catalog-en-v1.sqlite.zip.sha256`
- `catalog-manifest.json`
- `catalog-build-report.json`

Validation fails on database integrity errors, foreign-key/orphan failures, duplicate card IDs, missing required card identity fields, or malformed variants. The artifact tests also verify manifest hashes, physical/English-only scope, FTS lookup, legacy ID compatibility, and SQLite integrity.

## Android catalog lifecycle

On first use, the app atomically copies `assets/databases/catalog-en.sqlite` to a read-only application database. It compares `catalogVersion` from the asset manifest on later launches and atomically replaces only the catalog when the version changes. The user’s collection database is never replaced.

Browse search uses SQLite FTS5 and returns a bounded result set. Scanner recognition can still load the complete local catalog for offline candidate ranking.

## Build Android

From `android/`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.
