/**
 * Zustand store for scanner state management.
 */

import { create } from 'zustand';
import type { ScanResult, ScanCandidate } from '../types/scan';
import type { CardVariant } from '../types/inventory';

export type ScannerState =
  | 'IDLE'           // Camera ready, waiting for user action
  | 'CAPTURING'     // Taking photo
  | 'IDENTIFYING'   // Running OCR + API lookup
  | 'RESULT'        // Showing result (high confidence auto-accepted, or showing candidates)
  | 'ERROR';        // Something went wrong

interface ScannerStore {
  // State
  state: ScannerState;
  lastResult: ScanResult | null;
  lastMessage: string;
  totalScanned: number;
  uniqueCards: number;
  lastQuantity: number;

  // Actions
  setState: (state: ScannerState) => void;
  setResult: (result: ScanResult) => void;
  setMessage: (message: string) => void;
  setStats: (total: number, unique: number) => void;
  setLastQuantity: (qty: number) => void;
  reset: () => void;
}

export const useScannerStore = create<ScannerStore>((set) => ({
  state: 'IDLE',
  lastResult: null,
  lastMessage: '',
  totalScanned: 0,
  uniqueCards: 0,
  lastQuantity: 0,

  setState: (state) => set({ state }),
  setResult: (result) => set({ lastResult: result, state: 'RESULT' }),
  setMessage: (message) => set({ lastMessage: message }),
  setStats: (total, unique) => set({ totalScanned: total, uniqueCards: unique }),
  setLastQuantity: (qty) => set({ lastQuantity: qty }),
  reset: () => set({ state: 'IDLE', lastResult: null, lastMessage: '' }),
}));
