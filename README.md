# RemoteZio

Control Vizio SmartCast TVs (including Walmart onn TVs running Vizio OS) over your local network, using the TV's built-in SmartCast API on port 7345.

Three ways to use it:

- **Android app** (`android/`): pair with a PIN, switch between several TVs, D-pad, volume, inputs, a YouTube button and a per-TV "open on power-on" app. Chinese and English.
- **Web remote** (`server.py` + `remote.html`): the same features in a browser, on one screen. While the server runs it also opens each TV's startup app whenever that TV turns on, whichever remote turned it on.
- **Command line** (`tv.py`): pair, send keys, set volume, switch inputs, open apps.

No third-party dependencies: Python 3 standard library, and the Android SDK's own tools.

## Web remote

```bash
python3 server.py
```

Open http://localhost:8790 (or `http://<this computer's IP>:8790` from a phone on the same Wi-Fi), tap **＋ Add**, enter the TV's IP and the PIN shown on the TV. Paired TVs are saved in `tvs.json`.

## Android app

Needs the Android SDK (build-tools 36.1.0, platform android-36) and a JDK. No Gradle.

```bash
sh android/build.sh
adb install -r android/TVRemote.apk
```

## Command line

```bash
python3 tv.py pair          # a PIN appears on the TV
python3 tv.py pin 1234
python3 tv.py volume 20
python3 tv.py input HDMI-1
python3 tv.py app youtube
```

Set `TV_HOST=<ip>:7345` to point it at a different TV.

## Notes

- The TV uses a self-signed certificate, so certificate checks are turned off for it.
- `tvs.json` and `.tv_auth.json` hold pairing tokens and are git-ignored.
- The TV has no way to receive typed text from outside, so there is no keyboard feature.
