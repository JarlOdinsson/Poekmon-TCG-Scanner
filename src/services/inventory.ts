/**
 * Inventory management service.
 * Handles add/increment, undo, decrement, search.
 */

import type { CardVariant, InventoryEntryWithCard } from '../types/inventory';
import type { ScanResult, ScanHistoryEntry } from '../types/scan';
import {
  upsertInventoryEntry,
  decrementInventoryEntry,
  getAllInventory,
  getInventoryStats,
  addScanHistoryEntry,
  getLastScanEntry,
  updateInventoryQuantity,
} from './database';

export interface AddCardResult {
  success: boolean;
  quantity: number;
  message: string;
}

/**
 * Accept a scan result and add it to inventory.
 */
export async function acceptScan(
  scan: ScanResult,
  variant: CardVariant = 'normal'
): Promise<AddCardResult> {
  if (!scan.cardId) {
    return { success: false, quantity: 0, message: 'No card identified' };
  }

  const entry = await upsertInventoryEntry(scan.cardId, variant);

  // Record in scan history
  await addScanHistoryEntry({
    cardId: scan.cardId,
    name: scan.name,
    setName: scan.setName,
    collectorNumber: scan.collectorNumber,
    confidence: scan.confidence,
    confidenceScore: scan.confidenceScore,
    action: 'add',
    quantityChange: 1,
    timestamp: new Date().toISOString(),
    ocrName: scan.ocrName,
    ocrNumber: scan.ocrNumber,
  });

  return {
    success: true,
    quantity: entry.quantity,
    message: `${scan.name} — ${scan.setName} ${scan.collectorNumber} — Qty: ${entry.quantity}`,
  };
}

/**
 * Manually add a card by ID (from search/selection).
 */
export async function manualAddCard(
  cardId: string,
  name: string,
  setName: string,
  collectorNumber: string,
  variant: CardVariant = 'normal'
): Promise<AddCardResult> {
  const entry = await upsertInventoryEntry(cardId, variant);

  await addScanHistoryEntry({
    cardId,
    name,
    setName,
    collectorNumber,
    confidence: 'HIGH',
    confidenceScore: 1.0,
    action: 'manual_add',
    quantityChange: 1,
    timestamp: new Date().toISOString(),
    ocrName: '',
    ocrNumber: '',
  });

  return {
    success: true,
    quantity: entry.quantity,
    message: `${name} — ${setName} ${collectorNumber} — Qty: ${entry.quantity}`,
  };
}

/**
 * Undo the last scan (decrement the most recently added card).
 */
export async function undoLastScan(): Promise<{ success: boolean; message: string }> {
  const lastScan = await getLastScanEntry();
  if (!lastScan) {
    return { success: false, message: 'Nothing to undo' };
  }

  const removed = await decrementInventoryEntry(lastScan.cardId, 'normal');
  if (!removed) {
    return { success: false, message: 'Could not undo — card not in inventory' };
  }

  // Record undo in history
  await addScanHistoryEntry({
    cardId: lastScan.cardId,
    name: lastScan.name,
    setName: lastScan.setName,
    collectorNumber: lastScan.collectorNumber,
    confidence: lastScan.confidence,
    confidenceScore: lastScan.confidenceScore,
    action: 'undo',
    quantityChange: -1,
    timestamp: new Date().toISOString(),
    ocrName: lastScan.ocrName,
    ocrNumber: lastScan.ocrNumber,
  });

  return {
    success: true,
    message: `Undid: ${lastScan.name} — ${lastScan.setName} ${lastScan.collectorNumber}`,
  };
}

/**
 * Set quantity for a specific card.
 */
export async function setCardQuantity(
  cardId: string,
  variant: CardVariant,
  quantity: number
): Promise<void> {
  await updateInventoryQuantity(cardId, variant, quantity);
}

/**
 * Get the full inventory list with card details.
 */
export async function getFullInventory(): Promise<InventoryEntryWithCard[]> {
  return getAllInventory();
}

/**
 * Get inventory statistics.
 */
export async function getStats(): Promise<{ totalCards: number; uniqueCards: number }> {
  return getInventoryStats();
}
