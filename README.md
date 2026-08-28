<div align="center">
  <h1>🌞 Hikari AdBlock</h1>
  <p><strong>Free, open-source, no-root ad blocker for Android.</strong></p>
  <p>System-wide ad, tracker and malware blocking over a local full-tunnel VPN.</p>
  <p>Built with Kotlin, Jetpack Compose (Material 3) and a Go TUN stack.</p>
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

* **System-wide ad blocking via a local full-tunnel VPN** — no root required
* Real TCP/IP stack (gVisor) inside the tunnel: DNS answers on `:53`, every other packet is
  forwarded to your real network, so **internet keeps working** while ads are filtered
* Blocks ads, trackers, phishing and malware domains via powerful filter lists
* **HTTPS / cosmetic filtering** (optional): hides ad banners visually in selected browsers
  using a locally-generated root CA — never intercepts banking/Google traffic
* **Firewall** per-app allow/deny rules
* **WireGuard** tunnel profiles (import `.conf`)
* **Root proxy mode** (iptables redirect) for rooted devices
* **Trusted networks**: auto-pause on your home/office Wi-Fi
* **Protection profiles** with schedules and per-profile DNS providers
* **Live query log** with search, blocked/all filter and per-domain details
* **Statistics**: total queries, blocked count, block rate and charts
* Home-screen **widget** and **Quick Settings tile** toggle
* Tasker / automation intents, start-on-boot, auto filter updates
* Dark / light / system theme (Material 3) and custom accent colors

## How it works

Hikari AdBlock builds a `VpnService` tunnel and runs a full TCP/IP network stack (gVisor,
shipped as a prebuilt gomobile AAR) inside it. The stack answers DNS queries itself on `:53`:

* query for a blocked domain → an `NXDOMAIN`/block response is synthesized, so the ad/tracker
  never resolves;
* everything else (DNS and all regular TCP/UDP traffic, including YouTube and other apps)
  is forwarded through the real network, so the internet keeps working normally.

Filter lists are fetched from the
[blockads-default-filter](https://github.com/pass-with-high-score/blockads-default-filter)
repository, compiled locally, and periodically auto-updated.

## Building

### Requirements

* Android Studio (Ladybug or newer) — or just JDK 17 + Android SDK 36
* min SDK 24 (Android 7.0), target/compile SDK 36
* The prebuilt tunnel AAR at `app/libs/tunnel.aar` (already committed)

### Steps

```bash
git clone https://github.com/codegeasse1/hikari-adblock.git
cd hikari-adblock
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK (debug-signed unless CI key is provided)
```

Install the debug APK on a device with `adb install app/build/outputs/apk/debug/app-debug.apk`.

### Rebuilding the tunnel AAR (advanced)

The Go tunnel is at `github.com/nqmgaming/blockads-tunnel` (GPL). To rebuild `app/libs/tunnel.aar`:

```bash
cd <tunnel-source>
GOFLAGS=-buildvcs=false gomobile bind -target=android -androidapi 24 -trimpath \
  -ldflags="-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
  -o <this-repo>/app/libs/tunnel.aar github.com/nqmgaming/blockads-tunnel
```

### CI

`.github/workflows/build.yml`:
* builds debug + release APKs and runs unit tests on every push to `main`;
* a manual `workflow_dispatch` run with the **release** checkbox creates a GitHub Release
  with an APK signed by a CI-generated keystore.

## License

GPL-3.0 — see [LICENSE](LICENSE).

## Credits

* This app is a fork of [BlockAds](https://github.com/pass-with-high-score/blockads-android) (GPL-3.0)
* Go tunnel stack: [blockads-tunnel](https://github.com/nqmgaming/blockads-tunnel) (GPL-3.0)
* Filter lists: [blockads-default-filter](https://github.com/pass-with-high-score/blockads-default-filter)
