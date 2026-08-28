<div align="center">
  <h1>🛡️ Hikari AdBlock</h1>
  <p><strong>Free, open-source, no-root ad blocker for Android.</strong></p>
  <p>Blocks ads, trackers and malware system-wide via a local VPN DNS filter.</p>
  <p>Built with Kotlin and Jetpack Compose (Material 3).</p>
  <br>
  <a href="https://github.com/codegeasse1/hikari-adblock/releases">
    <img src="https://img.shields.io/github/v/release/codegeasse1/hikari-adblock">
  </a>
  <a href="https://github.com/codegeasse1/hikari-adblock/releases">
    <img src="https://img.shields.io/github/downloads/codegeasse1/hikari-adblock/total">
  </a>
</div>

---

## Download

Grab the latest APK from the [Releases](https://github.com/codegeasse1/hikari-adblock/releases)
page. Every push to `main` is built automatically by GitHub Actions; manual releases are signed.

## Features

* **System-wide ad blocking via local VPN** — no root required
* Blocks ads, trackers, phishing and malware domains
* **DNS-based filtering**: queries to blocked domains return `NXDOMAIN`, so ads can never load
* Bundled **StevenBlack hosts** blocklist (~80k domains) with one-tap updates
* **Auto-update** the blocklist on a schedule (6h / 12h / 24h / 48h)
* **Whitelist** and **custom blocked** domains (suffix matching)
* Live **query log** with search and blocked/all filter
* Stats: total queries, blocked count and block rate
* **Start on boot** option
* Dark / light / system theme (Material 3)
* 100% local — no data ever leaves your device

## How it works

Hikari AdBlock creates a private `VpnService` tunnel and routes only the packets destined for
your network's DNS servers into it. Every DNS query is checked against the blocklist using
suffix matching:

* blocked domain → an `NXDOMAIN` response is synthesized, so the ad/tracker never resolves;
* everything else → forwarded unchanged to the real DNS server.

All other traffic (TCP, HTTPS, etc.) is untouched and keeps flowing directly — the VPN does
not MITM or decrypt anything. No root, no private CA, no data collection.

> **Note on encrypted DNS (DoH/DoT):** DNS sent inside apps' own encrypted channels is passed
> through for privacy, so those particular queries are not filtered. This is an intentional
> no-root, no-MITM design choice.

## Building

### Requirements

* Android Studio (Ladybug or newer) — or just JDK 17 + Android SDK 36
* min SDK 26 (Android 8.0), target/compile SDK 36

### Steps

```bash
git clone https://github.com/codegeasse1/hikari-adblock.git
cd hikari-adblock
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK (debug-signed unless CI key is provided)
```

Install the debug APK on a device with `adb install app/build/outputs/apk/debug/app-debug.apk`.

### CI

`.github/workflows/build.yml`:
* builds debug + release APKs and runs unit tests on every push to `main`;
* a manual `workflow_dispatch` run with the **release** checkbox creates a GitHub Release
  with an APK signed by a CI-generated keystore (override with the `KEYSTORE_PASSWORD` secret).

## License

GPL-3.0 — see [LICENSE](LICENSE).

## Credits

* Blocklist: [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (MIT)
* Architecture inspired by [BlockAds](https://github.com/pass-with-high-score/blockads-android)
