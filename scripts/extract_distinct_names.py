#!/usr/bin/env python3
"""Collect every distinct raw live name across all server catalogs.

Order: first appearance while walking catalogs (sorted by filename) then
lives in file order. Not derived from channels.json.

Usage:
  python scripts/extract_distinct_names.py
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOGS = ROOT / "secrets" / "catalogs"
DEFAULT_OUT = ROOT / "secrets" / "distinct_names.json"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalogs", type=Path, default=DEFAULT_CATALOGS)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    files = sorted(args.catalogs.glob("*.json"))
    if not files:
        raise SystemExit(f"No hay JSON en {args.catalogs}")

    seen: set[str] = set()
    names: list[str] = []
    for path in files:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        for live in data.get("live") or []:
            if not isinstance(live, dict):
                continue
            name = live.get("name")
            if not isinstance(name, str) or not name or name in seen:
                continue
            seen.add(name)
            names.append(name)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(names, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"OK {len(names)} distinct raw names from {len(files)} catalogs -> {args.out}")


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
