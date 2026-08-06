#!/usr/bin/env python3
"""Launcher for the Rust order_lives tool (TS probe: resolution × bitrate).

Does NOT trust HD/SD in names. Builds/runs tools/order_lives which:
  - downloads a small MPEG-TS prefix per unique stream_id
  - parses H.264 SPS for resolution + PCR for bitrate estimate
  - reorders lives: Manolo1…6 → quality score → name stability
  - caches probes in secrets/ts_probe_cache.json

Usage:
  python scripts/order_lives.py
  python scripts/order_lives.py --release
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "order_lives"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--release", action="store_true", default=True)
    ap.add_argument("--debug", action="store_true", help="Build/run debug profile")
    args = ap.parse_args()
    profile = "debug" if args.debug else "release"

    cmd = ["cargo", "run", "--manifest-path", str(TOOL / "Cargo.toml")]
    if profile == "release":
        cmd.append("--release")
    cmd.append("--")

    print("Running:", " ".join(cmd), flush=True)
    raise SystemExit(subprocess.call(cmd, cwd=str(ROOT)))


if __name__ == "__main__":
    main()
