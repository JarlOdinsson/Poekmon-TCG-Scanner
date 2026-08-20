/**
 * Export service - generate CSV and JSON from inventory.
 * Uses expo-file-system to write temp files, expo-sharing to share.
 */

import * as FileSystem from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { getAllInventory } from './database';
import type { InventoryEntryWithCard } from '../types/inventory';

/**
 * Export inventory as a clean, human-readable CSV.
 * Designed to be directly usable by an AI for deck-building analysis.
 */
export async function exportCSV(): Promise<string> {
  const inventory = await getAllInventory();

  const headers = [
    'name',
    'quantity',
    'supertype',
    'subtypes',
    'types',
    'set_name',
    'collector_number',
    'variant',
    'rarity',
    'regulation_mark',
    'card_id',
    'first_scanned',
    'last_scanned',
  ];

  const rows = inventory.map((item) => [
    csvEscape(item.name),
    item.quantity.toString(),
    csvEscape(item.supertype),
    csvEscape(item.subtypes.join('; ')),
    csvEscape(item.types.join('; ')),
    csvEscape(item.setName),
    csvEscape(item.collectorNumber),
    item.variant,
    csvEscape(item.rarity || ''),
    csvEscape(item.regulationMark || ''),
    item.cardId,
    item.firstScanned,
    item.lastScanned,
  ]);

  const csv = [headers.join(','), ...rows.map((r) => r.join(','))].join('\n');
  return csv;
}

/**
 * Export inventory as structured JSON.
 */
export async function exportJSON(): Promise<string> {
  const inventory = await getAllInventory();

  const exportData = {
    exportedAt: new Date().toISOString(),
    totalCards: inventory.reduce((sum, i) => sum + i.quantity, 0),
    uniqueCards: inventory.length,
    cards: inventory.map((item) => ({
      cardId: item.cardId,
      name: item.name,
      quantity: item.quantity,
      supertype: item.supertype,
      subtypes: item.subtypes,
      types: item.types,
      setName: item.setName,
      collectorNumber: item.collectorNumber,
      variant: item.variant,
      rarity: item.rarity,
      regulationMark: item.regulationMark,
      firstScanned: item.firstScanned,
      lastScanned: item.lastScanned,
    })),
  };

  return JSON.stringify(exportData, null, 2);
}

/**
 * Write CSV to a file and share it via the system share sheet.
 */
export async function shareCSV(): Promise<void> {
  const csv = await exportCSV();
  const filename = `pokemon_tcg_inventory_${dateStamp()}.csv`;
  const path = `${FileSystem.cacheDirectory}${filename}`;

  await FileSystem.writeAsStringAsync(path, csv, {
    encoding: FileSystem.EncodingType.UTF8,
  });

  await Sharing.shareAsync(path, {
    mimeType: 'text/csv',
    dialogTitle: 'Export Pokémon TCG Inventory',
    UTI: 'public.comma-separated-values-text',
  });
}

/**
 * Write JSON to a file and share it via the system share sheet.
 */
export async function shareJSON(): Promise<void> {
  const json = await exportJSON();
  const filename = `pokemon_tcg_inventory_${dateStamp()}.json`;
  const path = `${FileSystem.cacheDirectory}${filename}`;

  await FileSystem.writeAsStringAsync(path, json, {
    encoding: FileSystem.EncodingType.UTF8,
  });

  await Sharing.shareAsync(path, {
    mimeType: 'application/json',
    dialogTitle: 'Export Pokémon TCG Inventory',
    UTI: 'public.json',
  });
}

function csvEscape(value: string): string {
  if (value.includes(',') || value.includes('"') || value.includes('\n')) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

function dateStamp(): string {
  const d = new Date();
  return `${d.getFullYear()}${(d.getMonth() + 1).toString().padStart(2, '0')}${d.getDate().toString().padStart(2, '0')}`;
}
