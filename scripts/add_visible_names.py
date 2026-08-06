#!/usr/bin/env python3
"""Add visible_name to channels.json (grandpa-friendly display label).
Reduces cognitive load for grandpa by removing country tags, quality markers and IPTV prefixes.

Strips country tags, quality markers and IPTV prefixes so
\"ES 4k- Telecinco\" becomes \"Telecinco\".

Usage:
  python scripts/add_visible_names.py
  python scripts/add_visible_names.py --channels secrets/channels.json
  python scripts/add_visible_names.py --dry-run
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CHANNELS = ROOT / "secrets" / "channels.json"

# Country / region codes used as IPTV prefixes (not channel brand words).
# Must be wrapped in (?:...) so VERBOSE + | does not split the outer group.
_COUNTRY = (
    r"(?:ES|MX|AR|BR|CL|PE|CO|EC|UY|PY|BO|VE|CR|PA|GT|HN|SV|NI|DO|CU|PR|"
    r"US|UK|GB|IT|FR|DE|PT|PL|NL|BE|CH|AT|SE|NO|DK|FI|RU|TR|IN|JP|CN|KR|"
    r"Carib)"
)

# "(PLUTO Spain)" / "(MX)" / "(AR)" at the start
_LEADING_PAREN = re.compile(r"^\([^)]+\)\s*")

# "ES 4k-" / "ES-" / "ES (AV)" / "MX (DSH)" / "Carib (AMP)"
_LEADING_COUNTRY = re.compile(
    rf"""^(?:
        {_COUNTRY}
        (?:\s*\([^)]+\))?                          # optional (AV)/(DSH)/(IZ)/(O)
        (?:\s*(?:4k|4K|UHD|FHD|HD|SD|HEVC))?       # optional quality after country
        \s*[-–:|]\s*                               # require a separator
      | {_COUNTRY}
        \s*\([^)]+\)\s+                            # ES (AV) Name  (space, no dash)
    )""",
    re.VERBOSE | re.IGNORECASE,
)

_TRAILING_QUALITY = re.compile(
    r"""\s*(?:
        [-–|]?\s*(?:4k|4K|UHD|FHD|HD|SD|HEVC)
      | \((?:ES|MX|AR|BR|PT|CL|PE|CO|EC|UY|PY|US|UK|IT|FR|DE)\)
    )\s*$""",
    re.VERBOSE | re.IGNORECASE,
)

_MULTI_SPACE = re.compile(r"\s+")

# Movistar IPTV shorthand: "M A3SERIES" / "M Estrenos" → drop the lone "M ".
_LEADING_M_SHORTHAND = re.compile(r"^M\s+", re.IGNORECASE)


def visible_name(raw: str) -> str:
    name = (raw or "").strip()
    if not name:
        return ""

    # Peel leading parenthetical tags: (MX) (IZ) …
    while True:
        nxt = _LEADING_PAREN.sub("", name, count=1)
        if nxt == name:
            break
        name = nxt.strip()

    # Peel leading country prefixes (require separator or "(code) ")
    while True:
        nxt = _LEADING_COUNTRY.sub("", name, count=1)
        if nxt == name:
            break
        name = nxt.strip()

    # Peel trailing quality / (ES)
    while True:
        nxt = _TRAILING_QUALITY.sub("", name)
        if nxt == name:
            break
        name = nxt.strip()

    name = name.strip(" -–|:;")
    name = _MULTI_SPACE.sub(" ", name).strip()

    # "M A3SERIES" → "A3SERIES" (Movistar list marker, not part of the brand)
    name = _LEADING_M_SHORTHAND.sub("", name).strip()

    return name or raw.strip()


def with_visible_name(canal: dict) -> dict:
    """Return canal dict with visible_name placed immediately after name."""
    raw = str(canal.get("name") or "")
    vis = visible_name(raw)
    out: dict = {}
    for key, value in canal.items():
        if key == "visible_name":
            continue
        out[key] = value
        if key == "name":
            out["visible_name"] = vis
    if "name" not in out:
        out["visible_name"] = vis
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channels", type=Path, default=DEFAULT_CHANNELS)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print sample renames without writing",
    )
    args = parser.parse_args()

    data = json.loads(args.channels.read_text(encoding="utf-8-sig"))
    canales = data.get("canales") or []
    changed = 0
    samples: list[tuple[str, str]] = []
    rebuilt: list[dict] = []

    for canal in canales:
        raw = str(canal.get("name") or "")
        vis = visible_name(raw)
        if canal.get("visible_name") != vis:
            changed += 1
        if raw != vis and len(samples) < 25:
            samples.append((raw, vis))
        rebuilt.append(with_visible_name(canal))

    data["canales"] = rebuilt

    if args.dry_run:
        for raw, vis in samples:
            print(f"{raw!r}  ->  {vis!r}")
        print(f"... {changed} would change / total {len(canales)}", flush=True)
        return

    args.channels.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"OK visible_name on {len(rebuilt)} channels "
        f"({changed} differ from name) -> {args.channels}"
    )
    for raw, vis in samples[:15]:
        print(f"  {raw}  ->  {vis}")


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
