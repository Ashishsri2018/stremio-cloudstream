# StreamioLink Memory

## Recent Changes
- Initial scaffold: CloudStream 3 plugin that plays Stremio addon streams.
- 1 catalog addon (default Cinemeta) + N source addons, managed via programmatic settings dialog.
- Pure protocol/mapping layer with JVM unit tests; CI builds .cs3 and publishes to `builds` branch.
- Repo created + CI green: https://github.com/Ashishsri2018/stremio-cloudstream (run 38729243040).
- Install URL: https://raw.githubusercontent.com/Ashishsri2018/stremio-cloudstream/main/repo.json
  (branch-less raw URL 404s; always include /main/)
- Published artifacts: `builds` branch -> `StreamioLink.cs3` + `plugins.json`.

## Build/CI Lessons (important)
- JitPack coordinate `com.github.recloudstream:gradle:master-SNAPSHOT` only serves an
  auto-generated stub; the real plugin jar is `com.github.recloudstream.gradle:gradle:master-SNAPSHOT`.
  This caused "Could not find com.github.recloudstream:gradle:master-SNAPSHOT" after JitPack purged
  its cache (~90 days). Workflow now warms the correct coordinates and retries Gradle on transient 404s.
- Module must compile with JVM target 11: the cloudstream3 stub's inline `getKey`/`setKey`
  (DataStore) are JVM-11 bytecode and fail to inline into JVM 1.8 targets.
- Plugin list display name = module directory name (no `name` field in CloudstreamExtension),
  so the module dir was renamed `StremioPlugin` -> `StreamioLink`.
- raw.githubusercontent.com serves stale CDN copies for minutes after a push; verify with the API.
- Package/namespace renamed `com.magic.stremio` -> `com.cs3addon.StreamioLink` (conflict with an
  already-installed package on the user's device); module version bumped to 2 so CloudStream replaces
  the installed v1.
- Live-tested against PenguPlay (debrid-style addon, config blob URL): manifest/stream/subtitles all
  compatible (streams carry `behaviorHints.proxyHeaders.request` + `filename`; donation entry has no
  `url` and is skipped). Found + fixed: `guessStreamKind` used `endsWith` which failed on PenguPlay's
  `file.mkv|^|meta` filenames -> now `contains`; version 3.
- Ideas for future upgrades live in TODO.md (see "Presets"); no version assigned, may never ship.

## 2026-10-16 — bug-fix pass (categorization, timeouts, links, home catalogs) — version 4
- Shipped as version 4 (Presets idea moved to TODO.md with no fixed version).
- Bug: provider showed up under Movies/Series. Fix: `supportedTypes = setOf(TvType.Others)`
  (Others is not episode-based, app/MainAPI.kt `isEpisodeBased`), items still `Movie`/`TvSeries`.
- Bug: slow addons were skipped (links missing). Fix: `loadLinksTimeoutMs = 180_000L`, and
  `loadLinks` now fans out one coroutine per addon that emits links as soon as each responds
  (removed the collect-then-emit `awaitAll`); `MAX_LINKS` shared via `AtomicInteger`,
  first `videoHash`/`videoSize` captured via `AtomicReference` for the subtitle probe.
- Bug: most links failed with `ERROR_CODE_IO_BAD_HTTP_STATUS (2004)`. Root cause (live-verified):
  CloudStream forces a Mozilla `USER_AGENT`; the Pengu/MovieBox CDN `hakunaymatata.com` returns
  HTTP 428 for any `Mozilla/*` UA but 200 for okhttp/VLC/ExoPlayer UAs (16/42 Pengu streams 428).
  Fix: `StremioMapping.requestHeadersFor` injects `DEFAULT_STREAM_USER_AGENT = "okhttp/4.12.0"`
  only when the addon's `proxyHeaders.request` has no `User-Agent`. Injected via `link.headers`,
  which both playback (CS3IPlayer `createVideoSource`) and downloads (DownloadManager
  `appendAndDontOverride`) honor, so one fix covers both. Downloads require VIDEO/M3U8 (DASH
  unsupported) — unaffected by this change.
- Bug: home catalogs stopped after 20 items (Nuvio returns 20/page with `skip`). Fix:
  overrode `mainPage` with one tab per catalog + per-catalog offset map; initial load returns
  every catalog; row expansion re-queries one catalog using `request.data` (`decodeCatalogRef`)
  and `skip=<offset>`, advancing by returned count (`catalogSupportsSkip`).
- `StremioApi` timeout split: manifest/catalog/meta 20 s (`DEFAULT_TIMEOUT_MS`), streams/subtitles
  60 s (`STREAM_TIMEOUT_MS`).
- Out of scope: upstream HTTP 500 from some Cinejoy streams and addons serving expired signed URLs.

