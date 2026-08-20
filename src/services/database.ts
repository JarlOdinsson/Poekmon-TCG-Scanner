/**
 * SQLite database layer using expo-sqlite.
 * Tables: cards (cache), inventory, scan_history, settings.
 */

import * as SQLite from 'expo-sqlite';
import type { Card, CardAttack, CardAbility, CardWeakness, CardResistance } from '../types/card';
import type { InventoryEntry, CardVariant, InventoryEntryWithCard } from '../types/inventory';
import type { ScanHistoryEntry, Confidence, ScanAction } from '../types/scan';

let db: SQLite.SQLiteDatabase | null = null;

export async function getDatabase(): Promise<SQLite.SQLiteDatabase> {
  if (!db) {
    db = await SQLite.openDatabaseAsync('pokemon_tcg_scanner.db');
    await initializeSchema(db);
  }
  return db;
}

async function initializeSchema(database: SQLite.SQLiteDatabase): Promise<void> {
  await database.execAsync(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;

    CREATE TABLE IF NOT EXISTS cards (
      card_id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      supertype TEXT NOT NULL,
      subtypes TEXT DEFAULT '[]',
      hp TEXT,
      types TEXT DEFAULT '[]',
      evolves_from TEXT,
      evolves_to TEXT DEFAULT '[]',
      attacks TEXT DEFAULT '[]',
      abilities TEXT DEFAULT '[]',
      weaknesses TEXT DEFAULT '[]',
      resistances TEXT DEFAULT '[]',
      retreat_cost TEXT DEFAULT '[]',
      converted_retreat_cost INTEGER DEFAULT 0,
      set_id TEXT DEFAULT '',
      set_name TEXT DEFAULT '',
      collector_number TEXT DEFAULT '',
      rarity TEXT,
      regulation_mark TEXT,
      legalities TEXT,
      images TEXT,
      artist TEXT,
      flavor_text TEXT,
      national_pokedex_numbers TEXT DEFAULT '[]',
      rules TEXT DEFAULT '[]',
      cached_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS inventory (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      card_id TEXT NOT NULL,
      variant TEXT DEFAULT 'normal',
      quantity INTEGER DEFAULT 1,
      first_scanned TEXT NOT NULL,
      last_scanned TEXT NOT NULL,
      notes TEXT DEFAULT '',
      UNIQUE(card_id, variant)
    );

    CREATE TABLE IF NOT EXISTS scan_history (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      card_id TEXT NOT NULL,
      name TEXT DEFAULT '',
      set_name TEXT DEFAULT '',
      collector_number TEXT DEFAULT '',
      confidence TEXT DEFAULT 'LOW',
      confidence_score REAL DEFAULT 0.0,
      action TEXT DEFAULT 'add',
      quantity_change INTEGER DEFAULT 1,
      timestamp TEXT NOT NULL,
      ocr_name TEXT DEFAULT '',
      ocr_number TEXT DEFAULT ''
    );

    CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_inventory_card_id ON inventory(card_id);
    CREATE INDEX IF NOT EXISTS idx_scan_history_timestamp ON scan_history(timestamp);
    CREATE INDEX IF NOT EXISTS idx_cards_name ON cards(name);
    CREATE INDEX IF NOT EXISTS idx_cards_collector_number ON cards(collector_number);
  `);
}

// ─── Card Cache CRUD ────────────────────────────────────────────────────────

export async function cacheCard(card: Card): Promise<void> {
  const database = await getDatabase();
  await database.runAsync(
    `INSERT OR REPLACE INTO cards (
      card_id, name, supertype, subtypes, hp, types,
      evolves_from, evolves_to, attacks, abilities,
      weaknesses, resistances, retreat_cost, converted_retreat_cost,
      set_id, set_name, collector_number, rarity, regulation_mark,
      legalities, images, artist, flavor_text,
      national_pokedex_numbers, rules, cached_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    card.cardId,
    card.name,
    card.supertype,
    JSON.stringify(card.subtypes),
    card.hp,
    JSON.stringify(card.types),
    card.evolvesFrom,
    JSON.stringify(card.evolvesTo),
    JSON.stringify(card.attacks),
    JSON.stringify(card.abilities),
    JSON.stringify(card.weaknesses),
    JSON.stringify(card.resistances),
    JSON.stringify(card.retreatCost),
    card.convertedRetreatCost,
    card.setId,
    card.setName,
    card.collectorNumber,
    card.rarity,
    card.regulationMark,
    card.legalities ? JSON.stringify(card.legalities) : null,
    card.images ? JSON.stringify(card.images) : null,
    card.artist,
    card.flavorText,
    JSON.stringify(card.nationalPokedexNumbers),
    JSON.stringify(card.rules),
    new Date().toISOString()
  );
}

export async function getCachedCard(cardId: string): Promise<Card | null> {
  const database = await getDatabase();
  const row = await database.getFirstAsync<any>(
    'SELECT * FROM cards WHERE card_id = ?',
    cardId
  );
  if (!row) return null;
  return rowToCard(row);
}

export async function searchCachedCards(query: string): Promise<Card[]> {
  const database = await getDatabase();
  const rows = await database.getAllAsync<any>(
    `SELECT * FROM cards WHERE name LIKE ? OR collector_number LIKE ? LIMIT 20`,
    `%${query}%`,
    `%${query}%`
  );
  return rows.map(rowToCard);
}

function rowToCard(row: any): Card {
  return {
    cardId: row.card_id,
    name: row.name,
    supertype: row.supertype,
    subtypes: JSON.parse(row.subtypes || '[]'),
    hp: row.hp,
    types: JSON.parse(row.types || '[]'),
    evolvesFrom: row.evolves_from,
    evolvesTo: JSON.parse(row.evolves_to || '[]'),
    attacks: JSON.parse(row.attacks || '[]') as CardAttack[],
    abilities: JSON.parse(row.abilities || '[]') as CardAbility[],
    weaknesses: JSON.parse(row.weaknesses || '[]') as CardWeakness[],
    resistances: JSON.parse(row.resistances || '[]') as CardResistance[],
    retreatCost: JSON.parse(row.retreat_cost || '[]'),
    convertedRetreatCost: row.converted_retreat_cost || 0,
    setId: row.set_id || '',
    setName: row.set_name || '',
    collectorNumber: row.collector_number || '',
    rarity: row.rarity,
    regulationMark: row.regulation_mark,
    legalities: row.legalities ? JSON.parse(row.legalities) : null,
    images: row.images ? JSON.parse(row.images) : null,
    artist: row.artist,
    flavorText: row.flavor_text,
    nationalPokedexNumbers: JSON.parse(row.national_pokedex_numbers || '[]'),
    rules: JSON.parse(row.rules || '[]'),
    cachedAt: row.cached_at,
  };
}

// ─── Inventory CRUD ─────────────────────────────────────────────────────────

export async function getInventoryEntry(
  cardId: string,
  variant: CardVariant = 'normal'
): Promise<InventoryEntry | null> {
  const database = await getDatabase();
  const row = await database.getFirstAsync<any>(
    'SELECT * FROM inventory WHERE card_id = ? AND variant = ?',
    cardId,
    variant
  );
  if (!row) return null;
  return rowToInventoryEntry(row);
}

export async function upsertInventoryEntry(
  cardId: string,
  variant: CardVariant = 'normal'
): Promise<InventoryEntry> {
  const database = await getDatabase();
  const now = new Date().toISOString();

  // Try to increment existing
  const existing = await getInventoryEntry(cardId, variant);
  if (existing) {
    await database.runAsync(
      'UPDATE inventory SET quantity = quantity + 1, last_scanned = ? WHERE card_id = ? AND variant = ?',
      now,
      cardId,
      variant
    );
    return { ...existing, quantity: existing.quantity + 1, lastScanned: now };
  }

  // Insert new
  const result = await database.runAsync(
    'INSERT INTO inventory (card_id, variant, quantity, first_scanned, last_scanned) VALUES (?, ?, 1, ?, ?)',
    cardId,
    variant,
    now,
    now
  );

  return {
    id: result.lastInsertRowId,
    cardId,
    variant,
    quantity: 1,
    firstScanned: now,
    lastScanned: now,
    notes: '',
  };
}

export async function decrementInventoryEntry(
  cardId: string,
  variant: CardVariant = 'normal'
): Promise<boolean> {
  const database = await getDatabase();
  const existing = await getInventoryEntry(cardId, variant);
  if (!existing || existing.quantity <= 0) return false;

  if (existing.quantity === 1) {
    // Remove the row entirely
    await database.runAsync(
      'DELETE FROM inventory WHERE card_id = ? AND variant = ?',
      cardId,
      variant
    );
  } else {
    await database.runAsync(
      'UPDATE inventory SET quantity = quantity - 1 WHERE card_id = ? AND variant = ?',
      cardId,
      variant
    );
  }
  return true;
}

export async function updateInventoryQuantity(
  cardId: string,
  variant: CardVariant,
  newQuantity: number
): Promise<void> {
  const database = await getDatabase();
  if (newQuantity <= 0) {
    await database.runAsync(
      'DELETE FROM inventory WHERE card_id = ? AND variant = ?',
      cardId,
      variant
    );
  } else {
    await database.runAsync(
      'UPDATE inventory SET quantity = ? WHERE card_id = ? AND variant = ?',
      newQuantity,
      cardId,
      variant
    );
  }
}

export async function getAllInventory(): Promise<InventoryEntryWithCard[]> {
  const database = await getDatabase();
  const rows = await database.getAllAsync<any>(`
    SELECT
      i.id, i.card_id, i.variant, i.quantity, i.first_scanned, i.last_scanned, i.notes,
      c.name, c.supertype, c.subtypes, c.types, c.set_name, c.collector_number,
      c.rarity, c.regulation_mark, c.images
    FROM inventory i
    LEFT JOIN cards c ON i.card_id = c.card_id
    ORDER BY i.last_scanned DESC
  `);

  return rows.map((row) => {
    const images = row.images ? JSON.parse(row.images) : null;
    return {
      id: row.id,
      cardId: row.card_id,
      variant: row.variant as CardVariant,
      quantity: row.quantity,
      firstScanned: row.first_scanned,
      lastScanned: row.last_scanned,
      notes: row.notes || '',
      name: row.name || 'Unknown',
      supertype: row.supertype || '',
      subtypes: JSON.parse(row.subtypes || '[]'),
      types: JSON.parse(row.types || '[]'),
      setName: row.set_name || '',
      collectorNumber: row.collector_number || '',
      rarity: row.rarity,
      regulationMark: row.regulation_mark,
      imageSmall: images?.small || null,
    };
  });
}

export async function getInventoryStats(): Promise<{
  totalCards: number;
  uniqueCards: number;
}> {
  const database = await getDatabase();
  const result = await database.getFirstAsync<any>(
    'SELECT COALESCE(SUM(quantity), 0) as total, COUNT(*) as unique_count FROM inventory'
  );
  return {
    totalCards: result?.total || 0,
    uniqueCards: result?.unique_count || 0,
  };
}

function rowToInventoryEntry(row: any): InventoryEntry {
  return {
    id: row.id,
    cardId: row.card_id,
    variant: row.variant as CardVariant,
    quantity: row.quantity,
    firstScanned: row.first_scanned,
    lastScanned: row.last_scanned,
    notes: row.notes || '',
  };
}

// ─── Scan History ───────────────────────────────────────────────────────────

export async function addScanHistoryEntry(entry: Omit<ScanHistoryEntry, 'id'>): Promise<number> {
  const database = await getDatabase();
  const result = await database.runAsync(
    `INSERT INTO scan_history (
      card_id, name, set_name, collector_number,
      confidence, confidence_score, action, quantity_change,
      timestamp, ocr_name, ocr_number
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    entry.cardId,
    entry.name,
    entry.setName,
    entry.collectorNumber,
    entry.confidence,
    entry.confidenceScore,
    entry.action,
    entry.quantityChange,
    entry.timestamp,
    entry.ocrName,
    entry.ocrNumber
  );
  return result.lastInsertRowId;
}

export async function getLastScanEntry(): Promise<ScanHistoryEntry | null> {
  const database = await getDatabase();
  const row = await database.getFirstAsync<any>(
    "SELECT * FROM scan_history WHERE action = 'add' ORDER BY id DESC LIMIT 1"
  );
  if (!row) return null;
  return rowToScanHistory(row);
}

export async function getRecentScans(limit: number = 50): Promise<ScanHistoryEntry[]> {
  const database = await getDatabase();
  const rows = await database.getAllAsync<any>(
    'SELECT * FROM scan_history ORDER BY id DESC LIMIT ?',
    limit
  );
  return rows.map(rowToScanHistory);
}

function rowToScanHistory(row: any): ScanHistoryEntry {
  return {
    id: row.id,
    cardId: row.card_id,
    name: row.name,
    setName: row.set_name,
    collectorNumber: row.collector_number,
    confidence: row.confidence as Confidence,
    confidenceScore: row.confidence_score,
    action: row.action as ScanAction,
    quantityChange: row.quantity_change,
    timestamp: row.timestamp,
    ocrName: row.ocr_name,
    ocrNumber: row.ocr_number,
  };
}

// ─── Settings ───────────────────────────────────────────────────────────────

export async function getSetting(key: string, defaultValue: string = ''): Promise<string> {
  const database = await getDatabase();
  const row = await database.getFirstAsync<any>(
    'SELECT value FROM settings WHERE key = ?',
    key
  );
  return row?.value ?? defaultValue;
}

export async function setSetting(key: string, value: string): Promise<void> {
  const database = await getDatabase();
  await database.runAsync(
    'INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)',
    key,
    value
  );
}
