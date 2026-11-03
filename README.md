# Seafile Big Upload

An **unofficial** Android app that automatically uploads your phone's photos and videos to your own [Seafile](https://www.seafile.com/) server — in chunks, so it keeps working when a proxy such as
**Cloudflare** (free plan: 100 MB per request) rejects large files, and with a built-in check that every file really arrived.

> Not affiliated with Seafile Ltd. "Seafile" is a trademark of its respective owners.
> Italiano: vedi la sezione in fondo.

## Why

The official Seafile Android app sends any file up to 200 MiB in one single request and only uses chunks above that. Behind a Cloudflare Tunnel/Proxy on the free or Pro plan, files between about
105 MB and 200 MiB are rejected with `413` and the app retries them forever. I reported it upstream ([haiwen/seadroid#1208](https://github.com/haiwen/seadroid/issues/1208)); this app is a workaround
that does the whole job on its own.

## Features

- **Uploads videos, photos or both** (default: videos only) from the albums you choose. Works in the background, even with the app closed.
- **Chunked upload** (8 MiB chunks with `Content-Range`), so every request stays far below proxy limits.
- **Verifies every upload**: after each file it asks the server for the size and only then marks it as done. Nothing is ever overwritten or deleted, on the phone or on the server.
- **Keeps the original date** of each file on the server (`last_modify`).
- **Registry of what was uploaded**: no need to re-read the whole server every time. A one-time comparison with the server avoids re-uploading files that are already there (also in subfolders).
- **Optional subfolders** like OneDrive: none, by year (`2026`) or by year and month (`2026/11`), using the date in the file name or the date taken.
- **Safety check you can tune**: every 15 minutes, hourly (default), every 6 hours, daily or weekly. Empty checks make no network requests and show no notification.
- **Wi-Fi only** by default, with progress shown during the comparison and the upload.
- **Sign in with username, password and 2FA, or with single sign-on (Google)** if your server has it enabled: the same flow as the official Android app (`/shib-login` in a WebView), no server changes needed.
- **Upload any file or folder by hand**: pick files or a whole folder on the phone, browse your libraries and folders on the server, and if a file already exists choose *Cancel*, *Overwrite* or *Keep both* (`name (1).ext`, like Windows). Big files are sent in chunks too.
- **Per-device token** (shows up in Seafile's *Connected devices* and can be revoked there); your password is never stored, the token is encrypted with a key that stays on the phone.
- **Save / restore the registry and settings** to a file, for reinstalls or a new phone.
- English and Italian, with a language picker (default: your phone's language, English unless it is Italian).

## Requirements

- A Seafile server (developed and tested against **Seafile Pro 13** behind a **Cloudflare Tunnel**, free plan). Two-factor authentication is supported.
- Android 8.0 or later.
- On some phones (Samsung, Xiaomi…) set the app's battery usage to *unrestricted* so background uploads are not stopped; the app tells you its state.

## Install

Build it yourself (below) or install an APK you built/trust: copy the file to the phone and install it ("install unknown apps" must be allowed). The app is not on the Play Store.

## Build

```bash
# JDK 17 (e.g. the one bundled with Android Studio) and the Android SDK are required
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug        # debug build, signed with the debug key
./gradlew assembleRelease      # release build: needs your own signing key (see below)
```

### Signing a release build

Create a keystore **outside this repository** and a `key.properties` file in the project root (both are git-ignored — never commit them):

```properties
storeFile=/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Keep a safe backup of the keystore and its password: updates can only be installed over an existing install if they are signed with the same key.

## How it works (short)

1. Reads the media of the chosen albums from `MediaStore`.
2. Compares them once with the file list on the server and records what is already there.
3. For each new file: asks the server if it exists, creates the subfolder if needed, gets an upload link, sends the file (in 8 MiB chunks above 8 MiB), then asks the server for the size and the date.
4. Runs from a periodic WorkManager job (the safety net) and from a MediaStore content trigger (an accelerator, not reliable on its own), and only when there is something to upload.

## Privacy

The app only talks to the server address you enter (with "Sign in with Google", Google's sign-in page opens inside the app through your server). No analytics, no third parties. The password is not saved; the access token is encrypted with an Android Keystore key.

## Status

A personal project, used daily by its author. Issues and suggestions are welcome, but there is no support guarantee.

## License

[MIT](LICENSE) © 2026 William Baglioni

---

## Italiano

App Android **non ufficiale** che carica da sola foto e video del telefono sul tuo server Seafile, **a pezzi**, così funziona anche quando davanti al server c'è Cloudflare (piano gratuito: 100 MB per richiesta),
e **controlla ogni file** dopo il caricamento. Conserva la data originale, tiene un registro di cosa è già stato caricato, può creare sottocartelle per anno o per anno/mese e ha un controllo di sicurezza a
frequenza scelta (15 minuti, 1 ora, 6 ore, 1 giorno, 1 settimana). Non cancella e non sovrascrive mai niente. Si installa direttamente dal file (non è sul Play Store). Ideata da William Baglioni.
