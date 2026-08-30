#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unicodedata
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from validators import validate_database


ROOT = Path(__file__).resolve().parent
DEFAULT_SOURCE_VERSION = "v2.47.0"
SOURCE_COMMIT = "649339eb6263dd378dbc8f155c567e7d3f2da894"
ASSET_INDEX_URL = "https://assets.tcgdex.net/datas.json"
ASSET_INDEX_SHA256 = "2a91112e10f238b8aca429499b83f8cd72b9a6c7bdd125a2006743129eb7b9e8"
SCHEMA_VERSION = 2
LANGUAGE = "en"
VARIANT_EVIDENCE = ROOT / "variant-evidence.json"
# Updated alongside source-lock.json whenever the committed evidence overlay changes.
VARIANT_EVIDENCE_SHA256 = "0d852d332d263858cb326db87d07b098b88f9b986d63c5113d5a3567203b86e1"

LEGACY_INTERNAL_IDS = {
    "sv03.5-007": "sv3pt5-7",
    "sv03.5-170": "sv3pt5-170",
    "sv03.5-004": "sv3pt5-4",
    "sv03.5-168": "sv3pt5-168",
    "sv03.5-006": "sv3pt5-6",
    "sv03.5-199": "sv3pt5-199",
    "sv01-181": "sv1-181",
    "sv01-255": "sv1-255",
    "sv02-196": "sv2-196",
    "sv04-080": "sv4-80",
    "swsh12-139": "swsh12-139",
    "base1-4": "base1-4",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the production CardDex English catalogue")
    parser.add_argument("--source-version", default=DEFAULT_SOURCE_VERSION)
    parser.add_argument("--catalog-version", type=int, default=2)
    parser.add_argument("--source-dir", type=Path, default=ROOT / "source" / "tcgdex")
    parser.add_argument("--snapshot", type=Path, default=None)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "output")
    parser.add_argument(
        "--android-assets-dir",
        type=Path,
        default=ROOT.parent.parent / "android" / "app" / "src" / "main" / "assets" / "databases",
    )
    parser.add_argument("--rebuild-snapshot", action="store_true")
    return parser.parse_args()


def normalize(value: Any) -> str:
    decomposed = unicodedata.normalize("NFKD", str(value or ""))
    characters = []
    for character in decomposed:
        if unicodedata.combining(character):
            continue
        if character.isalnum():
            characters.append(character.encode("ascii", "ignore").decode("ascii").lower())
        else:
            characters.append(" ")
    return " ".join("".join(characters).split())


def compact_json(value: Any, fallback: Any) -> str:
    return json.dumps(value if value is not None else fallback, ensure_ascii=False, separators=(",", ":"))


def ensure_source(source_dir: Path, source_version: str) -> None:
    if (source_dir / "server" / "compiler").is_dir():
        return
    source_dir.parent.mkdir(parents=True, exist_ok=True)
    if source_version != DEFAULT_SOURCE_VERSION:
        raise ValueError("Update SOURCE_COMMIT and the asset-index lock before changing --source-version")
    archive_url = f"https://github.com/tcgdex/cards-database/archive/{SOURCE_COMMIT}.zip"
    print(f"Downloading pinned TCGdex source {source_version} ({SOURCE_COMMIT})")
    with tempfile.TemporaryDirectory(dir=source_dir.parent) as temp_name:
        temp = Path(temp_name)
        archive = temp / "tcgdex.zip"
        request = urllib.request.Request(archive_url, headers={"User-Agent": "CardDex-Catalog-Builder"})
        with urllib.request.urlopen(request) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(temp / "extracted")
        extracted = next((temp / "extracted").iterdir())
        if source_dir.exists():
            resolved = source_dir.resolve()
            if resolved.parent != source_dir.parent.resolve():
                raise RuntimeError(f"Refusing to replace unexpected source path: {resolved}")
            shutil.rmtree(source_dir)
        shutil.move(str(extracted), str(source_dir))


def ensure_asset_index(cache_dir: Path) -> None:
    destination = cache_dir / "tcgdex-assets.json"
    if destination.exists() and sha256(destination) == ASSET_INDEX_SHA256:
        return
    cache_dir.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(ASSET_INDEX_URL, headers={"User-Agent": "CardDex-Catalog-Builder"})
    with urllib.request.urlopen(request) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)
    actual = sha256(destination)
    if actual != ASSET_INDEX_SHA256:
        destination.unlink(missing_ok=True)
        raise RuntimeError(f"TCGdex asset index checksum mismatch: expected {ASSET_INDEX_SHA256}, got {actual}")


def ensure_snapshot(args: argparse.Namespace) -> Path:
    snapshot = args.snapshot or ROOT / "source" / "cache" / f"tcgdex-en-{args.source_version}.json"
    if snapshot.exists() and not args.rebuild_snapshot:
        print(f"Using cached source snapshot {snapshot}")
        return snapshot
    ensure_source(args.source_dir, args.source_version)
    server = args.source_dir / "server"
    env = os.environ.copy()
    env["NODE_OPTIONS"] = "--use-system-ca"
    if not (server / "node_modules" / "glob").exists():
        print("Installing pinned TCGdex compiler dependencies")
        subprocess.run(
            ["npm", "install", "--ignore-scripts", "--no-audit", "--no-fund"],
            cwd=server,
            env=env,
            check=True,
        )
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    ensure_asset_index(snapshot.parent)
    print("Compiling the pinned TCGdex English source snapshot")
    subprocess.run(
        [
            "npx",
            "-y",
            "bun@1.2.22",
            str(ROOT / "export_tcgdex.ts"),
            str(args.source_dir.resolve()),
            str(snapshot.resolve()),
        ],
        cwd=server,
        env=env,
        check=True,
    )
    return snapshot


def is_physical_series(series: dict[str, Any]) -> bool:
    identifier = normalize(series.get("id"))
    name = normalize(series.get("name"))
    return identifier not in {"tcgp", "pocket"} and "tcg pocket" not in name and "digital" not in name


def internal_card_id(source_id: str) -> str:
    return LEGACY_INTERNAL_IDS.get(source_id, f"tcgdex-en:{source_id}")


def set_code(source_set: dict[str, Any]) -> str:
    abbreviation = source_set.get("abbreviation") or {}
    return str(abbreviation.get("official") or abbreviation.get("localized") or source_set.get("tcgOnline") or "")


def subtype_values(card: dict[str, Any]) -> list[str]:
    return [str(value) for value in (card.get("stage"), card.get("suffix"), card.get("trainerType"), card.get("energyType")) if value]


def variant_display(variant: dict[str, Any]) -> str:
    names = {"normal": "Normal", "reverse": "Reverse Holo", "holo": "Holo"}
    parts = [names.get(str(variant.get("type", "")).lower(), str(variant.get("type", "")).replace("-", " ").title())]
    subtype = str(variant.get("subtype") or "")
    foil = str(variant.get("foil") or "")
    stamps = [str(value) for value in variant.get("stamp") or []]
    if subtype and subtype.lower() not in {"unlimited", "standard"}:
        parts.append(subtype.replace("-", " ").title())
    if foil:
        parts.append(f"{foil.replace('-', ' ').title()} foil")
    parts.extend(value.replace("-", " ").title() for value in stamps)
    return " · ".join(part for part in parts if part)


def supported_variants(card: dict[str, Any]) -> Iterable[dict[str, Any]]:
    raw = card.get("sourceVariants")
    compiled = card.get("variants_detailed") or []
    if raw is None:
        return []
    if isinstance(raw, list):
        return compiled
    if not isinstance(raw, dict):
        return []
    supported_types = {key for key in ("normal", "reverse", "holo") if raw.get(key) is True}
    return [variant for variant in compiled if str(variant.get("type", "")).lower() in supported_types]


def insert_variant(connection: sqlite3.Connection, card_id: str, variant: dict[str, Any], index: int) -> None:
    variant_type = str(variant.get("type") or "").lower()
    if not variant_type:
        raise ValueError(f"Variant {index} for {card_id} has no type")
    source_variant_id = str(variant.get("variantId") or f"source-{index}")
    stamps = [str(item) for item in variant.get("stamp") or []]
    third_party = variant.get("thirdParty") or {}
    # TCGdex occasionally assigns the same source identifier to distinct finishes.
    # Preserve that source ID verbatim and use its stable source order to disambiguate
    # our internal key instead of discarding either physical variant.
    variant_id = f"{card_id}::{source_variant_id}::{index}"
    connection.execute(
        """INSERT INTO card_variants(
            id, card_id, source_variant_id, type, subtype, display_name, size, foil, stamps_json,
            is_first_edition, is_stamped, is_prerelease, language, evidence_status, provenance_source,
            provenance_ref, tcgplayer_id, cardmarket_id, cardtrader_id
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (
            variant_id,
            card_id,
            source_variant_id,
            variant_type,
            str(variant.get("subtype") or ""),
            variant_display(variant),
            str(variant.get("size") or "standard"),
            str(variant.get("foil") or ""),
            compact_json(stamps, []),
            int(any(stamp.lower() == "1st-edition" for stamp in stamps)),
            int(bool(stamps)),
            int(any("pre" in stamp.lower() and "release" in stamp.lower() for stamp in stamps)),
            LANGUAGE,
            "verified",
            "tcgdex",
            source_variant_id,
            str(third_party.get("tcgplayer") or ""),
            str(third_party.get("cardmarket") or ""),
            str(third_party.get("cardtrader") or ""),
        ),
    )


def insert_evidence_variant(connection: sqlite3.Connection, card_id: str, variant: dict[str, Any]) -> None:
    source_variant_id = str(variant["sourceVariantId"])
    stamps = [str(item) for item in variant.get("stamps") or []]
    normalized = {
        "type": str(variant["type"]),
        "subtype": str(variant.get("subtype") or ""),
        "foil": str(variant.get("foil") or ""),
        "stamp": stamps,
    }
    connection.execute(
        """INSERT INTO card_variants(
            id, card_id, source_variant_id, type, subtype, display_name, size, foil, stamps_json,
            is_first_edition, is_stamped, is_prerelease, language, evidence_status, provenance_source,
            provenance_ref, tcgplayer_id, cardmarket_id, cardtrader_id
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (
            f"{card_id}::{source_variant_id}", card_id, source_variant_id, normalized["type"],
            normalized["subtype"], variant_display(normalized), str(variant.get("size") or "standard"),
            normalized["foil"], compact_json(stamps, []),
            int("1st-edition" in stamps), int(bool(stamps)), int("prerelease" in stamps), LANGUAGE,
            "verified", str(variant.get("source") or "tcgplayer-via-tcgcsv"),
            str(variant.get("productId") or ""), str(variant.get("productId") or ""), "", "",
        ),
    )


def insert_unclassified_variant(connection: sqlite3.Connection, card_id: str, source_id: str) -> None:
    source_variant_id = "unclassified-physical"
    connection.execute(
        """INSERT INTO card_variants(
            id, card_id, source_variant_id, type, subtype, display_name, size, foil, stamps_json,
            is_first_edition, is_stamped, is_prerelease, language, evidence_status, provenance_source,
            provenance_ref, tcgplayer_id, cardmarket_id, cardtrader_id
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (
            f"{card_id}::{source_variant_id}", card_id, source_variant_id, "unclassified", "",
            "Finish not catalogued", "standard", "", "[]", 0, 0, 0, LANGUAGE, "unclassified",
            "tcgdex-card-existence", source_id, "", "", "",
        ),
    )


def load_variant_evidence(source_version: str) -> dict[str, Any]:
    if not VARIANT_EVIDENCE.exists():
        raise RuntimeError(f"Missing committed variant evidence overlay: {VARIANT_EVIDENCE}")
    actual_sha = sha256(VARIANT_EVIDENCE)
    if VARIANT_EVIDENCE_SHA256 == "PENDING" or actual_sha != VARIANT_EVIDENCE_SHA256:
        raise RuntimeError(
            f"Variant evidence checksum mismatch: expected {VARIANT_EVIDENCE_SHA256}, got {actual_sha}"
        )
    evidence = json.loads(VARIANT_EVIDENCE.read_text(encoding="utf-8"))
    if evidence.get("schemaVersion") != 1:
        raise RuntimeError("Unsupported variant evidence schema")
    if evidence.get("tcgdexSourceVersion") != source_version:
        raise RuntimeError("Variant evidence was generated for a different TCGdex source version")
    return evidence


def build_database(snapshot: Path, database: Path, catalog_version: int, source_version: str) -> dict[str, Any]:
    print(f"Loading {snapshot}")
    with snapshot.open("r", encoding="utf-8") as source_file:
        source = json.load(source_file)
    evidence = load_variant_evidence(source_version)
    evidence_by_card = evidence.get("variantsByCardSourceId") or {}
    unclassified_source_ids = set(evidence.get("unmatchedCards") or [])

    physical_series = [item for item in source["series"] if is_physical_series(item)]
    series_ids = {item["id"] for item in physical_series}
    physical_sets = [item for item in source["sets"] if (item.get("serie") or {}).get("id") in series_ids]
    set_by_id = {item["id"]: item for item in physical_sets}
    physical_cards = [item for item in source["cards"] if (item.get("set") or {}).get("id") in set_by_id]

    database.parent.mkdir(parents=True, exist_ok=True)
    if database.exists():
        database.unlink()
    connection = sqlite3.connect(database)
    try:
        connection.executescript((ROOT / "schema.sql").read_text(encoding="utf-8"))
        built_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        metadata = {
            "catalogVersion": str(catalog_version),
            "schemaVersion": str(SCHEMA_VERSION),
            "language": LANGUAGE,
            "source": "tcgdex",
            "sourceVersion": source_version,
            "sourceCommit": SOURCE_COMMIT,
            "assetIndexSha256": ASSET_INDEX_SHA256,
            "variantEvidenceSha256": VARIANT_EVIDENCE_SHA256,
            "builtAt": built_at,
        }
        connection.executemany("INSERT INTO catalog_metadata(key,value) VALUES(?,?)", metadata.items())

        for order, item in enumerate(physical_series):
            connection.execute(
                "INSERT INTO series(id,source_id,name,language,sort_order) VALUES(?,?,?,?,?)",
                (item["id"], item["id"], item["name"], LANGUAGE, order),
            )

        for item in physical_sets:
            code = set_code(item)
            counts = item.get("cardCount") or {}
            legal = item.get("legal") or {}
            connection.execute(
                """INSERT INTO sets(
                    id,source_id,series_id,name,normalized_name,code,normalized_code,printed_total,total,
                    release_date,language,logo_url,symbol_url,standard_legal,expanded_legal
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    item["id"], item["id"], item["serie"]["id"], item["name"], normalize(item["name"]),
                    code, normalize(code), int(counts.get("official") or 0), int(counts.get("total") or 0),
                    str(item.get("releaseDate") or ""), LANGUAGE, str(item.get("logo") or ""),
                    str(item.get("symbol") or ""), int(bool(legal.get("standard"))), int(bool(legal.get("expanded"))),
                ),
            )

        for position, card in enumerate(physical_cards, 1):
            source_id = str(card["id"])
            card_id = internal_card_id(source_id)
            set_item = set_by_id[card["set"]["id"]]
            code = set_code(set_item)
            local_number = str(card.get("localId") or "")
            image = str(card.get("image") or "")
            legal = card.get("legal") or {}
            rules = list(card.get("sourceRules") or [])
            if card.get("effect") and card["effect"] not in rules:
                rules.append(card["effect"])
            connection.execute(
                """INSERT INTO cards(
                    internal_id,source_id,name,normalized_name,set_id,collector_number,normalized_collector_number,
                    normalized_set_code,normalized_set_name,rarity,supertype,subtypes_json,types_json,hp,artist,
                    regulation_mark,language,abilities_json,attacks_json,rules_json,retreat_cost,weaknesses_json,
                    resistances_json,image_small_url,image_large_url,standard_legal,expanded_legal,release_date,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    card_id, source_id, card["name"], normalize(card["name"]), set_item["id"], local_number,
                    normalize(local_number).lstrip("0") or "0", normalize(code), normalize(set_item["name"]),
                    str(card.get("rarity") or ""), str(card.get("category") or ""), compact_json(subtype_values(card), []),
                    compact_json(card.get("types"), []), str(card.get("hp") or ""), str(card.get("illustrator") or ""),
                    str(card.get("regulationMark") or ""), LANGUAGE, compact_json(card.get("abilities"), []),
                    compact_json(card.get("attacks"), []), compact_json(rules, []), int(card.get("retreat") or 0),
                    compact_json(card.get("weaknesses"), []), compact_json(card.get("resistances"), []),
                    f"{image}/low.webp" if image else "", f"{image}/high.webp" if image else "",
                    int(bool(legal.get("standard"))), int(bool(legal.get("expanded"))),
                    str(set_item.get("releaseDate") or ""), str(card.get("updated") or ""),
                ),
            )
            source_supported = list(supported_variants(card))
            for index, variant in enumerate(source_supported):
                insert_variant(connection, card_id, variant, index)
            if not source_supported:
                evidence_variants = evidence_by_card.get(source_id) or []
                for variant in evidence_variants:
                    insert_evidence_variant(connection, card_id, variant)
                if not evidence_variants and source_id in unclassified_source_ids:
                    insert_unclassified_variant(connection, card_id, source_id)
            connection.execute(
                "INSERT INTO card_search(internal_id,card_name,collector_number,set_name,set_code,rarity,artist) VALUES(?,?,?,?,?,?,?)",
                (card_id, card["name"], local_number, set_item["name"], code, str(card.get("rarity") or ""), str(card.get("illustrator") or "")),
            )
            if position % 2500 == 0:
                print(f"normalized {position}/{len(physical_cards)} cards")

        connection.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
        connection.commit()
        validation = validate_database(connection)
        counts = {
            "seriesCount": connection.execute("SELECT COUNT(*) FROM series").fetchone()[0],
            "setCount": connection.execute("SELECT COUNT(*) FROM sets").fetchone()[0],
            "cardCount": connection.execute("SELECT COUNT(*) FROM cards").fetchone()[0],
            "variantCount": connection.execute("SELECT COUNT(*) FROM card_variants").fetchone()[0],
            "verifiedVariantCount": connection.execute(
                "SELECT COUNT(*) FROM card_variants WHERE evidence_status='verified'"
            ).fetchone()[0],
            "unclassifiedVariantCount": connection.execute(
                "SELECT COUNT(*) FROM card_variants WHERE evidence_status='unclassified'"
            ).fetchone()[0],
        }
        if validation.errors:
            raise RuntimeError("Catalogue validation failed:\n" + "\n".join(validation.errors))
        connection.execute("ANALYZE")
        connection.commit()
        connection.execute("VACUUM")
        return {**metadata, **counts, "validationWarnings": validation.warnings, "validationErrors": validation.errors}
    finally:
        connection.close()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    args = parse_args()
    snapshot = ensure_snapshot(args)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    stem = f"catalog-en-v{args.catalog_version}"
    database = args.output_dir / f"{stem}.sqlite"
    archive = args.output_dir / f"{stem}.sqlite.zip"
    manifest_file = args.output_dir / "catalog-manifest.json"
    checksum_file = args.output_dir / f"{stem}.sqlite.zip.sha256"
    bundle_file = args.output_dir / f"{stem}.bundle.zip"
    bundle_checksum_file = args.output_dir / f"{stem}.bundle.zip.sha256"
    report_file = args.output_dir / "catalog-build-report.json"

    report = build_database(snapshot, database, args.catalog_version, args.source_version)
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        bundle.write(database, database.name)
    archive_sha = sha256(archive)
    database_sha = sha256(database)
    report.update(
        {
            "databaseSize": database.stat().st_size,
            "archiveSize": archive.stat().st_size,
            "databaseSha256": database_sha,
            "sha256": archive_sha,
        }
    )
    manifest = {
        "catalogVersion": args.catalog_version,
        "schemaVersion": SCHEMA_VERSION,
        "language": LANGUAGE,
        "source": "tcgdex",
        "sourceVersion": args.source_version,
        "sourceCommit": SOURCE_COMMIT,
        "assetIndexSha256": ASSET_INDEX_SHA256,
        "variantEvidenceSha256": VARIANT_EVIDENCE_SHA256,
        "cardCount": report["cardCount"],
        "setCount": report["setCount"],
        "variantCount": report["variantCount"],
        "verifiedVariantCount": report["verifiedVariantCount"],
        "unclassifiedVariantCount": report["unclassifiedVariantCount"],
        "databaseFile": database.name,
        "archiveFile": archive.name,
        "databaseSha256": database_sha,
        "sha256": archive_sha,
        "builtAt": report["builtAt"],
    }
    manifest_file.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report_file.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    checksum_file.write_text(f"{archive_sha}  {archive.name}\n", encoding="utf-8")

    with zipfile.ZipFile(bundle_file, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        bundle.write(manifest_file, "catalog-manifest.json")
        bundle.write(database, database.name)
    bundle_sha = sha256(bundle_file)
    report.update({"bundleFile": bundle_file.name, "bundleSize": bundle_file.stat().st_size, "bundleSha256": bundle_sha})
    report_file.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    bundle_checksum_file.write_text(f"{bundle_sha}  {bundle_file.name}\n", encoding="utf-8")

    args.android_assets_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(database, args.android_assets_dir / "catalog-en.sqlite")
    shutil.copy2(manifest_file, args.android_assets_dir / "catalog-manifest.json")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    print(f"Android asset: {args.android_assets_dir / 'catalog-en.sqlite'}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"catalogue build failed: {error}", file=sys.stderr)
        raise
