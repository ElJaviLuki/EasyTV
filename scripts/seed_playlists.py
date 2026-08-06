#!/usr/bin/env python3
"""Normalize a playlist export and seed it onto the TV via adb.

Usage:
  python scripts/seed_playlists.py path/to/log_decoded.json
  python scripts/seed_playlists.py path/to/playlists.json
  python scripts/seed_playlists.py path/to/export.json --serial <adb-serial>
  python scripts/seed_playlists.py path/to/export.json --no-push   # only write secrets/playlists.json

Accepts IB Player export ({"urls":[...]}) or clean {"sources":[...]}.
No ids in output — the app derives them from baseUrl+username.

Pushes to:
  /sdcard/Android/data/com.eljaviluki.easytv/files/playlists.json
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[1]
LOCAL_OUT = ROOT / "secrets" / "playlists.json"
PACKAGE = "com.eljaviluki.easytv"
REMOTE_DIR = f"/sdcard/Android/data/{PACKAGE}/files"
REMOTE_FILE = f"{REMOTE_DIR}/playlists.json"


def clean_source(raw: dict) -> dict:
    out = {
        "name": raw["name"],
        "baseUrl": str(raw["baseUrl"]).rstrip("/"),
        "username": raw["username"],
        "password": raw["password"],
    }
    hint = raw.get("hint") or ""
    if hint:
        out["hint"] = hint
    return out


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
            clean_source(
                {
                    "name": item.get("name") or f"Servidor {i + 1}",
                    "baseUrl": f"{parsed.scheme or 'http'}://{parsed.hostname}{port}",
                    "username": user,
                    "password": password,
                    "hint": parsed.hostname,
                }
            )
        )
    return sources


def normalize(data: dict) -> dict:
    if "sources" in data:
        return {"sources": [clean_source(s) for s in data["sources"]]}
    if "urls" in data:
        return {"sources": from_ib_urls(data["urls"])}
    raise SystemExit("JSON no reconocido: se espera 'sources' o 'urls' (export IB Player)")


def adb(args: list[str], serial: str | None) -> None:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    subprocess.run(cmd, check=True)


def push_to_device(local: Path, serial: str | None) -> None:
    adb(["shell", f"mkdir -p '{REMOTE_DIR}'"], serial)
    adb(["push", str(local), REMOTE_FILE], serial)
    adb(["shell", f"am force-stop {PACKAGE}"], serial)
    adb(["shell", f"am start -n {PACKAGE}/.MainActivity"], serial)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export", type=Path, help="IB export or clean playlists JSON")
    parser.add_argument("--serial", help="adb device serial")
    parser.add_argument("--no-push", action="store_true", help="Only write secrets/playlists.json")
    args = parser.parse_args()

    if not args.export.is_file():
        raise SystemExit(f"No existe: {args.export}")

    data = json.loads(args.export.read_text(encoding="utf-8-sig"))
    normalized = normalize(data)
    if not normalized["sources"]:
        raise SystemExit("No se extrajo ningún servidor")

    payload = json.dumps(normalized, ensure_ascii=False, indent=2) + "\n"
    LOCAL_OUT.parent.mkdir(parents=True, exist_ok=True)
    LOCAL_OUT.write_text(payload, encoding="utf-8")
    print(f"Local: {len(normalized['sources'])} servidores -> {LOCAL_OUT}")

    if args.no_push:
        return

    if shutil.which("adb") is None:
        raise SystemExit("adb no está en PATH; usa --no-push o instala platform-tools")

    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as tmp:
        tmp.write(payload)
        tmp_path = Path(tmp.name)
    try:
        push_to_device(tmp_path, args.serial)
    finally:
        tmp_path.unlink(missing_ok=True)

    print(f"ADB: pushed -> {REMOTE_FILE} y reiniciada {PACKAGE}")


if __name__ == "__main__":
    main()
