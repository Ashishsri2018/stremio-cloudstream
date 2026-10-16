# AGENTS.md — cs3addon / StreamioLink

CloudStream 3 (Android) plugin in Kotlin that plays Stremio-addon streams.
Single Gradle module `StreamioLink`; package `com.cs3addon.StreamioLink`.
Canonical repo: `Ashishsri2018/stremio-cloudstream` (CI publishes builds there).

## Commands

- Unit tests: `./gradlew testDebugUnitTest` — pure JVM, no network, cover `StremioMapping`.
- Single test: `./gradlew testDebugUnitTest --tests "com.cs3addon.StreamioLink.StremioStreamTest"`.
- Plugin build: `./gradlew make makePluginsJson` → `.cs3` + `build/plugins.json`.
- Local builds need Android SDK 35 (`compileSdk 35`), a `local.properties`/`ANDROID_HOME`, and network for Gradle 8.12 + JitPack. None of that is set up in this workspace, so treat `.github/workflows/build.yml` as the build source of truth (JDK 17 + Android SDK).

## Hard constraints — do not "clean up"

- Jackson stays pinned to `2.13.1` (`build.gradle.kts:91`); newer breaks older Android devices.
- JVM target must stay **11** — the cloudstream3 stub's inline `getKey`/`setKey` are JVM-11 bytecode.
- Correct JitPack coordinate is `com.github.recloudstream.gradle:gradle:master-SNAPSHOT`. The widely documented `com.github.recloudstream:gradle` only serves a stub. The workflow's JitPack warm-up + Gradle retry loops exist for real 404s — keep them.
- Module **directory name = plugin display name** in CloudStream (`CloudstreamExtension` has no name field). Renaming `StreamioLink/` renames it for users.
- Bump the `version` int in `StreamioLink/build.gradle.kts` when publishing, so CloudStream replaces an already-installed copy.
- `settings.gradle.kts` auto-includes every subdir with a `build.gradle.kts` (except `disabled`); new modules register without editing it.
- `StremioMapping` must stay free of Android/CloudStream imports (it is the unit-tested layer). Android/CloudStream code belongs in `StremioApi`/`StremioProvider`/`StremioSettingsDialog`.

## Quirks

- Install URL must include the branch: `https://raw.githubusercontent.com/Ashishsri2018/stremio-cloudstream/main/repo.json`. Branch-less raw URLs 404, and raw.githubusercontent serves stale CDN copies for minutes after a push.
- `repo.json` (manifestVersion 2, main branch) points users at `builds/plugins.json`. The `builds` branch is a force-orphaned CI artifact — never hand-edit it; publish via CI.
- Settings UI is programmatic (no XML resources). `StremioMapping.normalizeAddonUrl` is the single URL normalizer — use it for all addon URLs (accepts base URL or full `manifest.json`).
- Stream-kind detection uses `contains`, not `endsWith` (addons like PenguPlay append `|^|meta` after the extension).

## Docs

- `architecture.md` — system layout + data flow; `memory.md` — running log of release/CI lessons (append new dated entries). Keep both in sync with behavior changes.
- Commit style is terse descriptive (`git log`); CI builds on pushes to `main`/`master`.
