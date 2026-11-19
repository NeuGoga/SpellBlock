# SpellBlock

Unofficial Fabric mod for **Minecraft 1.21.1**. Collect Magic: The Gathering cards from looted booster packs, disenchant spares or craft printings at a Dustery, trade with other players, and play on scripted multi-seat card tables.

Cards live in a **binder** or a **deck box** — never as inventory stacks of individual cards. Card data and images are fetched on demand from [Scryfall](https://scryfall.com/) and are not bundled inside the jar.

- **Community Discord:** https://discord.gg/GMVYwf3pT
- **Source & Issues:** https://github.com/NeuGoga/SpellBlock

## Features (Beta 2)

- **Binder & Scryfall Index** — Persistent per-player card collection with favourites, finishes (`NONFOIL`, `FOIL`, `ETCHED`), and live oracle indexing
- **Boosters, Boxes & World Loot** — 20 draft/play booster sets and 36-pack booster boxes with configurable chest & barrel loot injection across vanilla and modded loot tables
- **Dustery (Disenchant & Craft)** — Turn extra cards into Dust and craft any printing across normal, retro, borderless, and Special Guest treatments with configurable rarity, finish, treatment, and per-set multipliers
- **Player-to-Player Trading** — Live trade screen for exchanging cards and Dust (`/spellblock trade`)
- **Deck Boxes & Imports** — Import decks directly from Moxfield and Archidekt or build from your binder
- **Multi-Seat Card Tables** — 11 wood variants with dynamic merged table layouts, hand/battlefield/graveyard/exile zones, scrollable scry/surveil/library search, life totals, commander damage, counters, loyalty, and token spawning
- **Configurable Art Cache** — Choose `NORMAL`, `LARGE` JPEG, or full `PNG` in **Options → SpellBlock…**, cached outside `config/` under `<gameDir>/SpellBlock/art/`

## Requirements

- Minecraft **1.21.1**
- Java **21**
- Fabric Loader **0.20.1+** and Fabric API

## Commands

| Command | Permission | What |
|---|---|---|
| `/spellblock status` | op (level 2) | Oracle index and binder status |
| `/spellblock reindex` | op (level 2) | Rebuild the local oracle card index |
| `/spellblock grant <count> <card>` | op (level 2) | Add or remove cards in your binder |
| `/spellblock import moxfield <url\|id>` | op (level 2) | Import a Moxfield deck into a deck box |
| `/spellblock import archidekt <url\|id>` | op (level 2) | Import an Archidekt deck into a deck box |
| `/spellblock trade <player>` | player | Send or accept a trade request |
| `/spellblock trade cancel` | player | Cancel an active trade or pending request |
| `/spellblock config` | client | Open SpellBlock art settings (also **Options → SpellBlock…**) |

## Configuration

SpellBlock pre-generates editable JSON configs on first launch:

- **`config/spellblock.json`** — Client art quality (`NORMAL`, `LARGE`, `PNG`)
- **`config/spellblock/rules.json`** — Feature toggles (`dustery`, `dust`, `craft`, `trade`, `chest_loot`, `import`, `grant`) and trade limits
- **`config/spellblock/economy/dust.json`** & **`economy/craft.json`** — Rarity base values, finish/treatment multipliers (`borderless` and `special_guest` default to `2.0x`), per-set multipliers (`set_multipliers`), per-card overrides, and pinned recipes
- **`config/spellblock/loot/inject.json`** — Chest/barrel spawn switches, global/target chances, and per-rarity pack stack sizes
- **`config/spellblock/packs/rules.json`** & **`cards/disabled.json`** — Enable/disable packs or booster boxes, adjust pack loot weights, or ban specific cards/sets/types
- **`<gameDir>/SpellBlock/art/`** — Client downloaded card images per quality setting
- **`config/spellblock/sets/`** & **`config/spellblock/oracle*`** — Cached Scryfall set printings and oracle index

External datapacks under `data/spellblock/...` override `config/spellblock/` files. See [docs/STRUCTURE.md](docs/STRUCTURE.md#configuration--datapacks) for the full configuration reference.

## Docs

- [TODO.md](TODO.md) — Open tasks & community contribution ideas
- [docs/STRUCTURE.md](docs/STRUCTURE.md) — Codebase layout and configuration guide
- [docs/pack-data.md](docs/pack-data.md) — How booster pools and Scryfall queries work

## License & Disclaimer

Licensed under the [MIT License](LICENSE).

This is an unofficial fan project and is not affiliated with Wizards of the Coast or Hasbro. Magic: The Gathering is a trademark of Wizards of the Coast. Card metadata and artwork are retrieved from [Scryfall](https://scryfall.com/).
