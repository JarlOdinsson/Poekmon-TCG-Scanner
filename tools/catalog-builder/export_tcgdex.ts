import { mkdir, readFile, writeFile } from "node:fs/promises"
import { basename, dirname, resolve } from "node:path"
import { pathToFileURL } from "node:url"

const sourceRoot = resolve(process.argv[2])
const outputPath = resolve(process.argv[3])
const lang = "en"
const batchSize = 200

const util = await import(pathToFileURL(`${sourceRoot}/server/compiler/utils/util.ts`).href)
const variantUtil = await import(pathToFileURL(`${sourceRoot}/server/compiler/utils/variantUtil.ts`).href)
const translateModule = await import(pathToFileURL(`${sourceRoot}/server/compiler/utils/translationUtil.ts`).href)
const translate = translateModule.default

const english = (value: any): any => {
  if (value == null || typeof value !== "object" || Array.isArray(value)) return value
  return value.en ?? value[Object.keys(value)[0]]
}
const translated = (group: string, value: any): any => {
  if (value == null) return undefined
  try { return translate(group, value, lang) } catch { return value }
}

let assetIndex: any = {}
try {
  assetIndex = JSON.parse(await readFile(`${dirname(outputPath)}/tcgdex-assets.json`, "utf8"))
} catch {
  try {
    const response = await fetch("https://assets.tcgdex.net/datas.json")
    if (response.ok) assetIndex = await response.json()
  } catch {
    console.warn("asset index unavailable; image URLs will be omitted")
  }
}

const glob = new Bun.Glob("data/*/*/*.ts")
const paths = Array.from(glob.scanSync({ cwd: sourceRoot, absolute: true }))
const rawCards: Array<[string, any]> = []
for (let offset = 0; offset < paths.length; offset += batchSize) {
  const batch = paths.slice(offset, offset + batchSize)
  const loaded = await Promise.all(batch.map(async (path) => {
    const card = (await import(pathToFileURL(path).href)).default
    return [basename(path, ".ts"), card] as [string, any]
  }))
  for (const item of loaded) if (item[1]?.name?.en) rawCards.push(item)
  if ((offset + batch.length) % 1000 === 0 || offset + batch.length === paths.length) {
    console.log(`loaded ${offset + batch.length}/${paths.length} source card files`)
  }
}

const setsById = new Map<string, any>()
const cardCounts = new Map<string, number>()
for (const [, card] of rawCards) {
  setsById.set(card.set.id, card.set)
  cardCounts.set(card.set.id, (cardCounts.get(card.set.id) ?? 0) + 1)
}

const seriesById = new Map<string, any>()
for (const set of setsById.values()) seriesById.set(set.serie.id, set.serie)
const series = Array.from(seriesById.values()).map((serie) => ({ id: serie.id, name: english(serie.name) }))

const setImage = (set: any, kind: "logo" | "symbol") => {
  const scope = kind === "symbol" ? "univ" : lang
  if (!assetIndex?.[scope]?.[set.serie.id]?.[set.id]?.[kind]) return undefined
  return `https://assets.tcgdex.net/${scope}/${set.serie.id}/${set.id}/${kind}`
}
const sets = Array.from(setsById.values()).map((set) => ({
  id: set.id,
  name: english(set.name),
  serie: { id: set.serie.id, name: english(set.serie.name) },
  abbreviation: set.abbreviations ? { official: set.abbreviations.official, localized: english(set.abbreviations) } : undefined,
  tcgOnline: set.tcgOnline,
  cardCount: { official: set.cardCount?.official ?? 0, total: Math.max(set.cardCount?.official ?? 0, cardCounts.get(set.id) ?? 0) },
  releaseDate: english(set.releaseDate),
  legal: { standard: util.setIsLegal("standard", set), expanded: util.setIsLegal("expanded", set) },
  logo: setImage(set, "logo"),
  symbol: setImage(set, "symbol"),
}))

const variantDetails = (raw: any) => {
  if (raw == null) return []
  if (Array.isArray(raw)) return raw.map((variant) => ({
    ...variantUtil.formatVariant(variant, lang), variantId: variantUtil.variantToIdentifier(variant),
  }))
  const result: any[] = []
  for (const type of ["normal", "reverse", "holo"]) if (raw[type] === true) result.push({ type, size: "standard", variantId: type })
  if (raw.firstEdition === true) {
    for (const item of [...result]) result.push({ ...item, stamp: ["1st-edition"], variantId: `${item.variantId}-1st-edition` })
  }
  if (raw.wPromo === true) result.push({ type: "normal", size: "standard", stamp: ["w-Promo"], variantId: "normal-w-promo" })
  return result
}
const cardImage = (localId: string, card: any) =>
  assetIndex?.[lang]?.[card.set.serie.id]?.[card.set.id]?.[localId]
    ? `https://assets.tcgdex.net/${lang}/${card.set.serie.id}/${card.set.id}/${localId}` : undefined

const cards = rawCards.map(([localId, card]) => ({
  id: `${card.set.id}-${localId}`,
  localId,
  name: card.name.en,
  category: translated("category", card.category),
  rarity: translated("rarity", card.rarity),
  set: { id: card.set.id, name: english(card.set.name) },
  sourceVariants: card.variants ?? null,
  variants_detailed: variantDetails(card.variants),
  image: cardImage(localId, card),
  hp: card.hp,
  types: card.types?.map((value: any) => translated("types", value)),
  stage: translated("stage", card.stage),
  suffix: translated("suffix", card.suffix),
  trainerType: translated("trainerType", card.trainerType),
  energyType: translated("energyType", card.energyType),
  illustrator: card.illustrator,
  regulationMark: card.regulationMark,
  abilities: card.abilities?.map((ability: any) => ({ type: translated("abilityType", ability.type), name: english(ability.name), effect: english(ability.effect) })),
  attacks: card.attacks?.map((attack: any) => ({ cost: attack.cost?.map((value: any) => translated("types", value)), name: english(attack.name), effect: english(attack.effect), damage: attack.damage })),
  weaknesses: card.weaknesses?.map((item: any) => ({ type: translated("types", item.type), value: item.value })),
  resistances: card.resistances?.map((item: any) => ({ type: translated("types", item.type), value: item.value })),
  retreat: card.retreat,
  effect: english(card.effect),
  sourceRules: card.effect?.en ? [card.effect.en] : [],
  legal: { standard: util.cardIsLegal("standard", card, localId), expanded: util.cardIsLegal("expanded", card, localId) },
}))

await mkdir(dirname(outputPath), { recursive: true })
await writeFile(outputPath, JSON.stringify({ language: lang, series, sets, cards }))
console.log(`wrote ${cards.length} cards, ${sets.length} sets, ${series.length} series to ${outputPath}`)
