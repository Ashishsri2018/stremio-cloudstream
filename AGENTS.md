# AGENTS.md — cs3addon / StreamioLink

CloudStream 3 (Android) plugin in Kotlin that plays Stremio-addon streams.
Single Gradle module `StreamioLink`; package `com.cs3addon.StreamioLink`.
Canonical repo: `Ashishsri2018/stremio-cloudstream` (CI publishes builds there).

## Environment (verified — do not re-check every run)

- **No `gradle`/`./gradlew` execution possible here**: no Android SDK, no `local.properties`/`ANDROID_HOME`. Do not try `./gradlew ...`; it fails at configuration. Never spend turns probing for it.
- **`git` and `gh` ARE installed and authenticated** as `Ashishsri2018` (git user/email set, `gh auth status` logged in, HTTPS push works). Commit/push and watch CI with `gh run watch <id> --exit-status` directly — no need to verify auth first.
- CI (`.github/workflows/build.yml`) is the only real build; it runs on push to `main` and publishes to the `builds` branch.

## Commands

- Unit tests: `./gradlew testDebugUnitTest` — pure JVM, no network, cover `StremioMapping`.
- Single test: `./gradlew testDebugUnitTest --tests "com.cs3addon.StreamioLink.StremioStreamTest"`.
- Plugin build: `./gradlew make makePluginsJson` → `.cs3` + `build/plugins.json`.
- Addon playtest: `python3 tools/playtest.py <addon-url> --id tt0111161 --type movie` (live network, stdlib only) — **INCOMPLETE / LOW PRIORITY, not maintained**: it still mirrors the old mapping, does not build magnet/P2P links, and can report "no streams" for torrent-only addons. Don't rely on it; the plugin + its JVM unit tests are the source of truth. When it does something, `--manifest` prints a compatibility summary, `--search`/`--catalog ... --play` are other entry points, `--deep` fetches an HLS segment, `--json` is machine output.
- Live addons used for playtesting are recorded in `tools/test-addons.json` (nuvio catalog + hdhub/pengu/cnc/meteor, test id `tt0111161`). Keep it updated when addon configs change.
- Local builds need Android SDK 35 (`compileSdk 35`), a `local.properties`/`ANDROID_HOME`, and network for Gradle 8.12 + JitPack — none present here, so treat `.github/workflows/build.yml` as the build source of truth (JDK 17 + Android SDK).

## On-device debugging — rish + logcat (verified working)

This shell is a **PRoot Ubuntu container** inside Termux on the test device (Samsung SM-A156E; CloudStream is `com.lagradost.cloudstream3`). We can reach the device through **Shizuku via `rish`** and read `logcat`. There is **no `adb` binary on the device** (`/system/bin/adb` doesn't exist) — `rish` *is* the adb shell (uid 2000 `shell`). Use `rish -c '<cmd>'` for `getprop`/`pm`/`settings`/`cmd` and for `logcat`.

- Wrapper is installed at `/usr/local/bin/rish` (already works; reuse it). It exports `RISH_APPLICATION_ID=com.termux` and calls `/system/bin/app_process` on the dex directly. It needs `rish_shizuku.dex` in the Termux home.
- One-time setup, run in **Termux** (the top-level `files/` dir is *not* bind-mounted into this container — only `usr`, `home`, `cache` are):
  ```sh
  cp /data/data/com.termux/files/rish               /data/data/com.termux/files/home/rish
  cp /data/data/com.termux/files/rish_shizuku.dex   /data/data/com.termux/files/home/
  ```
- Why a wrapper: the stock `rish` script fails here — its Android-14 "dex must be non-writable" guard can't `chmod` (Android ACLs make it a no-op under PRoot), and its app-id auto-detection walks `/proc` names that are container paths, so it never finds `com.termux`. The wrapper skips both.
- First call may print `Waiting for Shizuku authorization... check your notifications.` — approve it in the Shizuku notification on the device.

Live plugin capture (tags: `StreamioLink` from our plugin; `PlayViewGen`/`RepoLink`/`PlayerGen` are CloudStream core):

```sh
rish -c 'logcat -c'                                                        # clear buffer
nohup rish -c 'logcat -v time -s StreamioLink:V PlayViewGen:V RepoLink:V PlayerGen:V' \
  > /root/streamio.log 2>&1 & echo $! > /root/streamio.pid                 # start (use `-s`, not `*:S`)
# reproduce on device: info card -> Play (note link count); back -> long-press episode -> Reload mirrors
kill "$(cat /root/streamio.pid)"                                          # stop
grep -c "Loaded ExtractorLink" /root/streamio.log                         # count links
grep -nE "PlayViewGen|RepoLink|StreamioLink" /root/streamio.log           # phase markers
```

Split phases at each `PlayViewGen: loadLinks` line (Play is the first, Reload mirrors the second); count `Loaded ExtractorLink` before/after. A verified run of *The Avengers* (`tt0848228`) produced 274 links in one phase.

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
