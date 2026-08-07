#!/usr/bin/env python3
"""Collapse per-server live catalogs into a unified Canal list.

Ad-hoc for this dump: group lives by name_curator.curate(raw_name)
(case-insensitive). NOT a de jure Canal invariant.

- visible_name: curated label (grandpa-facing)
- name: array of distinct raw IPTV names that collapsed into this Canal
- id: blake2b(visible_name.casefold()); number: zap pick from best-quality live

Usage:
  python scripts/build_channels.py
  python scripts/build_channels.py --catalogs secrets/catalogs --out secrets/channels.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from name_curator import curate  # noqa: E402

DEFAULT_CATALOGS = ROOT / "secrets" / "catalogs"
DEFAULT_OUT = ROOT / "secrets" / "channels.json"

# Lower = preferred failover / canonical pick.
_QUALITY_PATTERNS: list[tuple[re.Pattern[str], int]] = [
    (re.compile(r"\b4k\b|\buhd\b", re.I), 0),
    (re.compile(r"\bfhd\b", re.I), 1),
    (re.compile(r"\bhd\b", re.I), 2),
    (re.compile(r"\bsd\b", re.I), 3),
]


def quality_rank(name: str) -> int:
    for pat, rank in _QUALITY_PATTERNS:
        if pat.search(name):
            return rank
    return 2  # unknown → treat like HD


def canal_id(grouping_key: str) -> str:
    """Stable short id from the ad-hoc grouping key (not stream_id)."""
    return hashlib.blake2b(grouping_key.encode("utf-8"), digest_size=8).hexdigest()


def load_lives(catalogs_dir: Path) -> list[dict]:
    """Flatten all live entries, tagging each with server + curation helpers."""
    files = sorted(catalogs_dir.glob("*.json"))
    if not files:
        raise SystemExit(f"No hay JSON en {catalogs_dir}")

    lives: list[dict] = []
    seen_server_number: set[tuple[str, int]] = set()

    for path in files:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        server = str(data.get("name") or path.stem)
        for raw in data.get("live") or []:
            if not isinstance(raw, dict):
                continue
            sid = raw.get("stream_id")
            number = raw.get("number")
            if sid is None or number is None:
                continue
            number = int(number)
            key = (server, number)
            if key in seen_server_number:
                continue
            seen_server_number.add(key)

            name = str(raw.get("name") or f"Canal {sid}")
            curated = curate(name)
            lives.append(
                {
                    "_number": number,
                    "_name": name,
                    "_curated": curated,
                    "_group_key": curated.casefold(),
                    "_group": str(raw.get("group") or "Sin categoría"),
                    "_logo": raw.get("logo") or None,
                    "_quality": quality_rank(name),
                    "server": server,
                    "url": str(raw.get("url") or ""),
                }
            )
    return lives


def build_channels(lives: list[dict]) -> list[dict]:
    """
    Group lives into canales by curated name (case-insensitive).

    Lives of one Canal may have different stream_ids / playlist numbers /
    raw names; name_curator decides which raw titles collapse together.
    """
    buckets: dict[str, list[dict]] = defaultdict(list)
    for live in lives:
        key = live["_group_key"] or live["_name"].casefold()
        buckets[key].append(live)

    channels: list[dict] = []
    for _key, group in buckets.items():
        # Prefer best quality as taxonomy source + zap number + visible spelling.
        primary = min(
            group,
            key=lambda L: (L["_quality"], L["_number"], L["server"], L["_name"]),
        )
        ordered = sorted(
            group,
            key=lambda L: (L["_quality"], L["server"], L["_number"]),
        )
        # Distinct raw names in first-seen order (catalog walk / quality order).
        raw_names = list(dict.fromkeys(L["_name"] for L in ordered if L["_name"]))
        groups = list(dict.fromkeys(L["_group"] for L in ordered if L["_group"]))
        visible = primary["_curated"] or primary["_name"]

        lives_out: list[dict] = []
        seen_url: set[str] = set()
        for live in ordered:
            url = live["url"]
            if not url or url in seen_url:
                continue
            seen_url.add(url)
            lives_out.append(
                {
                    "server": live["server"],
                    "url": url,
                }
            )

        channels.append(
            {
                "id": canal_id(visible.casefold()),
                "number": primary["_number"],
                "name": raw_names,
                "visible_name": visible,
                "group": groups,
                "logo": primary["_logo"],
                "lives": lives_out,
            }
        )

    channels.sort(key=lambda c: (c["number"], c["visible_name"].casefold()))
    return channels


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalogs", type=Path, default=DEFAULT_CATALOGS)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    lives = load_lives(args.catalogs)
    channels = build_channels(lives)

    multi = sum(1 for c in channels if len(c["lives"]) > 1)
    multi_names = sum(1 for c in channels if len(c["name"]) > 1)
    payload = {
        "grouping": "name_curator.curate",
        "grouping_note": (
            "Ad-hoc: lives from all servers whose raw name curate() to the same "
            "label (case-insensitive) form one Canal. "
            "visible_name = curated label; name = distinct raw titles; "
            "no stream_id on Canal."
        ),
        "counts": {
            "canales": len(channels),
            "lives": sum(len(c["lives"]) for c in channels),
            "lives_raw": len(lives),
            "canales_con_failover": multi,
            "canales_con_varios_name": multi_names,
        },
        "canales": channels,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    c = payload["counts"]
    print(
        f"OK {c['canales']} canales ({c['lives']} lives, "
        f"{c['canales_con_varios_name']} con varios name raw) -> {args.out}",
        flush=True,
    )


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
