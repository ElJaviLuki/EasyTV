# EasyTV

**Modern TV. Old-school remote.**

## The problem

Smart TVs promised a better living room. For a lot of people—especially anyone used to a traditional set—they delivered the opposite.

My grandfather bought a new Smart TV to watch the World Cup properly. We installed IPTV apps so he could also watch channels, series, and movies. Every one of them was a maze: nested menus, tiny focus targets, too many buttons, too many steps. He was frustrated. The hardware was modern; the experience still assumed a “power user.”

That is the business problem EasyTV solves: IPTV and Android TV UIs are built for enthusiasts, not for the people who just want to change channel and press play. Families install capable boxes and playlists, then watch relatives struggle with software that feels harder than the CRT they replaced.

EasyTV exists to close that gap—bring the most modern TV to the most traditional audience. **Operate a modern TV the old-fashioned way.**

## What EasyTV is

An Android TV app for live TV, series, and movies (Xtream Codes), designed around a physical remote: color keys, channel zap, big readable UI, resume where you left off. Fewer screens. Clearer choices. No hunting through settings to watch something.

Licensed under the [MIT License](LICENSE).

## Technical overview

- **Platform:** Android TV (Leanback / remote-first), package `com.eljaviluki.easytv`
- **Sources:** Xtream Codes portals (`player_api.php`); playlists seeded to the device (not baked into the APK)
- **Modes:** Live TV · Series · Movies, with color-button navigation (Red / Green / Yellow / Blue)
- **Playback:** ExoPlayer; live zap with CH± (yes, obvious, I haven't found an IPTV that supports this yet!); VOD seek and resume; series episode queue

### App flow

Server → Channels | Series | Movies → catalog → (series: episodes) → player

### Setup

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

The script writes `secrets/playlists.json` (gitignored, outside `app/`) and pushes to:

`/sdcard/Android/data/com.eljaviluki.easytv/files/playlists.json`

Then force-stops and relaunches the app. Load order on device: external files → internal files.
