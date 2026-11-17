#!/usr/bin/env python3
"""check-set-numbers-scryfall.py — do a set file's card names and collector numbers match Scryfall?

    tools/check-set-numbers-scryfall.py StarTrek StarTrekCommander

The client downloads card images from Scryfall by set code + collector number (see
Mage.Client/.../ScryfallImageSupportCards.java), so a wrong number means a missing or wrong
image in the download tab. Run it for every set a fork-written card is added to.
Exits 1 if any line doesn't match.
"""
import json, re, sys, time, unicodedata, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def plain(name):
    """XMage strips accents from card names (Jotun Grunt, Lim-Dul): compare without them."""
    return "".join(c for c in unicodedata.normalize("NFKD", name or "") if not unicodedata.combining(c))


def scryfall_prints(code):
    out, url = {}, (f"https://api.scryfall.com/cards/search?q=set:{code.lower()}"
                    "&unique=prints&include_extras=true&include_variations=true")
    while url:
        req = urllib.request.Request(url, headers={"User-Agent": "xmage-fork-check", "Accept": "application/json"})
        data = json.load(urllib.request.urlopen(req))
        for c in data["data"]:
            out.setdefault(c["collector_number"], c["name"])
        url = data.get("next_page")
        time.sleep(0.12)  # Scryfall asks for 50-100 ms between requests
    return out


def main(sets):
    bad_total = 0
    for name in sets:
        src = (ROOT / "Mage.Sets/src/mage/sets" / f"{name}.java").read_text()
        code = re.search(r'super\("[^"]+", "([A-Z0-9]+)"', src).group(1)
        lines = re.findall(r'SetCardInfo\("((?:[^"\\]|\\.)*)", "?([0-9a-z★]+)"?,', src)
        prints = scryfall_prints(code)
        bad = []
        for card, num in lines:
            card = card.replace('\\"', '"')
            sf = prints.get(num)
            if plain(sf) != plain(card) and not plain(sf).startswith(plain(card.split(" //")[0])):
                bad.append((card, num, sf))
        print(f"{name} ({code}): {len(lines)} lines, {len(bad)} mismatched")
        for card, num, sf in bad:
            print(f"   #{num}: ours '{card}', Scryfall '{sf}'")
        bad_total += len(bad)
    return 1 if bad_total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["StarTrek", "StarTrekCommander"]))
