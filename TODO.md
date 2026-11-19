# SpellBlock — Roadmap & Open Tasks

Beta 2 is playable for collecting, chest loot, crafting/dusting, trading, and freeform multi-seat table Magic (without an automated rules engine). This list tracks **remaining** work and community-friendly tasks.

## Community & Open Tasks

| Area | Task | Notes |
|---|---|---|
| Assets | Rework pack & booster box icons | Replace starter textures under `assets/spellblock/textures/item/` for booster packs and booster boxes |
| Worldgen / Economy | Trader structures & pack vendor blocks | Add world structures with NPC traders, villagers, or dedicated blocks that sell/trade booster packs and deck boxes |
| Blocks | Dustery block model & texture polish | Replace the current basic block texture/model with a custom block model |
| Content | Additional booster sets & collector products | Add more sets and collector booster variants (see [docs/pack-data.md](docs/pack-data.md)) |
| UI / UX | Guide & tooltip polish | Cleaner in-table help, concise hover tooltips, and layout refinements |
| Networking | Multiplayer stress-test | Four-seat tables, simultaneous reveals, search/scry sync, and edge-case fixes |
| Rules | Optional rules helpers / enforcement | **Deferred** until freeform beta play settles further |

## Known Technical Follow-ups

- Token kit coverage when Scryfall `all_parts` metadata is incomplete for a deck
- Client art cache disk footprint optimization (compressed on-disk storage)
- Optional Mod Menu / Cloth Config screen integration alongside **Options → SpellBlock…**

## Completed (Beta 1 & Beta 2)

- **Core Play & Table:** Multi-seat card tables (11 wood variants with automatic multi-block merge/break rebuild), hand, battlefield, graveyard, exile, scrollable scry/surveil/library search, control requests, life totals, commander damage, counters/loyalty, and tokens
- **Collection & Imports:** Scryfall oracle index, per-world Binder with favourites, Deck Boxes, and Moxfield / Archidekt deck importing
- **Economy & Loot:** World chest/barrel pack loot injection (`all_chests` + per-target overrides + per-rarity pack stack sizes), Dustery disenchanting and cross-printing crafting, borderless & Special Guest 2x treatment multipliers, per-set (`set_multipliers`) config, and player-to-player trading
- **Configs & Infrastructure:** Pre-generated `config/spellblock/*.json` settings layered with external datapacks, `SpellBlock/art` client cache directory, MIT license metadata, JUnit test suite, and GitHub Actions CI build
