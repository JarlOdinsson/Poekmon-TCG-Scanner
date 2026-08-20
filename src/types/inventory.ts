/**
 * Inventory types - tracks owned card quantities and variants.
 */

export type CardVariant =
  | 'normal'
  | 'reverse_holo'
  | 'holo'
  | 'full_art'
  | 'other'
  | 'unknown';

export interface InventoryEntry {
  id?: number;
  cardId: string; // FK to cards
  variant: CardVariant;
  quantity: number;
  firstScanned: string; // ISO timestamp
  lastScanned: string; // ISO timestamp
  notes: string;
}

/**
 * Inventory entry enriched with card metadata for display.
 */
export interface InventoryEntryWithCard extends InventoryEntry {
  name: string;
  supertype: string;
  subtypes: string[];
  types: string[];
  setName: string;
  collectorNumber: string;
  rarity: string | null;
  regulationMark: string | null;
  imageSmall: string | null;
}
