#!/usr/bin/env python3
"""Seed app/src/main/assets/playlists.json from an IB Player export or clean JSON.

Usage:
  python scripts/seed_playlists.py path/to/log_decoded.json
  python scripts/seed_playlists.py path/to/playlists.json

The output file is gitignored. Safe to run after cloning.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "playlists.json"


def from_ib_urls(urls: list[dict]) -> list[dict]:
    sources = []
    for i, item in enumerate(urls):
        portal = item.get("url") or ""
        parsed = urlparse(portal)
        qs = parse_qs(parsed.query)
        user = (qs.get("username") or [""])[0]
        password = (qs.get("password") or [""])[0]
        if not parsed.hostname or not user or not password:
            continue
        port = f":{parsed.port}" if parsed.port else ""
        sources.append(
            {
                "id": item.get("id") or f"src_{i}",
                "name": item.get("name") or f"Servidor {i + 1}",
                "baseUrl": f"{parsed.scheme or 'http'}://{parsed.hostname}{port}",
                "username": user,
                "password": password,
                "hint": parsed.hostname,
            }
        )
    return sources


def normalize(data: dict) -> dict:
    if "sources" in data:
        return {"sources": data["sources"]}
    if "urls" in data:
        return {"sources": from_ib_urls(data["urls"])}
    raise SystemExit("JSON no reconocido: se espera 'sources' o 'urls' (export IB Player)")


def main() -> None:
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        raise SystemExit(2)
    src = Path(sys.argv[1])
    if not src.is_file():
        raise SystemExit(f"No existe: {src}")
    data = json.loads(src.read_text(encoding="utf-8-sig"))
    normalized = normalize(data)
    if not normalized["sources"]:
        raise SystemExit("No se extrajo ningún servidor")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(normalized, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"OK: {len(normalized['sources'])} servidores -> {OUT}")


if __name__ == "__main__":
    main()
