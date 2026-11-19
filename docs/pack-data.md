# Building a pack from Scryfall

The mod does not ship printings. On first launch it downloads them and writes `config/spellblock/sets/{set}.json.gz`. A pack JSON only names pools and weights. This file is the checklist for the next pack. `scripts/pack_pools.py` and `SetCatalog.readCard` must stay on the same rules.

Do not use the oracle bulk for packs. That file is one printing per card name. Packs need every printing.

## 1. Fetch printings

Endpoint:

```
GET https://api.scryfall.com/cards/search?q=QUERY&unique=prints
```

Headers on every request:

```
User-Agent: SpellBlock/1.0 (https://github.com/NeuGoga/SpellBlock; unofficial fan project)
Accept: application/json
```

`unique=prints` is required. Without it Scryfall collapses to one printing per oracle id and the treatments disappear.

Paginate `has_more` / `next_page`. Sleep 100ms between pages. A 404 is an empty query, not a crash. Stay on `api.scryfall.com` (search) and `cards.scryfall.io` (images). Both already redirect inside scryfall.io.

```bash
python3 scripts/pack_pools.py "set:mh3" "set:spg cn>=39 cn<=48"
```

The script prints one line per pool (`common 80`, `special_guest 10`, ...). A pool the JSON names must be non-zero or opening a pack that rolls it fails closed.

One page, if you only want to see the shape:

```bash
UA='SpellBlock/1.0 (https://github.com/NeuGoga/SpellBlock; unofficial fan project)'
curl -sS -A "$UA" -H 'Accept: application/json' \
  --get 'https://api.scryfall.com/cards/search' \
  --data-urlencode 'q=set:spg cn>=39 cn<=48' \
  --data-urlencode 'unique=prints' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["total_cards"]);
[print(c["collector_number"], c["name"], c["set"], c["rarity"], c["booster"]) for c in d["data"]]'
```

`set:spg` with no collector window is the whole ongoing Special Guests series (about 175 cards), not MH3. MH3's ten guests are collector numbers 39 through 48, all mythic, all borderless, all `booster: false`, set code `spg`:

| cn | card |
|---|---|
| 39 | Thought-Knot Seer |
| 40 | Prismatic Ending |
| 41 | Dismember |
| 42 | Persist |
| 43 | Expressive Iteration |
| 44 | Solitude |
| 45 | Subtlety |
| 46 | Grief |
| 47 | Fury |
| 48 | Endurance |

## 2. Drop a printing

Throw the object away when any of these is true:

- `oversized` is true
- `layout` is `token`, `art_series`, `emblem`, or `double_faced_token`
- `games` is present and does not contain `paper`
- `rarity` is not `common`, `uncommon`, `rare`, or `mythic`
- `promo_types` hits `bundle`, `textured`, `ripplefoil`, `serialized`, `prerelease`, `promopack`, `stamped`, `datestamped`, `release`, `league`, `tourney`, `instore`, `event`, or `playtest`
- `frame_effects` contains `etched`, or `finishes` is only `etched` (those are the etched twin of a card already kept; foil in this mod is a finish flag, not a second row)
- treatment is `normal` and `booster` is false

Keep `boosterfun`, `portrait`, and `concept`. Concept Eldrazi and profile portraits are in the play sheet. Bundle basics, ripplefoil basics, and textured foils are not.

`booster: false` is normal for retro, borderless, and Special Guests. Only the normal frame uses the booster flag.

## 3. Name the treatment

First match wins:

1. `set == spg` → `special_guest`
2. `frame == 1997` → `retro`
3. `border_color == borderless`, or `frame_effects` contains `inverted`, `showcase`, or `extendedart` → `borderless`
4. else `normal`

## 4. File it into a pool

- `special_guest` → pool `special_guest` (rarity is ignored; all ten are mythic)
- `normal` → pool is the rarity: `common`, `uncommon`, `rare`, `mythic`
- anything else → `{treatment}_{rarity}` such as `retro_rare` or `borderless_mythic`
- a normal common whose type line contains `Basic` goes to `basic` and not to `common`

That last rule is why MH3 `common` is the sheet's 80 commons. The five booster basics (#304–308, full-art, black border, `booster: true`) would otherwise inflate `common` to 85. Bundle basics and ripplefoil basics are already dropped by the promo filter. There is no second object for the ten "regular" basics, so the land slot cannot split Eldrazi basics from regular basics. Both of those sheet rates draw pool `basic`.

Fields kept per printing: oracle id, printing id (Scryfall `id`), name, mana cost, mana value, type line, color identity, rarity, real set code, basic, treatment. The image URL is not stored.

Double-faced cards often have an empty top-level `image_uris` and put the art on `card_faces`. Mana cost and type fall back to face 0. The picture is still `cards.scryfall.io/png/front/{id[0]}/{id[1]}/{id}.png` using the card id, which is the front.

## 5. Turn the sheet into weights

Source: [Collecting Modern Horizons 3](https://magic.wizards.com/en/news/feature/collecting-modern-horizons-3) (May 21, 2024).

Weights are relative inside one slot. In `mh3_play.json` they sum to 1000, so a weight is a tenth of a percent. The slot rolls a pool, then a uniform printing from that pool.

| Slot | Sheet | JSON |
|---|---|---|
| 5 commons | always a common | `common` 1 |
| Guest or common | a Special Guest replaces a common in 1/64 packs (1.5625%) | `common` 984, `special_guest` 16 |
| Land | 80 commons 50%; regular basics 20% nonfoil + 13.3% foil; Eldrazi basics 10% nonfoil + 6.7% foil | `common` 500, `basic` 300, `basic` foil 200 |
| 3 uncommons | always uncommon | `uncommon` 1 |
| Rare | 60 rares 79.8%, 20 mythics 13.0%, retro 24R+8M is 2.1%, borderless 5.1% | `rare` 798, `mythic` 130, `retro_rare` 16, `retro_mythic` 5, `borderless_rare` 40, `borderless_mythic` 11 |
| New to Modern | 20U 75%, 18R 21.3%, 4M 2.3%, frame-break 0.8%, profile 0.3%, retro 2R+1M is 0.2%, one more borderless mythic under 0.1% | `uncommon` 750, `rare` 213, `mythic` 23, `borderless_rare` 8, `borderless_mythic` 4, `retro_rare` 1, `retro_mythic` 1 |
| Wildcard | commons 41.7%, uncommons 33.4% + DFC uncommons 8.3%, rares 6.7%, mythics 1.1% + commander mythics 4.2%, borderless 0.4%, retro 7C/16U/24R/8M is 4.2% | `common` 417, `uncommon` 417, `rare` 67, `mythic` 53, `borderless_rare` 3, `borderless_mythic` 1, `retro_common` 6, `retro_uncommon` 12, `retro_rare` 18, `retro_mythic` 6 |
| Foil | traditional foil of the wildcard, not Special Guests | the wildcard line with `"foil": true` |

Retro 2.1% split 24:8 is 1.6 and 0.5, stored as 16 and 5. The wildcard retro 4.2% split 7:16:24:8 of 55 rounds to 6/12/18/6. The sheet does not split the rare slot's 5.1% borderless into rare vs mythic; 40 and 11 keep the category at 5.1 and lean rare, which matches fetches and frame-breaks outnumbering mythics. DFC uncommons (8.3%) have no pool, so they are added to `uncommon` (33.4 + 8.3 = 41.7). Commander mythics (4.2%) have no pool, so they are added to `mythic` (1.1 + 4.2 = 5.3). Snow-Covered Wastes is under 0.1% and is not its own line.

What this still is not:

- The new-to-Modern slot is not the 42-card reprint list. It rolls those rarity odds against the whole set. There is no Scryfall flag for "new to Modern".
- H2R is 16 retro reprints (`set:h2r`, `booster: false`, frame 1997: 2C/6U/2R/6M). The play-sheet counts (7/16/24/8 and 24R+8M) do not include them. Leave `set:h2r` out of the play booster. Revisit it for collector boosters.
- Scryfall's MH3 retro frame is a couple of cards larger than the sheet's 24R+8M. Those extras stay in `retro_rare` / `retro_mythic` because the sheet does not name the ones to drop.
- Foil is `Finish.FOIL` on the same printing. It is not a second Scryfall row.

## 6. Add the pack

1. Run the script on the queries. Confirm `special_guest` is 10 for MH3, not ~175, and that every pool the JSON names is non-zero.
2. Write `src/main/resources/data/spellblock/packs/{id}.json` with `id`, `set`, `item`, `name`, `queries`, `slots`.
3. Add the id to `src/main/resources/data/spellblock/packs/index.json`.
4. Register the item, model, lang line, and texture. The texture path is `src/main/resources/assets/spellblock/textures/item/{item}.png`. Draw it. Do not trace Wizards pack art. Do not put card images in the jar.
5. If the classification rules changed, bump `SetPool.VERSION` so old `config/spellblock/sets/{set}.json.gz` files are rejected. The cache also stores the query list and redownloads when that list changes. Do not bump the oracle cache for a pack change.

Art, when a sleeve has a printing id:

```
https://cards.scryfall.io/png/front/{id[0]}/{id[1]}/{id}.png
```

PNG, not JPEG. `NativeImage.read` is the 1.21.1 decoder that is reliable here. The client caches card images under `<gameDir>/SpellBlock/art/{quality}/`, keeps textures bounded in memory, and does not follow redirects off `cards.scryfall.io`. `/spellblock grant` stores an empty printing id, so that copy has no art.
