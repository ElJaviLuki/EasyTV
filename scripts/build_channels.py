#!/usr/bin/env python3
"""Collapse per-server live catalogs into a unified Canal list.

For this ad-hoc dump set, lives are grouped by stream_id across servers.
A Canal is what the user sees; its lives are failover alternatives (other
hosts / qualities) — the app can try the next live if one fails.

Usage:
  python scripts/build_channels.py
  python scripts/build_channels.py --catalogs secrets/catalogs --out secrets/channels.json
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOGS = ROOT / "secrets" / "catalogs"
DEFAULT_OUT = ROOT / "secrets" / "channels.json"


def load_lives(catalogs_dir: Path) -> list[dict]:
    """Flatten all live entries, tagging each with server metadata."""
    files = sorted(catalogs_dir.glob("*.json"))
    if not files:
        raise SystemExit(f"No hay JSON en {catalogs_dir}")

    lives: list[dict] = []
    for path in files:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        server = str(data.get("name") or path.stem)
        base_url = str(data.get("baseUrl") or "")
        for raw in data.get("live") or []:
            if not isinstance(raw, dict):
                continue
            sid = raw.get("stream_id")
            if sid is None:
                continue
            number = raw.get("number")
            if number is None:
                continue
            lives.append(
                {
                    # Fields shared by the Canal (set on the parent):
                    "_number": int(number),
                    "_name": str(raw.get("name") or f"Canal {sid}"),
                    "_group": str(raw.get("group") or "Sin categoría"),
                    "_logo": raw.get("logo") or None,
                    "_stream_id": int(sid),
                    # Live-only (differs per server / playback path):
                    "server": server,
                    "baseUrl": base_url,
                    "url": str(raw.get("url") or ""),
                }
            )
    return lives


def build_channels(lives: list[dict]) -> list[dict]:
    """
    Group lives into canales.

    Strategy (this dump): stream_id.
    Other datasets may need a different key (normalized name, EPG id, …).
    """
    buckets: dict[int, list[dict]] = defaultdict(list)
    seen_server_number: set[tuple[str, int]] = set()

    for live in lives:
        key = (live["server"], live["_number"])
        if key in seen_server_number:
            # (host, number) must be unique within the dump set
            continue
        seen_server_number.add(key)
        buckets[live["_stream_id"]].append(live)

    channels: list[dict] = []
    for stream_id, group in buckets.items():
        # Stable failover order: keep catalog file order (already sorted by name)
        primary = group[0]
        channels.append(
            {
                "id": str(stream_id),
                "stream_id": stream_id,
                "number": primary["_number"],
                "name": primary["_name"],
                "group": primary["_group"],
                "logo": primary["_logo"],
                # Strip taxonomy fields already on the Canal.
                "lives": [
                    {
                        "server": live["server"],
                        "baseUrl": live["baseUrl"],
                        "url": live["url"],
                    }
                    for live in group
                ],
            }
        )

    channels.sort(key=lambda c: (c["number"], c["name"].lower()))
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
        "grouping": "stream_id",
        "note": (
            "Canal = 1+ lives. For this dump, lives share stream_id across servers. "
            "Failover: try lives in order if one fails. Other dumps may group differently."
        ),
        "counts": {
            "canales": len(channels),
            "lives": len(lives),
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
