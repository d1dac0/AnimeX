# Working agreements

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
