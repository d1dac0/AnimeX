# Working agreements

## Every change starts as an issue

Every piece of work is a GitHub issue in this repository before any code is written — a bug, a
feature, a build fix, a documentation edit, this rule included. The issue says what is wrong and
what done looks like; the branch is named after it; the pull request says `Closes #<n>` so merging
closes it. A change with no issue is a change nobody can find later.

If GitHub Issues is ever disabled on this fork, enable it (`has_issues`) — the rule has no exception
for small work.

## Builds: TV only

Always and only build the TV flavour. This checkout ships to Android TV and Fire TV; the mobile
flavour is not built, installed, or verified here, and a task that builds `mobile` is the wrong
task. Use the variants below, and do not add `mobile` tasks to a check run to "keep parity".

```bash
./gradlew :app:assembleTvDebug          # debug APK for the TV
./gradlew :app:assembleTvRelease        # release build, signed and minified
./gradlew :app:testTvDebugUnitTest      # unit tests
```

## The configuration cache is on

`gradle.properties` sets `org.gradle.configuration-cache=true`. Do not pass
`--no-configuration-cache`. If a build fails at the store phase, that is a build-script bug to fix —
a closure capturing a script object, a task reading `project` at execution time — not a reason for a
flag. Builds are expected to print `Configuration cache entry stored` once and
`Reusing configuration cache` after.

## What this is

AnimeX is a personal, TV-first fork of Anilili: a native Kotlin and Jetpack Compose anime client
for Android TV and Fire TV. It installs as **Anilili** (`applicationId com.miruronative`, package
`com.anilili`), supports Android 5.1 / Fire OS 5 (API 22) and newer, and is built with JDK 17
against SDK 36.

## Where the code lives

- `app/src/main` is the shared app: `data/` (repository, settings, library, auth, pairing, update),
  `playback/` (Media3, downloads, subtitles), `ui/` (Compose screens), `diagnostics/`, `errors/`.
- `app/src/tv` and `app/src/mobile` hold only what differs per form factor. Cast is mobile-only
  (`mobileImplementation`), so TV code must not reference it.
- `app/src/test` is the JVM unit-test suite; `app/src/androidTest` and `app/src/androidTestMobile`
  are instrumented.
- `scripts/` and `tools/` hold the Python and Node probes used to reverse-engineer providers;
  `docs/investigations/` holds the write-ups.

## Debug is a different app

Debug builds append `.debug` to the applicationId, so they install beside the release and start
with no library, watchlist, downloads or tokens. Anything that depends on real user data has to be
tested with a release build, or the empty state will read as a bug.

## Tests and the parity corpus

`./gradlew :app:testTvDebugUnitTest` runs the suite. Unit tests run with
`unitTests.returnDefaultValues = true` because `DiagnosticsLog` reads `SystemClock`, and a real
`org.json` is on the test classpath because the stub in `android.jar` returns null.

`preBuild` runs `syncParityCorpus`, which copies `../anilili-desktop/fixtures/parity` into
`app/src/test/resources/parity` when that sibling checkout exists. The committed copy is what runs
when it does not; a case added on the desktop side appears in `git status` and is committed with
whatever made it pass.

## Merging to main publishes a release

`.github/workflows/release.yml` runs on every push to `main`: it numbers the build from the run
(`versionCode = 96 + run`, `versionName = 0.1.(84 + run)`), builds `:app:assembleTvRelease`, and
publishes a release with the three TV APKs and a signed `update.json`. Never edit a version number
by hand, and treat a merge to `main` as a release.

APK names are load-bearing: the universal `Anilili_tv.apk` must sort before the ABI splits, and
split names use `_`, never `-`, because `-` sorts before `.` and older updaters install the first
asset they find.

## Signing material and keys

`anilili-release.jks`, `keystore.properties` and `nostr-update-key.properties` are gitignored and
irreplaceable: Android refuses an update signed with a different key, and the app accepts only
manifests signed by that Nostr key. CI reads them from repository secrets. API keys
(`OPEN_SUBTITLES_API_KEY`, `TMDB_READ_TOKEN`, or the matching Gradle user properties) are passed
from outside the checkout and never committed.

## Read these before changing the matching area

- `docs/PIPE_PROTOCOL.md`: the reverse-engineered Miruro pipe contract. Keep it in sync when the
  protocol changes.
- `docs/TV_PAIRING_PROTOCOL.md`: phone-to-TV sign-in. Implemented and unit-tested, never run across
  two real devices.
- `docs/DIAGNOSTICS.md`: what a diagnostics ZIP contains and what gets redacted. Extend the
  redaction when adding a field that can carry user content.
- `docs/ERROR_CODES.md`: generated from `AnililiError` by `ErrorCatalogTest`. Change the catalog,
  not the doc; the codes are the only thing that survives a minified build.
