/**
 * Card data types - represents Pokémon TCG card metadata from the API.
 */

export interface CardAttack {
  name: string;
  cost: string[];
  convertedEnergyCost: number;
  damage: string;
  text: string;
}

export interface CardAbility {
  name: string;
  text: string;
  type: string; // "Ability", "Poké-Power", etc.
}

export interface CardWeakness {
  type: string;
  value: string;
}

export interface CardResistance {
  type: string;
  value: string;
}

export interface CardImages {
  small: string;
  large: string;
}

export interface CardLegality {
  standard?: string; // "Legal", "Banned"
  expanded?: string;
  unlimited?: string;
}

export interface CardSet {
  id: string;
  name: string;
  series: string;
  printedTotal: number;
  total: number;
  ptcgoCode: string;
  releaseDate: string;
}

export type Supertype = 'Pokémon' | 'Trainer' | 'Energy';

export interface Card {
  // Core identity
  cardId: string; // e.g. "sv3-032"
  name: string;
  supertype: Supertype;
  subtypes: string[]; // e.g. ["Stage 2", "ex"]

  // Pokémon-specific
  hp: string | null;
  types: string[]; // e.g. ["Fire"]
  evolvesFrom: string | null;
  evolvesTo: string[];
  attacks: CardAttack[];
  abilities: CardAbility[];
  weaknesses: CardWeakness[];
  resistances: CardResistance[];
  retreatCost: string[];
  convertedRetreatCost: number;

  // Set / printing
  setId: string;
  setName: string;
  collectorNumber: string; // e.g. "032/197"
  rarity: string | null;
  regulationMark: string | null;

  // Legality
  legalities: CardLegality | null;

  // Images
  images: CardImages | null;

  // Misc
  artist: string | null;
  flavorText: string | null;
  nationalPokedexNumbers: number[];
  rules: string[];

  // Cache
  cachedAt: string | null; // ISO timestamp
}
