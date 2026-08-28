/**
 * Pokémon TCG API client.
 * Queries pokemontcg.io v2, caches results in SQLite.
 */

import type { Card, CardAttack, CardAbility, CardWeakness, CardResistance } from '../types/card';
import { cacheCard, getCachedCard, searchCachedCards } from './database';

const API_BASE = 'https://api.pokemontcg.io/v2';

// Optional: set API key for higher rate limits
// Get one at https://dev.pokemontcg.io
let apiKey: string | null = null;

export function setApiKey(key: string): void {
  apiKey = key;
}

function getHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (apiKey) {
    headers['X-Api-Key'] = apiKey;
  }
  return headers;
}

/**
 * Convert a printed collector number such as "032/197" to the value used by
 * the Pokemon TCG API ("32").
 */
function normalizeCollectorNumber(value: string): string {
  return value.trim().split('/')[0].replace(/^0+(?=\d)/, '');
}

/**
 * Search for a card by collector number and/or name.
 * Checks cache first, then hits the API.
 */
export async function findCard(
  collectorNumber: string,
  name?: string,
  setId?: string
): Promise<Card[]> {
  // Try cache first
  const cached = await searchCachedCards(collectorNumber || name || '');
  if (cached.length > 0) {
    // Filter cached results for better match
    const filtered = cached.filter((c) => {
      const numberMatch = collectorNumber
        ? normalizeCollectorNumber(c.collectorNumber) ===
          normalizeCollectorNumber(collectorNumber)
        : true;
      const nameMatch = name
        ? c.name.toLowerCase().includes(name.toLowerCase())
        : true;
      return numberMatch && nameMatch;
    });
    if (filtered.length > 0) return filtered;
  }

  // Build API query
  const queryParts: string[] = [];

  if (collectorNumber) {
    // The API stores the number portion, not the printed "number/set total".
    const cleanNum = normalizeCollectorNumber(collectorNumber);
    queryParts.push(`number:${cleanNum}`);
  }

  if (name) {
    // Use quotes for exact name match attempt
    queryParts.push(`name:"${name}"`);
  }

  if (setId) {
    queryParts.push(`set.id:${setId}`);
  }

  if (queryParts.length === 0) return [];

  try {
    const results = await apiSearch(queryParts.join(' '));
    return results;
  } catch (error) {
    console.warn('[PokemonAPI] Search failed, trying fallback:', error);

    // Fallback: try just the number without name
    if (collectorNumber && name) {
      try {
        return await apiSearch(`number:${normalizeCollectorNumber(collectorNumber)}`);
      } catch {
        return [];
      }
    }
    return [];
  }
}

/**
 * Search the API by name (useful for manual correction).
 */
export async function searchByName(name: string): Promise<Card[]> {
  // Try cache first
  const cached = await searchCachedCards(name);
  if (cached.length >= 5) return cached.slice(0, 10);

  try {
    return await apiSearch(`name:"${name}*"`);
  } catch {
    return cached; // Fallback to whatever cache had
  }
}

/**
 * Get a specific card by its full ID (e.g. "sv3-032").
 */
export async function getCardById(cardId: string): Promise<Card | null> {
  // Check cache
  const cached = await getCachedCard(cardId);
  if (cached) return cached;

  try {
    const response = await fetch(`${API_BASE}/cards/${cardId}`, {
      headers: getHeaders(),
    });

    if (!response.ok) {
      console.warn(`[PokemonAPI] Card fetch failed: ${response.status}`);
      return null;
    }

    const json = await response.json();
    const card = apiCardToCard(json.data);
    await cacheCard(card);
    return card;
  } catch (error) {
    console.warn('[PokemonAPI] getCardById error:', error);
    return null;
  }
}

/**
 * Raw API search, caches results.
 */
async function apiSearch(query: string): Promise<Card[]> {
  const url = `${API_BASE}/cards?q=${encodeURIComponent(query)}&pageSize=10&orderBy=set.releaseDate`;

  const response = await fetch(url, { headers: getHeaders() });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  const json = await response.json();
  const cards: Card[] = (json.data || []).map(apiCardToCard);

  // Cache all results
  for (const card of cards) {
    try {
      await cacheCard(card);
    } catch (e) {
      // Don't fail the whole search if caching one card fails
      console.warn('[PokemonAPI] Cache write failed for', card.cardId, e);
    }
  }

  return cards;
}

/**
 * Convert API response card object to our Card type.
 */
function apiCardToCard(data: any): Card {
  return {
    cardId: data.id || '',
    name: data.name || '',
    supertype: data.supertype || '',
    subtypes: data.subtypes || [],
    hp: data.hp || null,
    types: data.types || [],
    evolvesFrom: data.evolvesFrom || null,
    evolvesTo: data.evolvesTo || [],
    attacks: (data.attacks || []).map((a: any): CardAttack => ({
      name: a.name || '',
      cost: a.cost || [],
      convertedEnergyCost: a.convertedEnergyCost || 0,
      damage: a.damage || '',
      text: a.text || '',
    })),
    abilities: (data.abilities || []).map((a: any): CardAbility => ({
      name: a.name || '',
      text: a.text || '',
      type: a.type || '',
    })),
    weaknesses: (data.weaknesses || []).map((w: any): CardWeakness => ({
      type: w.type || '',
      value: w.value || '',
    })),
    resistances: (data.resistances || []).map((r: any): CardResistance => ({
      type: r.type || '',
      value: r.value || '',
    })),
    retreatCost: data.retreatCost || [],
    convertedRetreatCost: data.convertedRetreatCost || 0,
    setId: data.set?.id || '',
    setName: data.set?.name || '',
    collectorNumber: data.number || '',
    rarity: data.rarity || null,
    regulationMark: data.regulationMark || null,
    legalities: data.legalities || null,
    images: data.images
      ? { small: data.images.small || '', large: data.images.large || '' }
      : null,
    artist: data.artist || null,
    flavorText: data.flavorText || null,
    nationalPokedexNumbers: data.nationalPokedexNumbers || [],
    rules: data.rules || [],
    cachedAt: new Date().toISOString(),
  };
}
