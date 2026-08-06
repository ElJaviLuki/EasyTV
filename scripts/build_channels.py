#!/usr/bin/env python3
"""Collapse per-server live catalogs into a unified Canal list.

Ad-hoc for this dump: group by visible_name (NOT an invariant — other
datasets may use EPG id, logo hash, manual map, etc.).

stream_id is NOT a Canal key: the same TV network can appear under many
stream_ids (4K / FHD / \"M …\" pack / other hosts). Canal.id is a hash of
the grouping key; Canal.number is a zap-friendly pick from its lives.

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
from add_visible_names import visible_name  # noqa: E402

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
    """Flatten all live entries, tagging each with server + display helpers."""
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
            lives.append(
                {
                    "_number": number,
                    "_name": name,
                    "_visible": visible_name(name),
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
    Group lives into canales.

    Strategy (this dump, ad-hoc): visible_name.
    Lives of one Canal may have different stream_ids / playlist numbers.
    """
    buckets: dict[str, list[dict]] = defaultdict(list)
    for live in lives:
        key = live["_visible"] or live["_name"]
        buckets[key].append(live)

    channels: list[dict] = []
    for vis, group in buckets.items():
        # Prefer best quality as taxonomy source + zap number.
        primary = min(
            group,
            key=lambda L: (L["_quality"], L["_number"], L["server"], L["_name"]),
        )
        # Failover: better quality first, then server order from catalog names.
        ordered = sorted(
            group,
            key=lambda L: (L["_quality"], L["server"], L["_number"]),
        )
        groups = list(dict.fromkeys(L["_group"] for L in ordered if L["_group"]))
        # Dedup identical playback URLs (same path on same host).
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
                "id": canal_id(vis),
                "number": primary["_number"],
                "name": primary["_name"],
                "visible_name": vis,
                "group": groups,
                "logo": primary["_logo"],
                "lives": lives_out,
            }
        )

    channels.sort(key=lambda c: (c["number"], c["visible_name"].lower()))
    return channels


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalogs", type=Path, default=DEFAULT_CATALOGS)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    lives = load_lives(args.catalogs)
    channels = build_channels(lives)

    multi = sum(1 for c in channels if len(c["lives"]) > 1)
    payload = {
        "grouping": "visible_name",
        "grouping_note": (
            "Ad-hoc for this dump only — not a de jure Canal invariant. "
            "stream_id is not stored on Canal (unreliable across qualities/packs). "
            "Canal.id = blake2b(visible_name); Canal.number = zap pick from best-quality live."
        ),
        "counts": {
            "canales": len(channels),
            "lives": sum(len(c["lives"]) for c in channels),
            "lives_raw": len(lives),
            "canales_con_failover": multi,
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
        f"{c['canales_con_failover']} con failover) -> {args.out}",
        flush=True,
    )


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
