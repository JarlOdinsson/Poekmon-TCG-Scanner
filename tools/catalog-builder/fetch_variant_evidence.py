#!/usr/bin/env python3
"""Build a reviewable, pinned variant overlay from TCGCSV's TCGplayer export."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from build_catalog import DEFAULT_SOURCE_VERSION, is_physical_series, normalize, set_code, supported_variants


ROOT = Path(__file__).resolve().parent
CATEGORY_ID = 3
BASE_URL = f"https://tcgcsv.com/tcgplayer/{CATEGORY_ID}"
USER_AGENT = "CardDex-Catalog-Builder/1.0 (+https://github.com/JarlOdinsson/Poekmon-TCG-Scanner)"
SUPPORTED_PRINTINGS = {
    "normal": ("normal", []),
    "holofoil": ("holo", []),
    "reverse holofoil": ("reverse", []),
    "1st edition normal": ("normal", ["1st-edition"]),
    "1st edition holofoil": ("holo", ["1st-edition"]),
}
GROUP_OVERRIDES = {
    # TCGdex models these as distinct source sets while TCGplayer uses marketplace
    # group names/codes that cannot be matched uniquely without an audited crosswalk.
    "bwp": 1407,   # Black and White Promos
    "np": 1423,    # Nintendo Promos
    "sm1": 1863,   # SM Base Set
    "smp": 1861,   # SM Promos
    "swshp": 2545, # Sword & Shield Promo Cards
    "tk-ex-latia": 1543,
    "tk-ex-latio": 1543,
    "tk-hs-r": 1540,
    "tk-xy-latia": 1536,
    "tk-xy-latio": 1536,
    "xy1": 1387,   # XY Base Set
    "xya": 1938,   # Alternate Art Promos
    "xyp": 1451,   # XY Promos
}
CARD_GROUP_OVERRIDES = {
    ("bw11", "rc"): 1465, # Legendary Treasures: Radiant Collection
    ("g1", "rc"): 1729,   # Generations: Radiant Collection
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Fetch explicit TCGplayer finish evidence for TCGdex gaps")
    parser.add_argument(
        "--snapshot",
        type=Path,
        default=ROOT / "source" / "cache" / f"tcgdex-en-{DEFAULT_SOURCE_VERSION}.json",
    )
    parser.add_argument("--output", type=Path, default=ROOT / "variant-evidence.json")
    parser.add_argument("--cache-dir", type=Path, default=ROOT / "source" / "cache" / "tcgcsv")
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--delay", type=float, default=0.3)
    return parser.parse_args()


def fetch_json(url: str, destination: Path, refresh: bool, delay: float) -> dict[str, Any]:
    if destination.exists() and not refresh:
        return json.loads(destination.read_text(encoding="utf-8"))
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            value = json.load(response)
        destination.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    except OSError:
        if os.name != "nt":
            raise
        # Some managed Windows machines have a valid enterprise CA in the Windows
        # trust store that Python/OpenSSL cannot consume. PowerShell uses that store
        # while retaining normal certificate and hostname verification.
        download_env = os.environ.copy()
        download_env.update(
            {
                "CARDEX_EVIDENCE_URL": url,
                "CARDEX_EVIDENCE_USER_AGENT": USER_AGENT,
                "CARDEX_EVIDENCE_DESTINATION": str(destination.resolve()),
            }
        )
        subprocess.run(
            [
                "powershell.exe", "-NoProfile", "-Command",
                "Invoke-WebRequest -UseBasicParsing -Uri $env:CARDEX_EVIDENCE_URL -Headers "
                "@{'User-Agent'=$env:CARDEX_EVIDENCE_USER_AGENT;'Accept'='application/json'} "
                "-OutFile $env:CARDEX_EVIDENCE_DESTINATION",
            ],
            env=download_env,
            check=True,
        )
        value = json.loads(destination.read_text(encoding="utf-8"))
    time.sleep(delay)
    return value


def compact_number(value: Any) -> str:
    numerator = str(value or "").split("/", 1)[0]
    result = normalize(numerator).replace(" ", "")
    return result.lstrip("0") or ("0" if result else "")


def canonical_name(value: Any) -> str:
    return normalize(str(value or "").replace("&", " and "))


def set_aliases(value: Any) -> set[str]:
    name = " ".join(token for token in normalize(str(value).replace("&", " and ")).split() if token != "and")
    aliases = {name}
    prefixes = (
        "sv ", "swsh ", "sm ", "xy ", "bw ", "dp ", "ex ", "neo ", "gym ", "base ",
        "pokemon ", "pop series ",
    )
    changed = True
    while changed:
        changed = False
        for alias in tuple(aliases):
            for prefix in prefixes:
                if alias.startswith(prefix) and len(alias) > len(prefix):
                    stripped = alias[len(prefix):]
                    if stripped not in aliases:
                        aliases.add(stripped)
                        changed = True
            if alias.endswith(" base set"):
                stripped = alias.removesuffix(" base set")
                if stripped and stripped not in aliases:
                    aliases.add(stripped)
                    changed = True
    return aliases


def choose_group(source_set: dict[str, Any], groups: list[dict[str, Any]]) -> dict[str, Any] | None:
    set_id = str(source_set["id"])
    group_by_id = {int(group["groupId"]): group for group in groups}
    if set_id in GROUP_OVERRIDES:
        return group_by_id.get(GROUP_OVERRIDES[set_id])

    set_name = str(source_set["name"])
    target_aliases = set_aliases(set_name)
    source_code = normalize(set_code(source_set))
    if source_code and source_code not in {"pr", "promo"}:
        by_code = [group for group in groups if normalize(group.get("abbreviation")) == source_code]
        if len(by_code) == 1:
            return by_code[0]

    normalized_set_name = next(iter(sorted(target_aliases, key=len, reverse=True)), "")
    scored: list[tuple[int, int, dict[str, Any]]] = []
    for group in groups:
        group_name = " ".join(token for token in normalize(group.get("name")).split() if token != "and")
        group_aliases = set_aliases(group.get("name"))
        if group_name == normalized_set_name:
            score = 0
        elif target_aliases & group_aliases:
            score = 1
        elif any(group_name.endswith(f" {alias}") for alias in target_aliases if alias):
            score = 2
        else:
            continue
        scored.append((score, len(group_name), group))

    if "trainer kit" in normalize(set_name):
        qualifier = normalize(set_name).removeprefix("bw ").removeprefix("dp ").removeprefix("ex ").removeprefix("hs ").removeprefix("sm ").removeprefix("xy ")
        qualifier = qualifier.replace("trainer kit", "").replace("2", "").strip()
        trainer_candidates = [
            group for group in groups
            if qualifier and qualifier in normalize(group.get("name"))
            and ("trainer kit" in normalize(group.get("name")) or "training kit" in normalize(group.get("name")))
        ]
        if len(trainer_candidates) == 1:
            return trainer_candidates[0]
    if not scored:
        return None
    scored.sort(key=lambda item: (item[0], item[1], int(item[2]["groupId"])))
    best = scored[0]
    if len(scored) > 1 and scored[1][:2] == best[:2]:
        return None
    return best[2]


def extended_value(product: dict[str, Any], field: str) -> str:
    for item in product.get("extendedData") or []:
        if str(item.get("name") or "").lower() == field.lower():
            return str(item.get("value") or "")
    return ""


def product_names(product: dict[str, Any]) -> set[str]:
    values = {str(product.get("name") or ""), str(product.get("cleanName") or "")}
    return {canonical_name(value) for value in values} | {
        canonical_name(value.split("(", 1)[0]) for value in values
    }


def match_products(card: dict[str, Any], products: list[dict[str, Any]]) -> list[dict[str, Any]]:
    number = compact_number(card.get("localId"))
    by_number = [item for item in products if compact_number(extended_value(item, "Number")) == number]
    card_name = canonical_name(card.get("name"))
    named_by_number = [
        item for item in by_number
        if any(name == card_name or name.startswith(f"{card_name} ") for name in product_names(item))
    ]
    if named_by_number:
        return named_by_number
    if len(by_number) == 1:
        return by_number
    set_name = str((card.get("set") or {}).get("name") or "")
    qualifier = normalize(set_name.split("(", 1)[1].split(")", 1)[0]) if "(" in set_name and ")" in set_name else ""
    if qualifier:
        by_number_and_deck = [
            item for item in by_number
            if qualifier in canonical_name(item.get("name")) or qualifier in canonical_name(item.get("cleanName"))
        ]
        if len(by_number_and_deck) == 1:
            return by_number_and_deck
    by_name = [
        item for item in by_number
        if card_name in product_names(item)
    ]
    if len(by_name) == 1:
        return by_name
    # Trainer-kit and promo sources sometimes number the same physical printing
    # differently. An exact, unique name within the already matched set/group is
    # still deterministic evidence; duplicate names remain deliberately unmatched.
    by_unique_name = [
        item for item in products
        if card_name in product_names(item)
    ]
    return by_unique_name if len(by_unique_name) == 1 else []


def evidence_variant(product: dict[str, Any], subtype: str) -> dict[str, Any] | None:
    printing = normalize(subtype)
    definition = SUPPORTED_PRINTINGS.get(printing)
    if definition is None:
        return None
    variant_type, base_stamps = definition
    product_name = normalize(product.get("name"))
    stamps = list(base_stamps)
    for marker, stamp in (("prerelease", "prerelease"), ("staff", "staff")):
        if marker in product_name and stamp not in stamps:
            stamps.append(stamp)
    source_id = f"tcgplayer:{product['productId']}:{printing.replace(' ', '-')}"
    return {
        "source": "tcgplayer-via-tcgcsv",
        "sourceVariantId": source_id,
        "productId": str(product["productId"]),
        "productName": str(product.get("name") or ""),
        "type": variant_type,
        "subtype": "",
        "size": "standard",
        "foil": "",
        "stamps": stamps,
    }


def card_group_override(card: dict[str, Any]) -> int | None:
    set_id = str((card.get("set") or {}).get("id") or "")
    local_id = normalize(card.get("localId")).replace(" ", "")
    for (candidate_set, prefix), group_id in CARD_GROUP_OVERRIDES.items():
        if set_id == candidate_set and local_id.startswith(prefix):
            return group_id
    return None


def main() -> int:
    args = parse_args()
    previous_retrieved_at = ""
    if args.output.exists() and not args.refresh:
        try:
            previous_output = json.loads(args.output.read_text(encoding="utf-8"))
            previous_retrieved_at = str((previous_output.get("source") or {}).get("retrievedAt") or "")
        except (OSError, ValueError, TypeError):
            pass
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    physical_series = {item["id"] for item in snapshot["series"] if is_physical_series(item)}
    sets = {
        item["id"]: item for item in snapshot["sets"]
        if (item.get("serie") or {}).get("id") in physical_series
    }
    missing_cards = [
        card for card in snapshot["cards"]
        if (card.get("set") or {}).get("id") in sets and not list(supported_variants(card))
    ]
    missing_by_set: dict[str, list[dict[str, Any]]] = {}
    for card in missing_cards:
        missing_by_set.setdefault(card["set"]["id"], []).append(card)

    groups_response = fetch_json(f"{BASE_URL}/groups", args.cache_dir / "groups.json", args.refresh, args.delay)
    groups = list(groups_response.get("results") or [])
    variants_by_card: dict[str, list[dict[str, Any]]] = {}
    unmatched_sets: list[dict[str, Any]] = []
    unmatched_card_details: list[dict[str, str]] = []
    unsupported_printings: set[str] = set()
    matched_groups: list[dict[str, Any]] = []

    def record_unmatched(card: dict[str, Any], reason: str) -> None:
        unmatched_card_details.append(
            {
                "cardSourceId": str(card["id"]),
                "setId": str((card.get("set") or {}).get("id") or ""),
                "name": str(card.get("name") or ""),
                "collectorNumber": str(card.get("localId") or ""),
                "reason": reason,
            }
        )

    for set_id, cards in sorted(missing_by_set.items()):
        source_set = sets[set_id]
        group = choose_group(source_set, groups)
        if group is None:
            unmatched_sets.append({"setId": set_id, "setName": source_set["name"], "cardCount": len(cards)})
            for card in cards:
                record_unmatched(card, "no-unique-tcgplayer-group")
            continue
        default_group_id = int(group["groupId"])
        cards_by_group: dict[int, list[dict[str, Any]]] = {}
        for card in cards:
            cards_by_group.setdefault(card_group_override(card) or default_group_id, []).append(card)

        for group_id, group_cards in cards_by_group.items():
            selected_group = next((item for item in groups if int(item["groupId"]) == group_id), None)
            if selected_group is None:
                for card in group_cards:
                    record_unmatched(card, "missing-audited-group")
                continue
            matched_group = {"setId": set_id, "setName": source_set["name"], "groupId": group_id, "groupName": selected_group["name"]}
            if matched_group not in matched_groups:
                matched_groups.append(matched_group)
            products_response = fetch_json(
                f"{BASE_URL}/{group_id}/products", args.cache_dir / str(group_id) / "products.json", args.refresh, args.delay
            )
            prices_response = fetch_json(
                f"{BASE_URL}/{group_id}/prices", args.cache_dir / str(group_id) / "prices.json", args.refresh, args.delay
            )
            products = list(products_response.get("results") or [])
            prices_by_product: dict[int, list[dict[str, Any]]] = {}
            for price in prices_response.get("results") or []:
                prices_by_product.setdefault(int(price["productId"]), []).append(price)

            for card in group_cards:
                matched_products = match_products(card, products)
                if not matched_products:
                    record_unmatched(card, "no-unique-product-match")
                    continue
                variants: list[dict[str, Any]] = []
                for product in matched_products:
                    for price in prices_by_product.get(int(product["productId"]), []):
                        subtype = str(price.get("subTypeName") or "")
                        variant = evidence_variant(product, subtype)
                        if variant is None:
                            if subtype:
                                unsupported_printings.add(subtype)
                            continue
                        if variant["sourceVariantId"] not in {item["sourceVariantId"] for item in variants}:
                            variants.append(variant)
                if variants:
                    variants_by_card[str(card["id"])] = sorted(variants, key=lambda item: item["sourceVariantId"])
                else:
                    record_unmatched(card, "no-supported-finish-rows")

    unmatched_by_reason: dict[str, int] = {}
    unmatched_by_set: dict[str, int] = {}
    for item in unmatched_card_details:
        unmatched_by_reason[item["reason"]] = unmatched_by_reason.get(item["reason"], 0) + 1
        unmatched_by_set[item["setId"]] = unmatched_by_set.get(item["setId"], 0) + 1

    output = {
        "schemaVersion": 1,
        "source": {
            "name": "TCGplayer catalog via TCGCSV",
            "categoryId": CATEGORY_ID,
            "groupsEndpoint": f"{BASE_URL}/groups",
            "retrievedAt": previous_retrieved_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        },
        "tcgdexSourceVersion": DEFAULT_SOURCE_VERSION,
        "missingBefore": len(missing_cards),
        "cardsEnriched": len(variants_by_card),
        "variantsAdded": sum(len(value) for value in variants_by_card.values()),
        "missingAfter": len(unmatched_card_details),
        "matchedGroups": matched_groups,
        "unmatchedSets": unmatched_sets,
        "unsupportedPrintings": sorted(unsupported_printings),
        "unmatchedByReason": unmatched_by_reason,
        "unmatchedBySet": dict(sorted(unmatched_by_set.items(), key=lambda item: (-item[1], item[0]))),
        "unmatchedCards": sorted(item["cardSourceId"] for item in unmatched_card_details),
        "unmatchedCardDetails": sorted(unmatched_card_details, key=lambda item: item["cardSourceId"]),
        "variantsByCardSourceId": variants_by_card,
    }
    args.output.write_text(json.dumps(output, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({key: output[key] for key in ("missingBefore", "cardsEnriched", "variantsAdded", "missingAfter", "unmatchedByReason", "unmatchedBySet", "unmatchedSets", "unsupportedPrintings")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
