from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field


@dataclass
class ValidationResult:
    warnings: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


def validate_database(connection: sqlite3.Connection) -> ValidationResult:
    result = ValidationResult()

    checks = {
        "foreign key violations": "SELECT COUNT(*) FROM pragma_foreign_key_check",
        "duplicate source IDs": "SELECT COUNT(*) FROM (SELECT source_id FROM cards GROUP BY source_id HAVING COUNT(*) > 1)",
        "duplicate internal IDs": "SELECT COUNT(*) FROM (SELECT internal_id FROM cards GROUP BY internal_id HAVING COUNT(*) > 1)",
        "orphan cards": "SELECT COUNT(*) FROM cards c LEFT JOIN sets s ON s.id=c.set_id WHERE s.id IS NULL",
        "empty card names": "SELECT COUNT(*) FROM cards WHERE trim(name)=''",
        "empty set names": "SELECT COUNT(*) FROM sets WHERE trim(name)=''",
        "missing collector numbers": "SELECT COUNT(*) FROM cards WHERE trim(collector_number)=''",
        "malformed variants": "SELECT COUNT(*) FROM card_variants WHERE trim(type)='' OR trim(display_name)=''",
        "variants without provenance": "SELECT COUNT(*) FROM card_variants WHERE trim(provenance_source)='' OR trim(evidence_status)=''",
        "orphan variants": "SELECT COUNT(*) FROM card_variants v LEFT JOIN cards c ON c.internal_id=v.card_id WHERE c.internal_id IS NULL",
    }
    for label, sql in checks.items():
        count = connection.execute(sql).fetchone()[0]
        if count:
            result.errors.append(f"{label}: {count}")

    integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
    if integrity != "ok":
        result.errors.append(f"integrity_check: {integrity}")

    no_variants = connection.execute(
        "SELECT COUNT(*) FROM cards c WHERE NOT EXISTS (SELECT 1 FROM card_variants v WHERE v.card_id=c.internal_id)"
    ).fetchone()[0]
    if no_variants:
        result.errors.append(f"cards without a catalogue variant identity: {no_variants}")

    unclassified = connection.execute(
        "SELECT COUNT(*) FROM card_variants WHERE evidence_status='unclassified'"
    ).fetchone()[0]
    if unclassified:
        result.warnings.append(
            f"cards with an explicit unclassified physical finish: {unclassified}; user confirmation is required"
        )

    no_images = connection.execute("SELECT COUNT(*) FROM cards WHERE image_small_url='' ").fetchone()[0]
    if no_images:
        result.warnings.append(f"cards without an English image URL: {no_images}")

    no_set_codes = connection.execute("SELECT COUNT(*) FROM sets WHERE normalized_code='' ").fetchone()[0]
    if no_set_codes:
        result.warnings.append(f"sets without a source set code: {no_set_codes}")

    return result
