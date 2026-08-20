/**
 * Scan types - recognition results and scan history.
 */

export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW';

export interface ScanCandidate {
  cardId: string;
  name: string;
  setName: string;
  collectorNumber: string;
  confidenceScore: number; // 0.0 - 1.0
  imageUrl: string;
}

export interface ScanResult {
  cardId: string | null;
  name: string;
  setName: string;
  collectorNumber: string;
  confidence: Confidence;
  confidenceScore: number;
  candidates: ScanCandidate[];
  ocrName: string;
  ocrNumber: string;
}

export type ScanAction = 'add' | 'undo' | 'manual_add' | 'manual_remove';

export interface ScanHistoryEntry {
  id?: number;
  cardId: string;
  name: string;
  setName: string;
  collectorNumber: string;
  confidence: Confidence;
  confidenceScore: number;
  action: ScanAction;
  quantityChange: number; // +1 for add, -1 for undo
  timestamp: string; // ISO
  ocrName: string;
  ocrNumber: string;
}
