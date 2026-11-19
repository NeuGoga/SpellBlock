# SpellBlock Structure & Configuration

High-level map of the codebase and configuration files for contributors and pack authors. Booster pool rules live in [pack-data.md](pack-data.md).

## Source Sets

| Path | Role |
|---|---|
| `src/main/java` | Server + shared game logic |
| `src/client/java` | Screens, rendering, card art cache |
| `src/test/java` | Pure unit tests (runs without full MC client) |
| `src/main/resources` | Assets, recipes, loot tables, default datapack/config JSON |

## Main Packages (`com.neugoga.spellblock`)

| Package | Responsibility |
|---|---|
| `catalog` | Oracle index, Scryfall bulk download & client |
| `library` | Binder, deck boxes, finishes, dust pricing (`DustValues`), world persistence |
| `pack` | Set pools, booster/box rolls, token kit generation |
| `datapack` | Pre-generated config loader & datapack layering (`SpellBlockDataPacks`, `EconomyRules`, `LootConfig`, `ModRules`, `PackRules`, `CardRestrictions`) |
| `loot` | World chest/barrel loot injection (`PackRollLootEntry`, conditions) |
| `dustery` | Dustery block registration & interactions |
| `table` | Card table blocks, block entity, seats, zones, dynamic layout (`TableLayout`) |
| `network` | C2S/S2C payloads; Binder, Deck, Dustery, Trade, and `network.table.*` handlers |
| `item` | Deck boxes, packs, booster boxes, table items, data components |
| `command` | `/spellblock …` commands and Moxfield/Archidekt deck importer |
| `config` | Client settings (`SpellBlockConfig`, `ArtQuality`) |
| `mixin` | Server datapack/recipe hooks for table & loot integration |

## Client (`com.neugoga.spellblock.client`)

| Class / Area | Responsibility |
|---|---|
| `TableScreen`, `ScryPanel`, `GuidePanel` | Multi-seat tabletop play UI, scrollable search/scry/surveil rows |
| `ContextMenu`, `CountPrompt`, `Click` | Middle-click menus, numeric prompts, shared click helpers |
| `CardArt` | Throttled card image download, disk cache (`SpellBlock/art/`), GPU texture upload |
| `ArtConfigScreen` | Client art quality screen (`NORMAL`, `LARGE`, `PNG`) |
| `BinderScreen`, `DeckScreen`, `PackOpenScreen` | Collection browser, deck builder, booster opening |
| `DusteryScreen`, `TradeScreen` | Disenchant/craft station UI and player-to-player trade screen |
| `TableRenderer` | In-world card table cloth & multi-block rendering |
| `ui/Ui` | Shared colors, borders, and UI drawing helpers |

## Resources (`src/main/resources`)

| Path | Content |
|---|---|
| `data/spellblock/rules.json` | Default feature toggles and trade limits |
| `data/spellblock/economy/{dust,craft}.json` | Default Dustery disenchant & craft economy profiles |
| `data/spellblock/loot/inject.json` | Default chest/barrel loot injection config |
| `data/spellblock/packs/*.json` | Booster product definitions and pack rules |
| `data/spellblock/cards/disabled.json` | Default card ban lists |
| `data/spellblock/recipe/*.json` | Crafting recipes for tables, deck boxes, and Dustery |
| `data/spellblock/loot_table/**` | Block drops and internal pack roll loot table |
| `assets/spellblock/**` | Blockstates, models, item/block textures, lang |

---

## Configuration & Datapacks

SpellBlock automatically creates editable JSON files under `config/spellblock/` on first startup.

**Override priority (lowest → highest):**
1. Built-in jar defaults (`data/spellblock/...`)
2. Local config files (`config/spellblock/...`) — preserved across updates once created
3. External datapacks (`data/spellblock/...` inside world `datapacks/` or modpack pack loaders)

Changes to `config/spellblock/` or datapacks apply on server start and `/reload`. Malformed JSON logs a warning and keeps the last valid configuration.

### Client Settings (`config/spellblock.json`)
- `artQuality`: `"NORMAL"`, `"LARGE"` (default), or `"PNG"`. Controls resolution cached in `<gameDir>/SpellBlock/art/<quality>/`.

### Feature Toggles & Trading (`config/spellblock/rules.json`)
- `dustery`, `dust`, `craft`, `trade`, `chest_loot`, `import`, `grant`: Booleans enabling/disabling each system (`true` by default).
- `trade_cooldown_seconds` (`60`), `trade_max_distance` (`64`): Request cooldown and block distance limit (`1+`).

### Dustery Economy (`config/spellblock/economy/dust.json` & `craft.json`)
Values are computed as: `base * global_multiplier * (percent / 100) * finish_multiplier * treatment_multiplier * set_multiplier` (unless an unscaled override or pinned recipe applies).

- `enabled`: Enable dusting (`dust.json`) or crafting (`craft.json`).
- `allow_dust_all` (`dust.json`): Allow bulk disenchanting spares.
- `owned_only` (`craft.json`): Restrict crafting to cards already seen/owned in the binder.
- `allow_foil`, `allow_etched`, `allow_treatments` (`craft.json`): Restrict crafting specific finishes or treatments (`normal`, `retro`, `borderless`, `special_guest`).
- `max_batch` (`craft.json`, `1..64`): Max copies crafted per click.
- `multiplier` / `percent`: Global scalar (`1.0` and `100` by default).
- `rarity`: Base values per rarity (`common`, `uncommon`, `rare`, `mythic`, `special`, `bonus`).
- `finish_multipliers`: Multipliers for `nonfoil` (`1.0`), `foil` (`2.0`), `etched` (`2.0`).
- `treatment_multipliers`: Multipliers for `normal` (`1.0`), `retro` (`1.0`), `borderless` (`2.0`), `special_guest` (`2.0`).
- `set_multipliers`: Optional map of lowercase Scryfall set codes (e.g., `"lea": 3.0`, `"mh3": 1.5`) defaulting to `1.0` for all 20 built-in sets.
- `overrides`: Per-card values keyed by card name or `oracle_id` (number or `{ "cost"/"dust", "scale", "nonfoil", "foil", "etched" }`).
- `recipes` (`craft.json`): Pinned per-printing craft costs (`{ "printing_id", "name", "oracle_id", "cost", "scale" }`).

### World Chest & Barrel Loot (`config/spellblock/loot/inject.json`)
- `enabled`: Toggle chest/barrel pack loot globally (`true`).
- `all_chests`: Inject into any loot table under `chests/` across vanilla and modded chests (`true`), minus `excluded_targets`. Set to `false` to restrict strictly to `targets`.
- `chance` (`0.65`), `rolls` (`1`): Base roll chance and number of pack rolls per matching container.
- `box_enabled` (`true`), `box_chance` (`0.03`): Independent chance to roll a full booster box.
- `common_min`/`common_max` (`2`–`3`), `uncommon_min`/`uncommon_max` (`2`–`3`), `rare_min`/`rare_max` (`1`–`2`), `epic_min`/`epic_max` (`1`–`2`): Stack count range dropped when a pack of that item rarity rolls.
- `targets`, `excluded_targets`, `target_chances`, `target_rolls`: Per-loot-table overrides (e.g., higher odds in `end_city_treasure` and `ancient_city`).

### Pack Rules & Card Restrictions
- **`config/spellblock/packs/rules.json`**:
  - `disabled_packs` / `enabled_packs`: Filter by pack ID (`"mh3_play"`) or set selector (`"set:lea"`).
  - `allow_booster_boxes`: Global booster box loot toggle.
  - `loot_weights`: Weight per pack ID in loot rolls (`0` excludes a pack from chest rolls).
  - `overrides`: Per-pack `{ "enabled", "loot_weight", "allow_box" }`.
- **`config/spellblock/cards/disabled.json`**:
  - `disabled`: Block cards everywhere (pack rolls, grants, imports, crafting) by card name, `oracle_id`, `set:<code>`, `rarity:<r>`, or `type:<t>`.
  - `disabled_crafting`: Block only crafting in the Dustery using the same entry formats.

## Runtime Paths (not in git)

| Path | Content |
|---|---|
| `config/spellblock.json` | Client art quality |
| `<gameDir>/SpellBlock/art/{quality}/` | Client downloaded card art cache |
| `config/spellblock/sets/` | Cached Scryfall set printings (`{set}.json.gz`) |
| `config/spellblock/oracle*` | Scryfall oracle bulk data & local index |
| `config/spellblock/*.json` | Pre-generated gameplay & datapack override configs |

## Tests

Pure logic tests (deck math, layout partitioning, Dust/Craft economy math, loot roll config, built-in recipes, datapack layering) live under `src/test/java`. Run with:

```bash
./gradlew test build
```
