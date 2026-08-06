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
    # Do not require whitespace after (ES): "(ES) (Orange)" would glue → "HDR(Orange)"
    # and then the Orange rule (needs \\s+ before '(') would miss forever.
    (re.compile(r"\s*\(ES\)\s*", re.IGNORECASE), r" "),
    (re.compile(r":\s*$", re.IGNORECASE), r""),

    (re.compile(r"\bAVTAEL|AVATEL\b", re.IGNORECASE), r""),

    # Quality tokens (leave gaps → collapsed at the end)
    (re.compile(r"\bUHD\/4K\b", re.IGNORECASE), ""),
    (re.compile(r"\bSD\b", re.IGNORECASE), ""),
    (re.compile(r"\bHD\b", re.IGNORECASE), ""),
    (re.compile(r"\bFHD\b", re.IGNORECASE), ""),
    (re.compile(r"\bUHD\b", re.IGNORECASE), ""),
    (re.compile(r"\b4K\b", re.IGNORECASE), ""),
    (re.compile(r"\bHDR\b", re.IGNORECASE), ""),

    # Provider / pack suffixes AFTER quality+(ES), so "(ES) (Orange)" still works
    (re.compile(r"\s*\(backup\s*(?:[0-9]+)?\s*\)\s*$", re.IGNORECASE), r""),
    (re.compile(r"\s*\(Orange\)\s*$", re.IGNORECASE), r""),
    (re.compile(r"\s*\(M\+\)\s*$", re.IGNORECASE), r""),
    (re.compile(r"\s*\(Movistar\+\)\s*$", re.IGNORECASE), r""),
    (re.compile(r"\s*\(LaLiga\+\)\s*$", re.IGNORECASE), r""),

    (re.compile(r"^\s*M\s+A3SERIES\b", re.IGNORECASE), "A3SERIES"),
    (re.compile(r"^\s*M\s+BOM\s+CINE\b", re.IGNORECASE), "BOM CINE"),
    (re.compile(r"^\s*M\s+DARK\b", re.IGNORECASE), "DARK"),
    (re.compile(r"^\s*M\s+WB\s*tv\b", re.IGNORECASE), "Warner Bros. TV"),
    # '+' is non-word: do not use \\b after Movistar+ (it never matches).
    (re.compile(r"^\s*M\s+Movistar\+", re.IGNORECASE), "Movistar+"),
    (re.compile(r"^\s*M\s+LALIGATV", re.IGNORECASE), "LALIGATV"),
    (re.compile(r"^\s*M\s+SKY\s+SHOWTIME", re.IGNORECASE), "SKY SHOWTIME"),
    (re.compile(r"^\s*M\s+WARNER\s+TV", re.IGNORECASE), "Warner Bros. TV"),

    (re.compile(r"^\s*Canal\s+Sur\s+A\s*$", re.IGNORECASE), "Canal Sur"),
    (re.compile(r"^\s*CANALE?\s+EXTREMADURA\s*$", re.IGNORECASE), "Canal Extremadura"),
    (re.compile(r"^\s*LA Sexta", re.IGNORECASE), "laSexta"),

    # El resto de M es Movistar
    (re.compile(r"^\s*M\s+(.*)\s*$", re.IGNORECASE), r"Movistar \1"),

    (re.compile(r"\bLALIGA\s+HYPERMOTION\s+TV\b", re.IGNORECASE), "LALIGATV HYPERMOTION"),
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
