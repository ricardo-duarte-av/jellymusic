# JellyMusic

Android Jellyfin music-only player. `applicationId` = `pt.aguiarvieira.jellymusic`.

## Releasing (tag = release)

The Play Store upload is **not** triggered by pushing to `main`. Commits to `main`
build the AAB in CI but never publish. The `Publish to Play Store` job in
`.github/workflows/ci.yml` gates on `refs/tags/v*` — **pushing a `vX.Y.Z` tag is
what cuts a release.**

To ship a release:

1. Bump **both** in `app/build.gradle.kts`:
   - `versionCode` (integer, must strictly increase — Play rejects duplicates)
   - `versionName` (e.g. `"0.1.31"`)
2. Commit to `main` and push.
3. Tag the commit and push the tag:
   ```
   git tag -a v0.1.31 -m "Release 0.1.31: <summary>"
   git push origin v0.1.31
   ```

The tag run then: builds the signed release AAB → uploads it to the Play
**internal** track, published **live** to testers automatically (no manual
promote) → cuts a GitHub Release with the debug + release APKs attached.

`versionCode`/`versionName` and the tag must line up: tag `v0.1.31` should carry
`versionName = "0.1.31"`. There is no closed beta or production track — internal
testing only.

## Build & test workflow

Don't build locally on every change — it's slow. Commit + push to `main`; the
user builds/tests on a real device (and reads `adb logcat`). CI builds debug +
release on every push to any branch.

Release builds run **R8** (`isMinifyEnabled = true`); debug does not. Anything
instantiated reflectively (e.g. Glance `ActionCallback`s, kotlinx.serialization
models) needs an explicit keep rule in `app/proguard-rules.pro`, or it works in
debug and silently breaks in release.
