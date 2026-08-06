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
    (re.compile(r"\s+$", re.IGNORECASE), ""),  # trailing whitespace
    (re.compile(r"^\s+", re.IGNORECASE), ""),  # leading whitespace
    (re.compile(r"\s+", re.IGNORECASE), " "),  # multiple spaces

    (re.compile(r"^\s*ES\s+4k-\s*(.*)\s*$", re.IGNORECASE), r"\1"),
    (re.compile(r"^\s*ES-\s*(.*)\s*$", re.IGNORECASE), r"\1"),
    (re.compile(r"^\s*ES\s+\([A-Z]+\)\s*", re.IGNORECASE), r""),
    (re.compile(r"^\s*ES\s+", re.IGNORECASE), r""),
    (re.compile(r"^\s*\(ES\)\s+", re.IGNORECASE), r""),
    (re.compile(r"\s+\(ES\)\s*$", re.IGNORECASE), r""),
    (re.compile(r"\s+\(backup\)\s*$", re.IGNORECASE), r""),
    
    (re.compile(r"^\s*M\s+A3SERIES\s*$", re.IGNORECASE), "A3SERIES"),
    (re.compile(r"^\s*M\s+BOM\s+CINE\s*$", re.IGNORECASE), "BOM CINE"),
    (re.compile(r"^\s*M\s+DARK\s*$", re.IGNORECASE), "DARK"),
    (re.compile(r"^\s*M\s+WB\s*tv\s*$", re.IGNORECASE), "Warner Bros. TV"),
    (re.compile(r"^\s*M\s+Movistar\+\s*$", re.IGNORECASE), "Movistar+"),
    (re.compile(r"^\s*M\s+LALIGATV", re.IGNORECASE), "LALIGATV"),
    (re.compile(r"^\s*M\s+SKY\s+SHOWTIME", re.IGNORECASE), "SKY SHOWTIME"),
    (re.compile(r"^\s*M\s+WARNER\s+TV", re.IGNORECASE), "Warner Bros. TV"),

    # El resto de M es Movistar
    (re.compile(r"^\s*M\s+(.*)\s*$", re.IGNORECASE), r"Movistar \1"),

    # Trailing SD, HD, FHD, etc.
    (re.compile(r"\bSD\b", re.IGNORECASE), ""),
    (re.compile(r"\bHD\b", re.IGNORECASE), ""),
    (re.compile(r"\bFHD\b", re.IGNORECASE), ""),
    (re.compile(r"\bUHD\b", re.IGNORECASE), ""),
    (re.compile(r"\b4K\b", re.IGNORECASE), ""),
    (re.compile(r"\bUHD\/4K\b", re.IGNORECASE), ""),

    (re.compile(r"\s+$", re.IGNORECASE), ""),  # trailing whitespace
    (re.compile(r"^\s+", re.IGNORECASE), ""),  # leading whitespace
]


def curate(name: str) -> str:
    text = name
    for pattern, repl in RULES:
        text = pattern.sub(repl, text, count=1)
    return text.strip()


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

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(curated, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"OK {len(curated)} names ({changed} curated) -> {args.out}")


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
