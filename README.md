# TV Fácil

Android TV app with a simple remote-friendly UI for live TV, series, and movies (Xtream Codes).

## Setup

1. Clone this repo and build/install the APK (no credentials inside):

```bash
./gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

2. Seed playlists onto the TV via adb (credentials stay off git):

```bash
python scripts/seed_playlists.py path/to/log_decoded.json
```

Optional:

```bash
python scripts/seed_playlists.py path/to/export.json --serial <adb-serial>
python scripts/seed_playlists.py path/to/export.json --no-push   # only secrets/playlists.json
```

Accepts IB Player export (`urls`) or clean format (`playlists.example.json`).

The script writes `secrets/playlists.json` (gitignored) and pushes to:

`/sdcard/Android/data/tv.facil.abuelo/files/playlists.json`

Then force-stops and relaunches the app. Load order: external files → internal files → assets fallback.

## Flow

Servidor → Canales | Series | Películas → lista → (series: episodios) → player
