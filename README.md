<p align="center">
  <img src="docs/assets/icon.png" width="104" alt="Anilili app icon" />
</p>

<h1 align="center">AnimeX</h1>

<p align="center">
  <strong>A personal, TV-first build of Anilili.</strong><br />
  Native Android TV and Fire TV playback, offline episodes, AniList and MyAnimeList sync —
  built, signed and published from this repository.
</p>

<p align="center">
  <a href="https://github.com/d1dac0/AnimeX/releases/latest"><img src="https://img.shields.io/github/v/release/d1dac0/AnimeX?style=flat-square&label=release&color=8979F2" alt="Latest GitHub release" /></a>
  <a href="https://github.com/d1dac0/AnimeX/releases"><img src="https://img.shields.io/github/downloads/d1dac0/AnimeX/total?style=flat-square&label=downloads&color=8979F2" alt="Total GitHub downloads" /></a>
  <img src="https://img.shields.io/badge/Android-5.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 5.1 and newer" />
  <img src="https://img.shields.io/badge/Kotlin-Native_UI-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Built with Kotlin" />
  <img src="https://img.shields.io/badge/Android_TV-Fire_TV-FF9900?style=flat-square&logo=amazonfiretv&logoColor=white" alt="Android TV and Fire TV" />
</p>

<p align="center">
  <a href="https://github.com/d1dac0/AnimeX/releases">All releases</a> ·
  <a href="https://github.com/d1dac0/AnimeX/issues">Report a problem</a>
</p>

---

## What this is

AnimeX is my personal fork of **Anilili**, the native Kotlin and Jetpack Compose anime client. This
repository builds, signs and publishes the Android TV and Fire TV flavour, and nothing else; it is
not the upstream project and does not pretend to be.

The app still installs as **Anilili** — that is the launcher name, the asset name in every release
(`Anilili_tv*.apk`) and what the in-app updater looks for — so this README keeps the name wherever
it means what lands on the device.

### What is different here

- **TV only.** The builds are `assembleTvDebug` and `assembleTvRelease`. The `mobile` flavour is
  still in the shared source, but this fork does not build, publish or verify it.
- **Releases are the update channel.** Every push to `main` builds a signed TV APK, publishes it
  with an `update.json` manifest, and broadcasts that manifest to the Nostr relays. The app's
  update check reads this repository's Releases, not a chat server.
- **Downloads are here.** The three TV APKs and the update manifest live on this repository's
  Releases page, built by the workflow in `.github/workflows/release.yml`.
- **Support happens here.** [Issues](https://github.com/d1dac0/AnimeX/issues), with the app's shared
  diagnostics ZIP attached when there is a reproducible problem.

## TV experience

<table>
  <tr>
    <th width="50%">A cinematic, remote-friendly home</th>
    <th width="50%">Details and episodes side by side</th>
  </tr>
  <tr>
    <td><a href="showcase/tv/home.webp"><img src="showcase/tv/home.webp" width="100%" alt="Anilili home screen on Android TV" /></a></td>
    <td><a href="showcase/tv/details.webp"><img src="showcase/tv/details.webp" width="100%" alt="Anilili anime details and episode list on Android TV" /></a></td>
  </tr>
</table>

<p align="center">
  <strong>Fullscreen playback designed around D-pad controls</strong><br /><br />
  <a href="showcase/tv/player.webp"><img src="showcase/tv/player.webp" width="92%" alt="Anilili fullscreen Android TV player controls" /></a>
</p>

| Watch your way | Your anime, in sync | Built for the couch |
| --- | --- | --- |
| Native Media3 playback, multiple sources, subtitles, skip intro/outro, quality selection, and offline episodes. | Sign in with AniList or MyAnimeList, update your list from the player, resume episodes, and optionally sync watched progress. | Large-screen layouts, D-pad navigation, visible focus states, TV-safe spacing, and player controls designed for a remote. |

## Highlights

- **Multiple streaming sources:** automatic discovery and fallback across Miruro's providers (native
  and iframe embeds) and Anivexa-backed providers, with server priority and sub/dub filtering.
- **Native video player:** Media3 playback with quality selection, audio tracks, provider captions,
  manual subtitle-file import, optional free OpenSubtitles search, caption styling and timing,
  playback speed, and content scaling. A **quality readout** names the resolution — 4K, 1080p,
  720p — for a few seconds when an episode starts and again whenever adaptive playback changes it,
  without pausing or taking the remote.
- **Offline viewing:** background episode downloads, bulk season downloads, external subtitles,
  progress notifications, offline playback, and optional MP4 export to `Downloads/Anilili/`.
- **AniList and MyAnimeList:** optional login, list views, Add to My List from the player, resume
  positions, and watched-episode progress sync.
- **TV-first controls:** D-pad navigation, focus that comes back to the card you left on Home, skip
  intro/outro with a visible countdown, an episode drawer, next/previous navigation, and a sleep
  timer that pauses after a set time or when the episode ends.
- **Continue Watching that keeps up:** every show in progress rather than the first twelve,
  next-episode countdowns for titles the published schedule misses, and resume positions.
- **Backup and restore:** save every setting, your saved anime and Continue Watching to one JSON
  file, and restore it on another device or after a reinstall. Logins can travel too, sealed under a
  passphrase you choose: AniList and OpenSubtitles as themselves, MyAnimeList as a one-time sign-in
  the restoring device redeems for a session of its own. Without the passphrase the file signs
  nobody in.
- **Updates from this repository:** the app checks Releases, verifies a signed manifest, and falls
  back to the Nostr relays carrying the same event. Settings → Check for updates.
- **Useful diagnostics:** shareable support reports containing app, playback, device, network, and
  recent diagnostic information when troubleshooting is needed.

The phone half of TV sign-in pairing — a television shows a code, a phone signs in and hands the
session over — is in the source, but it needs the `mobile` flavour to be built. This fork publishes
none, so the TV-side pairing screens in Settings work only with a phone build you make yourself;
the protocol is implemented and unit-tested, but has not yet been run across two real devices.

<details>
  <summary><strong>Streaming providers</strong></summary>

Anilili can resolve episodes from Miruro, Senshi, AniBD, AniKoto, KickAssAnime, AllAnime,
AnimeKai, ReAnime, AniZone, AnimeGG, AniNeko, 2DHive, RareAnimes, and additional compatible
sources. Availability can vary by title, language, region, and provider uptime.

</details>

## Install

1. Open [Releases](https://github.com/d1dac0/AnimeX/releases/latest) on the device or on a computer.
2. Download one of the three TV APKs.
3. Sideload it: `adb install Anilili_tv.apk`, a file manager on the TV, or — on Fire TV — the
   Downloader app pointed at the release URL.
4. Launch Anilili. Signing in to AniList or MyAnimeList is optional.

| APK | Best for |
| --- | --- |
| `Anilili_tv.apk` | **Recommended.** Universal TV build. |
| `Anilili_tv_arm64-v8a.apk` | Smaller download for 64-bit TV boxes and newer Fire TV sticks. |
| `Anilili_tv_armeabi-v7a.apk` | Older 32-bit Fire OS sticks. |

**Compatibility:** Android 5.1 / Fire OS 5 (API 22) or newer.

Every release is a TV build, so there is no phone APK on the Releases page. The `mobile` flavour can
still be built from this source; see below.

## Updates

Merging to `main` publishes a release. Nobody edits a version number by hand.

`.github/workflows/release.yml` runs on every push to `main` and:

1. Numbers the build from the run: `appVersionCode = 96 + run`, `appVersionName = 0.1.(84 + run)`.
2. Builds `:app:assembleTvRelease` with the release keystore from the repository secrets.
3. Creates a **draft** GitHub release with `Anilili_tv.apk`, `Anilili_tv_arm64-v8a.apk` and
   `Anilili_tv_armeabi-v7a.apk`.
4. Signs the update manifest with `scripts/nostr_update.py` and attaches it as `update.json`.
5. Publishes the release, then broadcasts the same signed event to the Nostr relays. A relay
   refusing the event is fine; the release copy is authoritative.

The app reads
`https://github.com/d1dac0/AnimeX/releases/latest/download/update.json`, verifies the signature
against the public key built into the app, and compares build numbers — which is why a rebuild with
an unchanged version name still reaches an install, quietly. If GitHub is unreachable, the relays
carrying the same event are the fallback. The check runs at launch, or on demand in Settings →
Check for updates.

A run that fails after the draft is created leaves a draft behind rather than a half-published
release, so a broken build never becomes the "latest" the updater reads.

## Build from source

<details>
  <summary>Developer setup and release notes</summary>

### Requirements

- JDK 17
- Android SDK API 36 (build tools 35.0.1), or Android Studio
- Gradle 8.13 when building without the included wrapper

### Build

This fork builds the TV flavour only:

```bash
./gradlew :app:assembleTvDebug
./gradlew :app:assembleTvRelease
./gradlew :app:testTvDebugUnitTest
```

On Windows, use `gradlew.bat` with the same tasks.

`gradle.properties` enables the configuration cache. Do not pass `--no-configuration-cache`: a build
prints `Configuration cache entry stored` once and `Reusing configuration cache` afterwards, and a
failure there is a build-script bug rather than a reason for the flag.

Online subtitle search is optional. Register a free API consumer at OpenSubtitles.com and provide
its key outside the checkout, either as `openSubtitlesApiKey=...` in your Gradle user properties
(`~/.gradle/gradle.properties`) or through the `OPEN_SUBTITLES_API_KEY` environment variable.
Never commit the key. Viewers sign in once under Settings with their own free OpenSubtitles.com
account. Builds without a consumer key still support manual SRT, VTT, ASS, SSA, TTML, and XML
subtitle import. Episode stills the CDN has not caught up with can be fetched from TMDB the same
way, through `tmdbReadToken` or `TMDB_READ_TOKEN`.

### Signing keys

`anilili-release.jks` signs every release APK and `nostr-update-key.properties` signs the update
manifest; CI reads both from repository secrets. Both are gitignored, which also means nothing
restores them if the working tree is lost.

**They are irreplaceable.** Android refuses an update signed with a different key, so losing the
keystore strands every existing install permanently, and the shipped apps accept only manifests
signed by that Nostr key. Keep backups off this machine.

### Project map

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/anilili/data` | Models, provider catalog, storage, and sync |
| `app/src/main/java/com/anilili/data/remote` | AniList, MAL, Miruro, and provider clients |
| `app/src/main/java/com/anilili/playback` | Media3 session, downloads, and update services |
| `app/src/main/java/com/anilili/ui` | Compose screens and the player |
| `showcase/tv` | The TV screenshots used in this README |
| `scripts/nostr_update.py` | Signs and publishes the update manifest |
| `.github/workflows/release.yml` | Builds, signs, and publishes every merge to `main` |
| `docs/` | Protocol notes and the static project page |

</details>

## Community and support

Support is [GitHub Issues](https://github.com/d1dac0/AnimeX/issues), which is also where release
notes and provider-status discussions belong. For a reproducible bug, attach the app's shared
diagnostics ZIP (Settings → Send diagnostics) so the report carries the app, playback, device, and
network details.

## License and disclaimer

AnimeX is a personal and educational fork of Anilili, released under the same **MIT No Attribution**
license — see [LICENSE](LICENSE); the upstream copyright is with its author. It is not affiliated
with AniList, MyAnimeList, or any streaming provider. The app hosts no video content; streams are
resolved from third-party providers at playback time. Availability and legality can vary by region,
and users are responsible for following the laws and terms that apply to them.
