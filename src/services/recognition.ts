/**
 * OCR/Recognition service.
 * Uses @react-native-ml-kit/text-recognition to extract text from card images,
 * then parses collector number and card name to identify the card.
 */

import TextRecognition from '@react-native-ml-kit/text-recognition';
import type { Card } from '../types/card';
import type { ScanResult, ScanCandidate, Confidence } from '../types/scan';
import { findCard } from './pokemonApi';

/**
 * Process a captured image URI and attempt to identify the card.
 */
export async function recognizeCard(imageUri: string): Promise<ScanResult> {
  try {
    // Run ML Kit text recognition on the image
    const result = await TextRecognition.recognize(imageUri);
    const allText = result.text;
    const blocks = result.blocks;

    console.log('[Recognition] Full OCR text:', allText);

    // Extract collector number (bottom of card, format like "032/197" or "32/197")
    const collectorNumber = extractCollectorNumber(allText, blocks);

    // Extract card name (top of card, usually first prominent text)
    const cardName = extractCardName(allText, blocks);

    console.log('[Recognition] Extracted number:', collectorNumber);
    console.log('[Recognition] Extracted name:', cardName);

    if (!collectorNumber && !cardName) {
      return {
        cardId: null,
        name: '',
        setName: '',
        collectorNumber: '',
        confidence: 'LOW',
        confidenceScore: 0,
        candidates: [],
        ocrName: cardName || '',
        ocrNumber: collectorNumber || '',
      };
    }

    // Query the API with what we found
    const matches = await findCard(collectorNumber || '', cardName || '');

    if (matches.length === 0) {
      return {
        cardId: null,
        name: cardName || '',
        setName: '',
        collectorNumber: collectorNumber || '',
        confidence: 'LOW',
        confidenceScore: 0.1,
        candidates: [],
        ocrName: cardName || '',
        ocrNumber: collectorNumber || '',
      };
    }

    // Score and rank candidates
    const candidates = scoreCandidates(matches, collectorNumber, cardName);
    const best = candidates[0];

    return {
      cardId: best.cardId,
      name: best.name,
      setName: best.setName,
      collectorNumber: best.collectorNumber,
      confidence: best.confidenceScore >= 0.8 ? 'HIGH' :
                  best.confidenceScore >= 0.5 ? 'MEDIUM' : 'LOW',
      confidenceScore: best.confidenceScore,
      candidates: candidates.slice(0, 5),
      ocrName: cardName || '',
      ocrNumber: collectorNumber || '',
    };
  } catch (error) {
    console.error('[Recognition] Error:', error);
    return {
      cardId: null,
      name: '',
      setName: '',
      collectorNumber: '',
      confidence: 'LOW',
      confidenceScore: 0,
      candidates: [],
      ocrName: '',
      ocrNumber: '',
    };
  }
}

/**
 * Extract collector number from OCR text.
 * Looks for patterns like "032/197", "32/197", "SV032", etc.
 */
function extractCollectorNumber(fullText: string, blocks: any[]): string | null {
  // Pattern: digits/digits (most common format)
  const slashPattern = /(\d{1,3})\s*\/\s*(\d{1,3})/;

  // Search from bottom blocks first (collector number is at the bottom of cards)
  const sortedBlocks = [...blocks].sort((a, b) => {
    const aY = a.frame?.y || 0;
    const bY = b.frame?.y || 0;
    return bY - aY; // Bottom first
  });

  for (const block of sortedBlocks) {
    const text = block.text || '';
    const match = text.match(slashPattern);
    if (match) {
      return `${match[1]}/${match[2]}`;
    }
  }

  // Fallback: search the full text
  const fullMatch = fullText.match(slashPattern);
  if (fullMatch) {
    return `${fullMatch[1]}/${fullMatch[2]}`;
  }

  // Try just a standalone number (some promos don't have /total)
  const standaloneNum = fullText.match(/\b(\d{1,3})\b/);
  if (standaloneNum) {
    return standaloneNum[1];
  }

  return null;
}

/**
 * Extract card name from OCR text.
 * The card name is typically at the top of the card in large text.
 */
function extractCardName(fullText: string, blocks: any[]): string | null {
  // Sort blocks by Y position (top first)
  const sortedBlocks = [...blocks].sort((a, b) => {
    const aY = a.frame?.y || 0;
    const bY = b.frame?.y || 0;
    return aY - bY; // Top first
  });

  // The name is usually in the top 30% of the card
  // Look for text that looks like a Pokémon name (capitalized, reasonable length)
  for (const block of sortedBlocks.slice(0, 5)) {
    const lines = (block.text || '').split('\n');
    for (const line of lines) {
      const cleaned = line.trim();
      // Skip very short text, numbers-only, HP values, etc.
      if (cleaned.length < 3) continue;
      if (/^\d+$/.test(cleaned)) continue;
      if (/^HP\s*\d+/i.test(cleaned)) continue;
      if (/^\d+\s*\/\s*\d+/.test(cleaned)) continue;
      if (/^(BASIC|STAGE|TRAINER|ENERGY|SUPPORTER|ITEM)/i.test(cleaned)) continue;

      // Likely a card name
      return cleaned;
    }
  }

  return null;
}

/**
 * Score candidate cards based on how well they match OCR output.
 */
function scoreCandidates(
  cards: Card[],
  ocrNumber: string | null,
  ocrName: string | null
): ScanCandidate[] {
  return cards
    .map((card) => {
      let score = 0;

      // Number match (strongest signal)
      if (ocrNumber && card.collectorNumber) {
        const cleanOcr = ocrNumber.replace(/^0+/, '').split('/')[0];
        const cleanCard = card.collectorNumber.replace(/^0+/, '').split('/')[0];

        if (cleanOcr === cleanCard) {
          score += 0.6;
        } else if (cleanCard.includes(cleanOcr) || cleanOcr.includes(cleanCard)) {
          score += 0.3;
        }
      }

      // Name match
      if (ocrName && card.name) {
        const ocrLower = ocrName.toLowerCase();
        const cardLower = card.name.toLowerCase();

        if (ocrLower === cardLower) {
          score += 0.4;
        } else if (cardLower.includes(ocrLower) || ocrLower.includes(cardLower)) {
          score += 0.25;
        } else {
          // Fuzzy: check word overlap
          const ocrWords = ocrLower.split(/\s+/);
          const cardWords = cardLower.split(/\s+/);
          const overlap = ocrWords.filter((w) => cardWords.includes(w)).length;
          if (overlap > 0) {
            score += 0.15 * (overlap / Math.max(ocrWords.length, cardWords.length));
          }
        }
      }

      return {
        cardId: card.cardId,
        name: card.name,
        setName: card.setName,
        collectorNumber: card.collectorNumber,
        confidenceScore: Math.min(score, 1.0),
        imageUrl: card.images?.small || '',
      };
    })
    .sort((a, b) => b.confidenceScore - a.confidenceScore);
}
