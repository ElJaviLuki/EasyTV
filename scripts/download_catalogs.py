#!/usr/bin/env python3
"""Download full Xtream catalogs (live / movies / series) to secrets/catalogs/.

Usage:
  python scripts/download_catalogs.py
  python scripts/download_catalogs.py --playlists secrets/playlists.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PLAYLISTS = ROOT / "secrets" / "playlists.json"
OUT_DIR = ROOT / "secrets" / "catalogs"
UA = "EasyTV/1.0"


def slug(name: str) -> str:
    s = re.sub(r"[^\w\-]+", "_", name.strip(), flags=re.UNICODE)
    return s.strip("_") or "source"


def api_get(base: str, user: str, password: str, action: str) -> object:
    url = (
        f"{base.rstrip('/')}/player_api.php"
        f"?username={user}&password={password}&action={action}"
    )
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
    if not raw.strip():
        raise RuntimeError(f"empty response for {action}")
    return json.loads(raw)


def cat_map(categories: object) -> dict[str, str]:
    out: dict[str, str] = {}
    if not isinstance(categories, list):
        return out
    for c in categories:
        if not isinstance(c, dict):
            continue
        cid = str(c.get("category_id", "")).strip()
        if cid:
            out[cid] = str(c.get("category_name") or "Sin categoría")
    return out


def normalize_live(streams: object, categories: dict[str, str], base: str, user: str, password: str) -> list[dict]:
    items = []
    if not isinstance(streams, list):
        return items
    for i, o in enumerate(streams):
        if not isinstance(o, dict):
            continue
        sid = o.get("stream_id")
        if sid is None:
            continue
        cat = categories.get(str(o.get("category_id", "")), "Sin categoría")
        items.append(
            {
                "number": o.get("num", i + 1),
                "name": o.get("name") or f"Canal {sid}",
                "group": cat,
                "logo": o.get("stream_icon") or None,
                "stream_id": sid,
                "url": f"{base.rstrip('/')}/live/{user}/{password}/{sid}.ts",
            }
        )
    return items


def normalize_movies(streams: object, categories: dict[str, str], base: str, user: str, password: str) -> list[dict]:
    items = []
    if not isinstance(streams, list):
        return items
    for i, o in enumerate(streams):
        if not isinstance(o, dict):
            continue
        sid = o.get("stream_id")
        if sid is None:
            continue
        ext = o.get("container_extension") or "mp4"
        cat = categories.get(str(o.get("category_id", "")), "Sin categoría")
        items.append(
            {
                "number": o.get("num", i + 1),
                "name": o.get("name") or f"Película {sid}",
                "group": cat,
                "logo": o.get("stream_icon") or None,
                "stream_id": sid,
                "url": f"{base.rstrip('/')}/movie/{user}/{password}/{sid}.{ext}",
            }
        )
    return items


def normalize_series(series: object, categories: dict[str, str]) -> list[dict]:
    items = []
    if not isinstance(series, list):
        return items
    for i, o in enumerate(series):
        if not isinstance(o, dict):
            continue
        sid = o.get("series_id")
        if sid is None:
            continue
        cat = categories.get(str(o.get("category_id", "")), "Sin categoría")
        items.append(
            {
                "number": o.get("num", i + 1),
                "name": o.get("name") or f"Serie {sid}",
                "group": cat,
                "logo": o.get("cover") or None,
                "series_id": sid,
            }
        )
    return items


def download_source(src: dict) -> tuple[str, Path, dict]:
    name = str(src["name"])
    base = str(src["baseUrl"]).rstrip("/")
    user = str(src["username"])
    password = str(src["password"])

    live_cats = api_get(base, user, password, "get_live_categories")
    live_streams = api_get(base, user, password, "get_live_streams")
    vod_cats = api_get(base, user, password, "get_vod_categories")
    vod_streams = api_get(base, user, password, "get_vod_streams")
    series_cats = api_get(base, user, password, "get_series_categories")
    series_list = api_get(base, user, password, "get_series")

    live = normalize_live(live_streams, cat_map(live_cats), base, user, password)
    movies = normalize_movies(vod_streams, cat_map(vod_cats), base, user, password)
    series = normalize_series(series_list, cat_map(series_cats))

    payload = {
        "name": name,
        "baseUrl": base,
        "hint": src.get("hint") or base.replace("http://", "").replace("https://", ""),
        "counts": {"live": len(live), "movies": len(movies), "series": len(series)},
        "live": live,
        "movies": movies,
        "series": series,
    }
    out = OUT_DIR / f"{slug(name)}.json"
    return name, out, payload


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--playlists", type=Path, default=DEFAULT_PLAYLISTS)
    parser.add_argument("--workers", type=int, default=3)
    args = parser.parse_args()

    if not args.playlists.is_file():
        raise SystemExit(f"No existe: {args.playlists}")

    data = json.loads(args.playlists.read_text(encoding="utf-8-sig"))
    sources = data.get("sources") or []
    if not sources:
        raise SystemExit("Sin servidores en playlists")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Descargando {len(sources)} listas -> {OUT_DIR}", flush=True)

    errors: list[str] = []
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = {pool.submit(download_source, s): s.get("name", "?") for s in sources}
        for fut in as_completed(futures):
            label = futures[fut]
            try:
                name, path, payload = fut.result()
                path.write_text(
                    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )
                c = payload["counts"]
                print(
                    f"OK {name}: live={c['live']} movies={c['movies']} series={c['series']} -> {path.name}",
                    flush=True,
                )
            except Exception as e:
                msg = f"FAIL {label}: {e}"
                errors.append(msg)
                print(msg, file=sys.stderr, flush=True)

    if errors:
        raise SystemExit(f"{len(errors)} lista(s) fallaron")


if __name__ == "__main__":
    main()
