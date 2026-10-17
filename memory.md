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

## 2026-10-16 — slow addons actually fixed (NiceHttp/OkHttp timeout trap) — version 5
- Symptom: PenguPlay (and other slow aggregator addons) still "skipped" while hdhub plays, even
  after the v4 `loadLinksTimeoutMs`/incremental-emit work. Not a playback-lifecycle issue: the
  player's `loadLinks` job is not cancelled on playback (`PlayerGeneratorViewModel.loadLinks` runs
  in `viewModelScope`; `GeneratorPlayer.startPlayer` only loads the chosen link).
- Root cause: CloudStream's `app` is a NiceHttp `Requests` with `defaultTimeOut = 0`. In NiceHttp,
  `timeout = 0` skips setting connect/read/write timeouts, so OkHttp's default **10 s read timeout**
  applies (`RequestsHelper.buildDefaultClient` sets no timeouts). Pengu TTFB measured 5.7-28.8 s,
  so requests over ~10 s threw `SocketTimeoutException`, which `StremioApi.getStreams` swallowed
  (fail-soft) -> empty list -> addon silently skipped. A longer `withTimeoutOrNull` cannot help
  because the OkHttp timeout fires first.
- Fix: `StremioApi.request` now passes an explicit timeout: `app.get(url, timeout = ms / 1000)`
  (NiceHttp takes seconds; must be > 0 or it is ignored). `STREAM_TIMEOUT_MS` raised 60 s -> 90 s.
- Also adopted CSX/CineStream's fan-out hardening (`CineStreamUtils.runLimitedAsync`): `loadLinks`
  now uses `supervisorScope` + per-addon `try/catch` (rethrows `CancellationException`) so one
  addon's failure cannot cancel the others. Reference technique: `app.get(url, timeout = 100000L)`.
- `StremioApi` timeout split now: manifest/catalog/meta 20 s, streams/subtitles 90 s.

## 2026-10-16 — addon playtest tool + CNCVerse (source-addon) findings
- Added `tools/playtest.py`: dependency-free CLI (stdlib only) that mirrors `StremioMapping`
  (`normalizeAddonUrl`, `requestHeadersFor`, `extractReferer`, `guessStreamKind`, `parseQuality`,
  `streamDisplayName`) to fetch a manifest, resolve a movie/series/catalog item, build stream
  candidates and probe each URL (ranged GET, redirects) as PLAYABLE / NOT-MEDIA / DEAD.
  Modes: `--manifest`, `--id <id> --type`, `--search`, `--catalog type/id --play`, plus
  `--deep` (fetch first HLS segment), `--json`, `--quality`, `--insecure`. Exits non-zero when
  nothing plays, so it is CI-usable. It is a diagnostic mirror, not part of the unit suite
  (`StremioMapping` tests stay network-free).
- Tested `https://cncverse.dpdns.org/u/p_l0uq0xtenbo7` (CNCVerse Bridge): manifest parses, but all
  50 catalogs are type `other`/`tv`, so `catalogCanLoadHome`/`catalogSupportsSearch` reject them
  (only `movie`/`series` accepted) -> useless as a *catalog* addon, fine as a *source* addon for
  `tt` movie/series ids.
- Playback failures are addon-side link quality, not a plugin bug: for `tt0111161` only 8/15
  candidates played (some 404, some `text/html` landing pages from Moviezwap, some dead hosts);
  `tt0903747:1:1` was 10/10 playable. The plugin emits every candidate without a liveness check
  (`resolveLinkType` probes only to classify kind), so users can tap a dead link first. The
  plugin's header/UA handling was confirmed correct (206 with the addon's `Referer` + okhttp UA).
- Deliberately not changed: no liveness filtering/reordering in `loadLinks` (skipped by request).
- Tested `https://plaviohd.vercel.app/c/<config>/manifest.json` (PlavioHD, community.plaviohd):
  manifest OK (movie/series/tv, catalog+meta+stream, no idPrefixes), but its 4 catalogs are all
  `tv` (sports) -> source-addon only. As a source it is good: `tt0111161` returned 3/3 playable
  and `ffprobe` decoded them (h264 720p + AAC, and h264 1080p + AC3, ~2h22m). `tt0903747:1:1`
  returned a single Vidzee HLS that is actually a **JPEG trickplay playlist** (segments are
  `image/jpeg`, `ffprobe` = "Invalid data found") — a real addon-side trap.
- Tool follow-ups from this test: `--json` now emits only JSON (human summary suppressed); `--deep`
  fetches the first `#EXTINF` segment and marks an HLS playlist whose segments are images as
  NOT-MEDIA ("trickplay"), instead of trusting the `application/vnd.apple.mpegurl` content-type.

## 2026-10-16 — P2P/torrent addons now supported (Meteor test) — version 6
- Question: "Nuvio plays from a torrent addon without debrid; can StreamioLink?" Answer: yes.
- Tested Meteor (`community.meteor`, `https://meteorfortheweebs.midnightignite.me`, source-only:
  resources=[stream], types movie/series/anime, idPrefixes tt/kitsu/anilist/mal/anidb, no catalogs).
  `tt0111161` returned 82 streams, `tt0903747:1:1` 75 — **all `infoHash`+`sources`, 0 with `url`**.
  No `subtitles` resource (404), so the plugin correctly contributes no subtitles.
- Why Nuvio plays them: it bundles a native torrent engine (libtorrent4j in NuvioTV; the newer
  `NuvioMedia/nuvio-engine` is C++20+libtorrent). It selects a file, serves it over a local HTTP
  range server and feeds ExoPlayer/mpv. Stremio does the same.
- Why CloudStream can too: the host app already ships `com.github.recloudstream:torrentserver`
  (Go) and its player handles `ExtractorLinkType.MAGNET`/`TORRENT` (`CS3IPlayer.loadOnlinePlayer`
  -> `Torrent.transformLink`, gated by a one-time consent dialog and the "Torrent" preferred-media
  setting). `RepoLinkGenerator`/`ExtractorLinkGenerator` forward any link whose type is in
  `LOADTYPE_INAPP` (includes `MAGNET`). Plugins do NOT need a native engine.
- Fix: `StremioMapping.torrentUrlFor(stream)` builds `magnet:?xt=urn:btih:<infoHash>` +
  `dn=<filename>` + `tr=<tracker>` (from `sources`, ignoring `dht:`) + `index=<fileIdx>`;
  `isTorrentUrl` detects it. `StremioProvider.candidatesFor` falls back from `playbackUrlFor` to
  the magnet and flags the candidate; `resolveLinkType` returns `ExtractorLinkType.MAGNET` for it
  (never probes). Added `fileIdx` to `StremioStream`. Unit tests cover magnet building.
- Addon-side quirk found: Meteor's `/{config}/stream/...` returned `401 invalid session` for the
  user's blob specifically because `languageFormat` was an array `["flags"]`; a string works. Its
  `/{anything}/manifest.json` is a catch-all stub, so a bad install URL still "loads a manifest"
  then silently yields no streams. Not fixable plugin-side.
- Not implemented (possible follow-up): a setting to opt out of P2P/torrent links. CloudStream's
  own consent dialog already gates each torrent session.

## 2026-10-16 — Episode names showed as ids with Nuvio catalog — version 7
- Symptom: every episode listed as its video id (`tt0903747:1:1`) instead of the episode title.
- Cause: Nuvio (`https://catalog.nuvio.tv`, `com.nuvio.tmdb.catalogs`) puts the episode label in
  `video.title` ("Pilot"), but Stremio's protocol (and our `StremioVideo`) only read `video.name`,
  so `toEpisode` fell back to `videoId`.
- Fix: added `title` to `StremioVideo`; new `StremioMapping.episodeName(video)` prefers
  `name` then `title`; `toEpisode` uses it before the id fallback. Unit test covers the fallback.

## 2026-10-16 — remove per-link HEAD probe + short stream cache (client strain) — version 8
- User report: only hdhub (direct signed CDN) is "smooth"; pengu is slow, cnc unreliable, and
  meteor fails. Live-tested all four (catalog nuvio, movie tt0111161) — URLs saved in
  `tools/test-addons.json` for future playtests. Findings (sandbox had a constant ~5 s DNS
  penalty, so compare deltas):
  - hdhub: direct `r2.cloudflarestorage.com` + pixeldrain links, fast. 2 links are `hubcloud.ist`
    HTML landing pages.
  - pengu: every link is `pengu.uk/direct/...` which 307-redirects (~8 s server-side) to the
    **same** `r2.../hub/<key>` objects hdhub already returns (pengu is a slow proxy over the same
    CDN). 49 links/movie.
  - cnc (CNCVerse Bridge): ~1/3 links dead (404, connection refused, `text/html`), one URL with
    literal spaces. Servers themselves are fast.
  - meteor: `/{config}/stream/...` returns `401 invalid session` after ~6 s with the user's blob
    because `languageFormat` is an array `["flags"]`; a string works (see the v6 note).
- Root cause of the plugin's own slowness: `resolveLinkType` did a serial `app.head()` per
  candidate before emitting it. Tested HEAD responses: hdhub R2 **403**, cnc **404**, pengu
  **403 after 12 s** — so the probe almost always fell through to VIDEO anyway, while adding up to
  `PROBE_TIMEOUT_MS` (3.5 s) × candidates, serialized per addon. HLS/DASH was already detected
  from the URL/filename in every real case.
- Fix: `StremioProvider.linkTypeFor` now classifies purely from `guessStreamKind` (no network, no
  suspend, no `linkTypeCache`/semaphore/budget). `StremioMapping.guessStreamKind` also scans the
  full URL (catches `.m3u8` hidden in a proxy query) and `/hls`, `/playlist`, `mpegurl` hints.
- Added a 5-minute in-memory stream-JSON cache in `StremioApi` (key `base|type|id`, only non-empty
  results, cap 64) so replaying/re-opening an episode does not refetch slow addons.
- Deliberately NOT changed (user's call — keep the client light, let servers dedupe/filter): no
  cross-addon dedup, no liveness filtering, no P2P opt-out. Addon-side limits remain: pengu's ~8 s
  redirect is paid at playback, cnc dead links still appear, meteor's broken config is user-side.

## 2026-10-16 — efficiency/error-handling pass (concurrency, back-off, cancellation) — version 9
- Question: "does it lazy-load catalog items?" Answer: **no, and it can't**. CloudStream's
  `HomeViewModel.load()` calls `getMainPage(1, null)` **once** and renders every returned row; row
  expansion only paginates an existing row (`expandAndReturn` → `getMainPage(nextPage, rowIndex)`).
  Verified against upstream `HomeViewModel.kt` / `APIRepository.kt`. So all catalogs must be fetched
  for the initial home response; lazy rows are impossible through the provider API. Instead the
  eager fan-out was made efficient.
- **Bounded catalog fan-out**: `StremioProvider` uses `Semaphore(MAX_CONCURRENT_CATALOGS = 6)` in
  `allCatalogsPage` and `searchAddon`. Nuvio advertises 27 loadable catalogs, so the old code opened
  up to 27 sockets at once. Added `getMainPageTimeoutMs = 90 s` / `searchTimeoutMs = 60 s`.
- **Concurrent subtitles**: previously `emitSubtitleLinks` ran strictly after `supervisorScope`
  (i.e. after the slowest stream addon, up to 90 s). Now a child coroutine waits on
  `firstAddonDone: CompletableDeferred<Unit>` (completed in each stream addon's `finally`) and emits
  subtitles as soon as the fastest addon answers, with whatever `videoHash`/`videoSize` hints exist
  then. Trade-off: hints (and possibly a very slow addon's subtitle manifest) may be missing on the
  first pass; acceptable vs. subtitles being delayed for the whole session.
- **Cancellation bug fixed**: every `StremioApi` `catch (t: Throwable)` now rethrows
  `CancellationException`; previously a cancelled/timed-out coroutine was swallowed as "empty".
  Also added the missing rethrow in `StremioProvider` catalog catch blocks.
- **Failure back-off**: `StremioApi` remembers failed URLs for 30 s (`failedUntil`), so a down addon
  is not re-hit per screen; cleared on success. Only real failures (null/timeout/exception) mark a
  failure — a valid empty catalog/stream list does not.
- **Cache hygiene**: manifest cache now prunes expired + caps at 32 entries (was unbounded, TTL only
  checked on read); stream cache evicts expired then oldest-by-`fetchedAt` instead of `clear()`.
- **Dead-link guard**: `StremioMapping.isPlayableStreamUrl` (http(s), no whitespace) is used by
  `playbackUrlFor`, dropping CNCVerse's literal-space URLs before they reach the player. Unit-tested.
- README gained a "Tested addons" section (name + homepage + notes from `tools/test-addons.json`).

## 2026-10-17 — raise total link cap to 500 — version 10
- `StremioProvider.MAX_LINKS` raised 120 → **500** (single ceiling shared across all source addons,
  via the existing `AtomicInteger`). Users with many source addons (up to ~10) now receive the links
  each addon's server returns instead of sharing a 120 budget.
- No per-addon cap added (per-addon counts are the addon servers' decision) and no extra network:
  addons are already fetched in full; this only raises the forwarding ceiling. Follows the
  2026-10-16 decision, deliberately left out of the v9 efficiency pass.

## 2026-10-17 — embedded stream subtitles + `lang_code`
- `StremioStream.subtitles` (a valid Stremio protocol field) was parsed away. Added it to
  `StremioModels` and emit the entries via `StremioMapping.embeddedSubtitles` (flattened, deduped by
  URL, no URL → skipped) as each addon's streams arrive in `StremioProvider.loadLinks`. Emitting
  per-addon avoids the `firstAddonDone` snapshot race, so a slow addon's embedded subs are not lost.
- `emitSubtitleLinks` no longer early-returns when no addon declares the `subtitles` resource —
  embedded subs must be emitted even with stream-only addons. Both paths share one
  `ConcurrentHashMap.newKeySet` URL-dedup set, so an embedded sub and an addon sub with the same URL
  collapse to one.
- `StremioSubtitle.lang_code` added; `StremioMapping.subtitleLanguage` prefers `lang`, falls back to
  `lang_code`, then `"Unknown"`. Unit tests cover parsing, precedence, and flatten/dedup.
- TODO A2 (`behaviorHints.headers` top-level fallback) deliberately deferred.
- Bumped `version` 10 → **11** so CloudStream replaces an installed v10 copy.
- Live-verified (tt0111161): pengu `31` streams carry `36` embedded sub entries (`12` unique URLs,
  a subset of its `35`-URL `subtitles` resource → merged `35`); cnc `40` streams / `174` entries
  (`51` unique). hdhub/meteor have none; playvio returned `0` streams for this id. No live addon
  used `lang_code` (all use `lang`), so A3 stays unit-test-only.

## 2026-10-17 — `behaviorHints.headers` top-level fallback — version 12
- Some addons emit request headers at the top level of `behaviorHints` instead of under
  `proxyHeaders.request`. Added `StremioBehaviorHints.headers` and made
  `StremioMapping.requestHeadersFor` read `proxyHeaders.request ?: headers`, so proxyHeaders always
  wins and the top-level map is only used when proxyHeaders.request is absent/empty. The default
  non-Mozilla UA is still injected when the chosen map has no `User-Agent`.
- Unit tests cover: JSON parsing of a top-level `headers` stream, fallback with referer extraction,
  default UA injection for a headers-only map, and proxyHeaders precedence over top-level headers.
- No new network paths; change is confined to `StremioModels`/`StremioMapping`.
- Bumped `version` 11 → **12** so CloudStream replaces an installed v11 copy.
- TODO A2 marked done. Not live-verified (no known public addon in `tools/test-addons.json` uses the
  top-level form), so this stays unit-test-only for now.




