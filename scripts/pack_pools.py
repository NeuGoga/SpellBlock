#!/usr/bin/env python3
"""Print SpellBlock pack pools for one or more Scryfall queries.

Same drop and treatment rules as SetCatalog.readCard. This does not write the
mod cache. Run it before editing a pack JSON file.

    python3 scripts/pack_pools.py "set:mh3" "set:spg cn>=39 cn<=48"
"""

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "SpellBlock/1.0 (https://github.com/NeuGoga/SpellBlock; unofficial fan project)"
SKIP_PROMOS = {
    "bundle", "textured", "ripplefoil", "serialized", "prerelease", "promopack",
    "stamped", "datestamped", "release", "league", "tourney", "instore", "event", "playtest",
}


def search(query):
    url = "https://api.scryfall.com/cards/search?q=" + urllib.parse.quote(query) + "&unique=prints"
    cards = []
    first = True
    while url:
        if not first:
            time.sleep(0.1)
        first = False
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                root = json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return cards
            raise SystemExit(f"{query}: HTTP {error.code}") from error
        cards.extend(root.get("data") or [])
        url = root.get("next_page") if root.get("has_more") else None
    return cards


def effect(card, name):
    return name in (card.get("frame_effects") or [])


def keep(card):
    if card.get("oversized"):
        return False
    if card.get("layout") in {"token", "art_series", "emblem", "double_faced_token"}:
        return False
    games = card.get("games") or ["paper"]
    if "paper" not in games:
        return False
    if SKIP_PROMOS.intersection(card.get("promo_types") or []):
        return False
    finishes = card.get("finishes") or []
    if effect(card, "etched") or finishes == ["etched"]:
        return False
    if card.get("rarity") not in {"common", "uncommon", "rare", "mythic"}:
        return False
    if treatment(card) == "normal" and card.get("booster") is False:
        return False
    return bool(card.get("oracle_id") and card.get("id") and card.get("name"))


def treatment(card):
    if card.get("set") == "spg":
        return "special_guest"
    if card.get("frame") == "1997":
        return "retro"
    border = card.get("border_color")
    if border == "borderless" or effect(card, "inverted") or effect(card, "showcase") or effect(card, "extendedart"):
        return "borderless"
    return "normal"


def pool_key(card):
    kind = treatment(card)
    rarity = card.get("rarity") or ""
    type_line = card.get("type_line") or ""
    if not type_line and card.get("card_faces"):
        type_line = card["card_faces"][0].get("type_line") or ""
    if kind == "normal" and rarity == "common" and "Basic" in type_line:
        return "basic"
    if kind == "special_guest":
        return "special_guest"
    if kind == "normal":
        return rarity
    return f"{kind}_{rarity}"


def main(queries):
    seen = set()
    pools = {}
    guests = []
    for query in queries:
        for card in search(query):
            printing_id = card.get("id")
            if not printing_id or printing_id in seen or not keep(card):
                continue
            seen.add(printing_id)
            key = pool_key(card)
            pools.setdefault(key, []).append(card)
            if key == "special_guest":
                guests.append(card)
    print(f"{len(seen)} printings")
    for key in sorted(pools):
        print(f"{key} {len(pools[key])}")
    if guests:
        print("special guests:")
        for card in sorted(guests, key=lambda item: int(item.get("collector_number") or 0)):
            print(f"  {card.get('collector_number')} {card.get('name')}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: pack_pools.py 'set:mh3' ['set:spg cn>=39 cn<=48']")
    main(sys.argv[1:])
