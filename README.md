# TV Fácil

Android TV app with a simple remote-friendly UI for live TV, series, and movies (Xtream Codes).

## Setup

1. Clone this repo.
2. Seed playlists (credentials are **not** in git):

```bash
python scripts/seed_playlists.py path/to/log_decoded.json
```

Accepts either:
- IB Player export (`urls` with `get.php?...` portals), or
- Clean format (see `playlists.example.json`).

This writes `app/src/main/assets/playlists.json` (gitignored).

3. Build:

```bash
./gradlew.bat assembleDebug
```

## Flow

Servidor → Canales | Series | Películas → lista → (series: episodios) → player
