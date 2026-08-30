PRAGMA foreign_keys = ON;
PRAGMA journal_mode = DELETE;
PRAGMA synchronous = FULL;

CREATE TABLE catalog_metadata (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
) WITHOUT ROWID;

CREATE TABLE series (
    id TEXT PRIMARY KEY NOT NULL,
    source_id TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    language TEXT NOT NULL,
    sort_order INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE sets (
    id TEXT PRIMARY KEY NOT NULL,
    source_id TEXT NOT NULL UNIQUE,
    series_id TEXT NOT NULL REFERENCES series(id),
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    code TEXT NOT NULL DEFAULT '',
    normalized_code TEXT NOT NULL DEFAULT '',
    printed_total INTEGER NOT NULL DEFAULT 0,
    total INTEGER NOT NULL DEFAULT 0,
    release_date TEXT NOT NULL DEFAULT '',
    language TEXT NOT NULL,
    logo_url TEXT NOT NULL DEFAULT '',
    symbol_url TEXT NOT NULL DEFAULT '',
    standard_legal INTEGER NOT NULL DEFAULT 0 CHECK (standard_legal IN (0, 1)),
    expanded_legal INTEGER NOT NULL DEFAULT 0 CHECK (expanded_legal IN (0, 1))
) WITHOUT ROWID;

CREATE INDEX sets_series_id_idx ON sets(series_id);
CREATE INDEX sets_release_date_idx ON sets(release_date);
CREATE INDEX sets_normalized_code_idx ON sets(normalized_code);

CREATE TABLE cards (
    internal_id TEXT PRIMARY KEY NOT NULL,
    source_id TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    set_id TEXT NOT NULL REFERENCES sets(id),
    collector_number TEXT NOT NULL,
    normalized_collector_number TEXT NOT NULL,
    normalized_set_code TEXT NOT NULL DEFAULT '',
    normalized_set_name TEXT NOT NULL,
    rarity TEXT NOT NULL DEFAULT '',
    supertype TEXT NOT NULL,
    subtypes_json TEXT NOT NULL DEFAULT '[]',
    types_json TEXT NOT NULL DEFAULT '[]',
    hp TEXT NOT NULL DEFAULT '',
    artist TEXT NOT NULL DEFAULT '',
    regulation_mark TEXT NOT NULL DEFAULT '',
    language TEXT NOT NULL,
    abilities_json TEXT NOT NULL DEFAULT '[]',
    attacks_json TEXT NOT NULL DEFAULT '[]',
    rules_json TEXT NOT NULL DEFAULT '[]',
    retreat_cost INTEGER NOT NULL DEFAULT 0,
    weaknesses_json TEXT NOT NULL DEFAULT '[]',
    resistances_json TEXT NOT NULL DEFAULT '[]',
    image_small_url TEXT NOT NULL DEFAULT '',
    image_large_url TEXT NOT NULL DEFAULT '',
    standard_legal INTEGER NOT NULL DEFAULT 0 CHECK (standard_legal IN (0, 1)),
    expanded_legal INTEGER NOT NULL DEFAULT 0 CHECK (expanded_legal IN (0, 1)),
    release_date TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
) WITHOUT ROWID;

CREATE INDEX cards_set_id_idx ON cards(set_id);
CREATE INDEX cards_normalized_name_idx ON cards(normalized_name);
CREATE INDEX cards_normalized_number_idx ON cards(normalized_collector_number);
CREATE INDEX cards_set_code_number_idx ON cards(normalized_set_code, normalized_collector_number);
CREATE INDEX cards_regulation_mark_idx ON cards(regulation_mark);

CREATE TABLE card_variants (
    id TEXT PRIMARY KEY NOT NULL,
    card_id TEXT NOT NULL REFERENCES cards(internal_id) ON DELETE CASCADE,
    source_variant_id TEXT NOT NULL DEFAULT '',
    type TEXT NOT NULL,
    subtype TEXT NOT NULL DEFAULT '',
    display_name TEXT NOT NULL,
    size TEXT NOT NULL DEFAULT 'standard',
    foil TEXT NOT NULL DEFAULT '',
    stamps_json TEXT NOT NULL DEFAULT '[]',
    is_first_edition INTEGER NOT NULL DEFAULT 0 CHECK (is_first_edition IN (0, 1)),
    is_stamped INTEGER NOT NULL DEFAULT 0 CHECK (is_stamped IN (0, 1)),
    is_prerelease INTEGER NOT NULL DEFAULT 0 CHECK (is_prerelease IN (0, 1)),
    language TEXT NOT NULL,
    evidence_status TEXT NOT NULL CHECK (evidence_status IN ('verified', 'unclassified')),
    provenance_source TEXT NOT NULL,
    provenance_ref TEXT NOT NULL DEFAULT '',
    tcgplayer_id TEXT NOT NULL DEFAULT '',
    cardmarket_id TEXT NOT NULL DEFAULT '',
    cardtrader_id TEXT NOT NULL DEFAULT ''
) WITHOUT ROWID;

CREATE INDEX card_variants_card_id_idx ON card_variants(card_id);

CREATE VIRTUAL TABLE card_search USING fts5(
    internal_id UNINDEXED,
    card_name,
    collector_number,
    set_name,
    set_code,
    rarity,
    artist,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE VIEW card_app AS
SELECT
    c.internal_id AS id,
    c.name AS name,
    c.set_id AS setId,
    s.name AS setName,
    s.code AS setCode,
    c.collector_number AS collectorNumber,
    c.supertype AS supertype,
    trim(replace(replace(replace(c.subtypes_json, '[', ''), ']', ''), '"', '')) AS subtypes,
    trim(replace(replace(replace(c.types_json, '[', ''), ']', ''), '"', '')) AS types,
    c.rarity AS rarity,
    c.regulation_mark AS regulationMark,
    c.artist AS artist,
    c.language AS language,
    s.printed_total AS setPrintedTotal,
    s.total AS setTotal,
    COALESCE((SELECT group_concat(v.display_name, ',') FROM card_variants v WHERE v.card_id = c.internal_id), '') AS variants,
    c.standard_legal AS standardLegal,
    c.expanded_legal AS expandedLegal,
    c.image_small_url AS imageUrl
FROM cards c
JOIN sets s ON s.id = c.set_id;
