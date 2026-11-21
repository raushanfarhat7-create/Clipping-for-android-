# AutoClip Mobile (Android)

A thin Android shell around the [AutoClip](https://github.com/artbyjazi/autoclip) web UI.
AutoClip itself (Whisper, ffmpeg, MediaPipe, the LLM calls) still runs on a computer or
server. This app connects to it and adds:

- a first-run screen that tests the server address via `/api/health`
- video file upload from the phone (system file picker)
- downloading exported clips to the phone's Downloads folder
- fullscreen video, back-button navigation, retry screen when the server is unreachable

## 1. Run AutoClip so the phone can reach it

AutoClip binds to `127.0.0.1` by default. For a phone to connect, bind to all interfaces:

    autoclip serve --host 0.0.0.0 --no-open

or in Docker change the port mapping in `docker/compose.yaml` from
`'127.0.0.1:8000:8000'` to `'8000:8000'`.

**AutoClip has no login.** Only do this on a network you trust (home Wi-Fi, a VPN such as
Tailscale). Do not expose it to the public internet.

Find the computer's LAN IP (e.g. `192.168.1.20`) and allow port 8000 through its firewall.
Emulator: use `http://10.0.2.2:8000`.

## 2. Build the APK

### Option A: GitHub Actions (no Android tooling needed)

1. Create a GitHub repo and push this folder to it.
2. Open **Actions → Build APK → Run workflow**.
3. Download the `autoclip-debug-apk` artifact, unzip it, and you have `app-debug.apk`.

### Option B: Android Studio

1. Open this folder in Android Studio (Koala or newer) and let Gradle sync.
2. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
3. The file is at `app/build/outputs/apk/debug/app-debug.apk`.

### Option C: command line

Needs JDK 17, the Android SDK (platform 34) and Gradle 8.7:

    gradle assembleDebug

## 3. Install

Copy `app-debug.apk` to the phone and open it (allow "install unknown apps" for your file
manager or browser), or run `adb install app-debug.apk`.

On first launch enter `http://<computer-ip>:8000`, tap **Test and save**. Change the server
later from the ⋮ menu.

## Notes

- Min Android 7.0 (API 24). No third-party libraries.
- Cleartext http is allowed on purpose (LAN use). Use HTTPS via a reverse proxy if the
  server is reachable beyond your own network.
- The release build is signed with the debug key so it is sideloadable. Use your own
  keystore before distributing it.
- This project was written without an Android SDK available, so it has not been compiled
  here. If Gradle reports an error on first build, send me the log.
