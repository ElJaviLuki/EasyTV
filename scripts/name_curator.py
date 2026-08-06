#!/usr/bin/env python3
"""Curate display-oriented names from distinct_names.json.

Rules run as a pipeline (each may rewrite the string).

Usage:
  python scripts/name_curator.py
  python scripts/name_curator.py --in secrets/distinct_names.json --out secrets/curated_distinct_names.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_IN = ROOT / "secrets" / "distinct_names.json"
DEFAULT_OUT = ROOT / "secrets" / "curated_distinct_names.json"

# (pattern, replacement) — applied in order; \\1 group refs allowed.
RULES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"^\s*ES(?:\s+4k)?-\s*(.*)\s*$", re.IGNORECASE), r"\1"),
    (re.compile(r"^\s*ES\s+\([A-Z]+\)\s*", re.IGNORECASE), r""),
    (re.compile(r"^\s*ES\s+", re.IGNORECASE), r""),
    (re.compile(r"\s+ES\s*$", re.IGNORECASE), r""),
    (re.compile(r"\s*\(ES\)\s*", re.IGNORECASE), r" "),
    (re.compile(r":\s*$", re.IGNORECASE), r""),

    (re.compile(r"\b((UHD|4K|HDR|SD|HD|FHD)\/)*(UHD|4K|HDR|SD|HD|FHD)\b", re.IGNORECASE), r""),
    # Quality glued to channel number: "Eurosport 1HD" → "Eurosport 1"
    (re.compile(r"(?<=\d)(?:UHD|4K|HDR|SD|HD|FHD)\b", re.IGNORECASE), r""),
    (re.compile(r"\s*\(backup\s*(?:[0-9]+)?\s*\)\s*$", re.IGNORECASE), r""),
    (re.compile(r"\s*\((?:Orange|Movistar\+|M\+|LaLiga\+)\)\s*$", re.IGNORECASE), r""),

    # Non-Movistar names but channels packed by Movistar; remove Movistar prefix.
    (re.compile(r"^\s*M\s+A3SERIES", re.IGNORECASE), "A3SERIES"),
    (re.compile(r"^\s*M\s+BOM\s+CINE", re.IGNORECASE), "BOM CINE"),
    (re.compile(r"^\s*M\s+DARK", re.IGNORECASE), "DARK"),
    (re.compile(r"^\s*M\s+WB\s*tv", re.IGNORECASE), "Warner Bros. TV"),
    (re.compile(r"^\s*M\s+Movistar\+", re.IGNORECASE), "Movistar+"),
    (re.compile(r"^\s*M\s+LALIGATV", re.IGNORECASE), "LALIGATV"),
    (re.compile(r"^\s*M\s+SKY\s+SHOWTIME", re.IGNORECASE), "SKY SHOWTIME"),
    (re.compile(r"^\s*M\s+WARNER\s+TV", re.IGNORECASE), "Warner Bros. TV"),

    # Typo corrections
    (re.compile(r"^\s*Canal\s+Sur\s+A\s*$", re.IGNORECASE), "Canal Sur"),
    (re.compile(r"^\s*CanalSur\s*$", re.IGNORECASE), "Canal Sur"),
    (re.compile(r"^\s*(?:CANALE?\s+)?EXTREMADURA(?:\s*TV)?\s*$", re.IGNORECASE), "Canal Extremadura"),
    (re.compile(r"^\s*LA Sexta", re.IGNORECASE), "laSexta"),
    (re.compile(r"\bAVTAEL|AVATEL\b", re.IGNORECASE), r""),
    (re.compile(r"\bLALIGA\s+HYPERMOTION\s+TV\b", re.IGNORECASE), "LALIGATV HYPERMOTION"),
    (re.compile(r"^\s*24H(?:\s*TVE)?\s*$", re.IGNORECASE), "24 Horas"),
    (re.compile(r"^\s*24\s*Horas\s*$", re.IGNORECASE), "24 Horas"),
    (re.compile(r"Divinty", re.IGNORECASE), "Divinity"),

    # Same Spanish channel, different IPTV spellings
    (re.compile(r"^\s*Clan(?:\s*Tve|\s*TV)?\s*$", re.IGNORECASE), "Clan"),
    (re.compile(r"^\s*Warner(?:\s+Bros\.?)?\s*TV\s*$", re.IGNORECASE), "Warner Bros. TV"),
    (re.compile(r"^\s*Real\s+Madrid(?:\s*TV)?\s*$", re.IGNORECASE), "Real Madrid TV"),
    (re.compile(r"^\s*Disney\s*(?:Junior|Jr\.?)\s*$", re.IGNORECASE), "Disney Junior"),
    (re.compile(r"^\s*Disney\s+(?:Channel|Chanel|Ch)\s*$", re.IGNORECASE), "Disney Channel"),
    (re.compile(r"^\s*IB\s*3(?:\s*Global)?\s*$", re.IGNORECASE), "IB3 Global"),
    (re.compile(r"^\s*TV\s*Canarias?\s*$", re.IGNORECASE), "TV Canaria"),
    (re.compile(r"^\s*Sol\s*M(?:ú|u|fa)?sica\s*$", re.IGNORECASE), "Sol Música"),
    (re.compile(r"^\s*GOL\s*TV\s*$", re.IGNORECASE), "GOL PLAY"),
    (re.compile(r"^\s*#?\s*Vamos\s*$", re.IGNORECASE), "Vamos 1"),
    (re.compile(r"^\s*(?:TDP|TELEDEPORTES?|Teledeporte)\s*$", re.IGNORECASE), "TELEDEPORTE"),
    (re.compile(r"^\s*Nick\s*(?:Junior|Jr\.?)\s*$", re.IGNORECASE), "Nick Junior"),
    # After Nick Junior: bare Nick ≡ Nickelodeon (do not use a loose Nick\b here).
    (re.compile(r"^\s*(?:Nickelodeon|Nick)\s*$", re.IGNORECASE), "Nickelodeon"),
    (re.compile(r"^\s*Iberalia(?:\s*TV)?\s*$", re.IGNORECASE), "Iberalia TV"),
    (re.compile(r"^\s*Iberalia(?:\s*100\s*%?)?\s*Caza\s*$", re.IGNORECASE), "Iberalia Caza"),
    (re.compile(r"^\s*Iberalia(?:\s*100\s*%?)?\s*Pesca\s*$", re.IGNORECASE), "Iberalia Pesca"),
    (re.compile(r"^\s*El\s*Toro(?:\s*TV)?\s*$", re.IGNORECASE), "El Toro TV"),
    (re.compile(r"^\s*DKiss(?:\s*TV)?\s*$", re.IGNORECASE), "DKiss"),

    (re.compile(r"^\s*Nat\s*Geo\s*$", re.IGNORECASE), "NAT GEO"),
    (re.compile(r"^\s*Nat\s*Geo\s+Wild\s*$", re.IGNORECASE), "Nat Geo Wild"),
    (re.compile(r"^\s*ETB\s*3\s*$", re.IGNORECASE), "ETB 3"),
    (re.compile(r"^\s*ETB\s*4\s*$", re.IGNORECASE), "ETB 4"),
    (re.compile(r"^\s*EN\s*FAMILIA\s*$", re.IGNORECASE), "En Familia"),
    (re.compile(r"^\s*ALL\s*FLAMENCO\s*$", re.IGNORECASE), "All Flamenco"),
    (re.compile(r"^\s*7\s*RM\s*$", re.IGNORECASE), "7RM"),
    (re.compile(r"^\s*MTV\s*00.?\s*s\s*$", re.IGNORECASE), "MTV 00s"),
    (re.compile(r"^\s*SUPER\s*333\s*$", re.IGNORECASE), "Super 3/33"),
    (re.compile(r"^\s*Super\s*3\s*/\s*33\s*$", re.IGNORECASE), "Super 3/33"),
    # "Run Time Foo" / "RunTime Foo" → "Runtime Foo"
    (re.compile(r"^\s*Run\s*Time\b", re.IGNORECASE), "Runtime"),

    (re.compile(r"^\s*BE\s*MAD(?:\s*TV)?\s*$", re.IGNORECASE), "BE MAD"),
    (re.compile(r"^\s*Runtime\s+Acci[oó]n\s*$", re.IGNORECASE), "Runtime Acción"),
    (re.compile(r"^\s*Runtime\s+Action\s*$", re.IGNORECASE), "Runtime Acción"),
    (re.compile(r"^\s*Runtime\s+Cl[aá]sicos?\s*$", re.IGNORECASE), "Runtime Clásicos"),
    (re.compile(
        r"^\s*Runtime\s+Thriller\s*(?:/\s*Terror|\+\s*Terror|y\s+Terror)\s*$",
        re.IGNORECASE,
    ), "Runtime Thriller y Terror"),
    (re.compile(r"^\s*Am[eé]rica\s*TV\s*$", re.IGNORECASE), "América TV"),
    (re.compile(r"^\s*SURF\s+CHAN(?:NEL|EL)\s*$", re.IGNORECASE), "SURF CHANNEL"),

    # El resto de M es Movistar Original.
    (re.compile(r"^\s*M\s+(.*)\s*$", re.IGNORECASE), r"Movistar \1"),
]


def curate(name: str) -> str:
    text = name
    for pattern, repl in RULES:
        text = pattern.sub(repl, text, count=1)
    # count=0: collapse ALL whitespace runs (SD/HD removal creates several).
    text = re.sub(r"\s+", " ", text).strip()
    return text


def dedupe_casefold(names: list[str]) -> list[str]:
    """Keep first occurrence; drop later case-insensitive duplicates."""
    seen: set[str] = set()
    out: list[str] = []
    for name in names:
        key = name.casefold()
        if key in seen:
            continue
        seen.add(key)
        out.append(name)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--in", dest="inp", type=Path, default=DEFAULT_IN)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    names = json.loads(args.inp.read_text(encoding="utf-8-sig"))
    if not isinstance(names, list):
        raise SystemExit(f"Expected a JSON array in {args.inp}")

    curated = [curate(str(n)) for n in names]
    changed = sum(1 for a, b in zip(names, curated) if a != b)
    unique = dedupe_casefold(curated)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(unique, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"OK {len(unique)} curated names "
        f"({changed} rewritten, {len(curated) - len(unique)} dupes dropped) -> {args.out}"
    )


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
