# StreamioLink Memory

Dated change/release log. Current unresolved issues and limitations live in `known-issues.md`.

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
- Out of scope (recorded in `known-issues.md`): Cinejoy upstream HTTP 500s and addons serving
  expired signed URLs.

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
- Live result: for `tt0111161` only 8/15 candidates played (404/HTML landing pages/dead hosts);
  `tt0903747:1:1` was 10/10. Header/UA handling was confirmed correct. These are addon-side
  link-quality issues, and no liveness filtering/reordering is done by request
  (`known-issues.md`).
- Tested `https://plaviohd.vercel.app/c/<config>/manifest.json` (PlavioHD, community.plaviohd):
  manifest OK (movie/series/tv, catalog+meta+stream, no idPrefixes), but its 4 catalogs are all
  `tv` (sports) -> source-addon only. As a source it is good: `tt0111161` returned 3/3 playable
  and `ffprobe` decoded them (h264 720p + AAC, and h264 1080p + AC3, ~2h22m). `tt0903747:1:1`
  returned a single Vidzee HLS that is actually a **JPEG trickplay playlist** (segments are
  `image/jpeg`, not video) — an addon-side trap recorded in `known-issues.md`.
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
- Addon-side caveats (recorded in `known-issues.md`): Meteor's catch-all manifest hides bad
  configs; a malformed `languageFormat` array yields `401 invalid session`. No P2P opt-out setting
  was added (CloudStream's consent dialog gates each torrent session).

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
- Intentionally unchanged (see `known-issues.md`): no cross-addon dedup, liveness filtering, or P2P
  opt-out; pengu's ~8 s redirect and cnc dead links remain.

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

## 2026-10-17 — subtitle gate released by a non-stream addon (reported concern → real bug)
- Bug (present since the v9 "concurrent subtitles" change, see the 2026-10-16 v9 entry): the
  `firstAddonDone` `CompletableDeferred` was completed in the `finally` of **every** addon launch,
  so the first addon to *settle* — not the first *stream-capable* addon — released it. Addons that
  return early (`getManifest` null, or `!supportsStream`) still tripped it, so the subtitle
  coroutine read `subtitleProbeHash`/`subtitleProbeSize` while both were still null.
- Why it hit the default config, not an edge case: `StremioSettings.streamAddonUrls()` puts the
  **catalog addon first** and `useCatalogAsSource()` defaults `true`. The catalog addon (Cinemeta)
  only declares catalog/meta, early-returns at the `!supportsStream` guard, and does less work
  (`getManifest` with no `getStreams`) — so it is a strong first-settler. Subtitle addons needing
  `videoHash`/`videoSize` silently degraded to title/imdb lookup every session.
- Fix in `StremioProvider.loadLinks`: the gate is now completed **only by an addon that declared
  `stream` and reached its stream query** (`streamAnswered = true` set after `candidatesFor`, so the
  probe hints are populated first), and `finally` completes it only in that case. Added a fallback
  child coroutine that `joinAll`s the addon jobs and releases the gate after everything settles, so
  the subtitle `await()` cannot hang when no addon supports streams (would otherwise deadlock
  `supervisorScope`).
- Gotcha worth remembering: the addon jobs are collected with `addonBases.map { launch { ... } }`,
  **not** `forEach { jobs += launch { ... } }`. The `forEach` form lets the fallback coroutine start
  on another thread and call `joinAll()` on a list still being appended — a race that would release
  the gate early all over again. `.map` fully builds the list before the fallback is launched.
- Residual race (reduced only here, fixed in the next entry): subtitle-capable addons whose manifest
  has not loaded yet when the gate fires were still dropped from `emitSubtitleLinks`. The stream
  addon path now does manifest+streams+`candidatesFor` first, buying time, but there was no explicit
  wait on all addon manifests.
- `architecture.md` (§5) updated. No version bump yet — not published.

## 2026-10-17 — `StremioPayload` hardening (unknown fields + blank id/type)
- Gap (reported concern, verified real but low severity): `StremioPayload` was the only model without
  `@JsonIgnoreProperties(ignoreUnknown = true)`, and `StremioMapping.mapper` is a bare
  `jacksonObjectMapper()` (StremioMapping.kt:13) with `FAIL_ON_UNKNOWN_PROPERTIES` left at its
  default `true`. An unknown field would throw `UnrecognizedPropertyException`, which `decodePayload`
  swallows into `null`, silently breaking `load`/`loadLinks`. It cannot happen in normal operation
  (the payload is produced by `encodePayload` and consumed by `decodePayload` in the same build), but
  it matters for crafted/external `data` and for payloads persisted in CloudStream history/resume and
  later decoded by a build with different fields. Added the annotation for parity with the peers.
- Gap: no blank-field validation. `null` id/type already failed (non-null Kotlin params, no
  defaults), and `null` ids are filtered at `StremioProvider.toSearchResponse`/`toEpisode`, but the
  empty-string case slipped through and produced URLs like `/stream/movie/.json`. `decodePayload` now
  returns `null` unless both `id` and `type` are non-blank (single validation point).
- `StremioPayloadTest` gained `ignoresUnknownPayloadFields` and `rejectsBlankIdOrType`; existing
  `payloadRoundTrip*`/`rejectsNonJsonPayload` unchanged. No architecture/behavior-flow change, so
  `architecture.md` untouched. No version bump yet — not published.

## 2026-10-17 — subtitle fan-out could drop a slow addon's external subtitles
- Reported as "CRITICAL concurrency race"; verified real but a subtitle-completeness bug, not
  critical. `emitSubtitleLinks` picks addons by snapshotting the shared `manifests` map
  (`StremioProvider.emitSubtitleLinks`), which is filled per-addon as each `getManifest` returns.
  The `firstAddonDone` gate could release after the fastest stream addon's manifest+streams, before
  other addons' manifests arrived — so those addons were absent from the snapshot and their external
  `subtitles` resource was never queried. Embedded subs and the gate-tripping addon's own external
  subs were never affected; only *other*, slower subtitle-capable addons were dropped.
- Fix: added a second gate, `manifestDone = addonBases.associateWith { CompletableDeferred() }`.
  Each addon completes its own signal in the `finally` (so early `return@launch` on a null manifest
  or `!supportsStream`, and failures, all release it). The subtitle coroutine now does
  `firstAddonDone.await()` **then** `manifestDone.values.forEach { it.await() }` before calling
  `emitSubtitleLinks`. Awaiting all manifests makes the queried set complete. Manifests are one
  round-trip (20 s cap) vs. the 90 s stream path, so this keeps the original "don't wait for slow
  stream addons" goal; the cost is bounded by the slowest manifest, not the slowest stream query.
- Still no deadlock: `manifestDone` signals complete independent of the consumer; the fallback
  `addonJobs.joinAll()` still releases `firstAddonDone` if no addon supports streams.
- Lesson: when a fan-out consumes a shared map that peers fill concurrently, gate the consumer on
  *that map's* readiness, not on an unrelated "someone finished" signal. The `firstAddonDone` fix
  in the earlier entry narrowed the window but could not close it; this does.
- `architecture.md` (§5) updated. No version bump yet — not published.

## 2026-10-17 — comprehensive codebase audit (bugG.md)
- Performed thorough audit across all plugin code without making any code edits.
- Identified 20 findings (4 High, 9 Medium, 7 Low) covering manifest resource filtering dropping
  addons that omit `types`, DASH/MP4 misclassification in `guessStreamKind`, `stremio://` URL
  corruption in `normalizeAddonUrl`, TrueHD/CAM regex lookbehind flaw in `parseQuality`, subtitle probe
  hint race conditions, episode numbering fallbacks, unbounded `failedUntil` memory leak, and more.
- Detailed report written to `bugG.md`.

## 2026-10-17 — `guessStreamKind` misclassified DASH/direct files under HLS-ish paths
- Reported as "DASH streams and standard MP4 URLs misclassified as HLS". Verified **partly real**
  (one of the two stated causes + its example were wrong): the check is `path.contains("/playlist")`
  with a leading slash, so the audit's own example `.../shows/the-playlist-s01e01.mp4` was already
  `VIDEO` (it contains `-playlist`, not `/playlist`). The real defects were:
  - `hls` was evaluated before `dash`, and `hls` included the weak path hints `/hls` and `/playlist`,
    so an explicit `.mpd` under an `/hls/` path (`.../hls/live/manifest.mpd`) became HLS.
  - A direct file under an `/hls/` or `/playlist/` **directory** (`/hls/movie.mp4`) became HLS,
    because the path hint beat the file extension.
  - `lower.contains(".m3u8")` scans the whole URL, so `.m3u8` in a query turned a `.mp4` into HLS.
- Fix (`StremioMapping.guessStreamKind`): explicit extensions are now authoritative. Order is
  `isMpd && !isM3u8` → DASH, `isM3u8` (path/filename) → HLS, a known video-container extension in
  path/filename (`.mp4/.mkv/.webm/.avi/.mov/.m4v/.flv`, new `videoFileRegex`) → VIDEO, and only then
  the hints: full-URL `.m3u8`, `/hls`, `/playlist`, `mpegurl` → HLS; full-URL `.mpd` → DASH; else
  VIDEO. The full-URL `.m3u8` fallback is kept on purpose (extensionless proxies such as
  CNCVerse where `.m3u8` lives only in the query), and the PenguPlay `file.mkv|^|…` filename still
  resolves to VIDEO.
- Tests: added `prioritizesExplicitExtensionsOverPathHints`; existing `guessesStreamKind*` and
  `guessesHlsBehindProxyOrPlainPaths` unchanged and still pass.
- `architecture.md` (§4) updated. No version bump yet — not published.

## 2026-10-17 — subtitle probe hints: `&&` blocked filling the second hint
- Reported and verified real (low severity). `StremioProvider.candidatesFor` gated the probe-hint
  capture on `subtitleHash.get() == null && subtitleSize.get() == null`. Once either hint was set,
  the whole block was skipped for every later stream, so e.g. `[hash-only, hash+size]` left
  `subtitleSize` null forever. OpenSubtitles' hash lookup needs both, so this forced a title/imdb
  fallback and degraded subtitle accuracy. Only surfaces when an addon emits the two hints on
  different streams (ours emit them together in one `behaviorHints`).
- Fix: `&&` → `||`. The inner `compareAndSet(null, …)` per field was already correct, so this just
  lets the missing field be filled while still stopping the scan once both are captured.
- No regression test: `candidatesFor` is `StremioProvider` (Android/CloudStream) code with no JVM
  test harness. No version bump yet — not published.

## 2026-10-17 — `StremioApi.failedUntil` unbounded growth
- Reported and verified real (low severity). `failedUntil` (unlike the 32-capped manifest cache and
  64-capped stream cache) had no cap and no TTL sweep: an entry was only removed when the *same* URL
  was re-queried after expiry (`isBackingOff`) or succeeded (`clearFailure`). High-cardinality keys
  (per-id meta, search queries, `videoHash`-bearing subtitle URLs) that failed once and were never
  revisited accumulated forever. Inert (an expired unqueried key can't affect results) and tiny per
  entry, so no OOM risk — cache hygiene, not a functional bug.
- Fix: `markFailure` now evicts expired entries, then the soonest-expiring if past
  `FAIL_CACHE_MAX = 256`, before inserting. Mirrors `pruneStreams`/`pruneManifestsLocked`.
- No unit test (StremioApi is Android/CloudStream `app` code). `architecture.md` (Performance &
  resilience) updated. No version bump yet — not published.

## 2026-10-17 — subtitle fan-out emitted per-addon, not batched on `awaitAll()`
- Reported and verified real (low severity). `emitSubtitleLinks` fanned out with `async{}` then
  `.awaitAll()` before the first `emitSubtitles`, so a fast subtitle addon's results were held until
  the slowest returned (up to `STREAM_TIMEOUT_MS` = 90 s). Stream links were already progressive, so
  this was an asymmetry. Narrow in practice: needs ≥2 addons declaring the `subtitles` resource with
  different latency (a single subtitle addon waits on itself anyway), and it only delays subtitle
  availability, not playback/links.
- Fix: replaced the `awaitAll` batch with `subtitleAddons.forEach { launch { … emitSubtitles(…) } }`
  inside `coroutineScope`, mirroring the stream fan-out. Safe because `seen` is
  `ConcurrentHashMap.newKeySet()` and `subtitleCallback` is already invoked from several stream
  coroutines (embedded subs); only addon-subtitle ordering becomes nondeterministic.
- No unit test (StremioProvider/Android code). `architecture.md` (§5) updated. No version bump yet —
  not published.

## 2026-10-17 — `hasQuickSearch` removed (was advertising an unimplemented capability)
- Reported as "quickSearch per keystroke → request storm → 429". Verified against the installed
  stable CloudStream **v4.8.0** source: the premise is wrong. `hasQuickSearch` is a capability flag
  (`MainAPI.kt:562`) consulted only by `QuickSearchFragment` in single-provider mode
  (`isSingleProviderQuickSearch`, `QuickSearchFragment.kt:143-145`); only there does
  `onQueryTextChange` call `search(..., isQuickSearch=true)` (`:260-264`). The main search bar does
  not (`SearchFragment.kt:442` is commented out; typing there only does a debounced
  `fetchSuggestions`). And `MainAPI.quickSearch` defaults to `throw NotImplementedError()`
  (`:654-656`), **not** `search(query)`, so each keystroke failed before any HTTP — no storm, no 429.
- Real, narrower defect: `hasQuickSearch = true` without overriding `quickSearch` made the
  home-icon quick-search screen show nothing live (every keystroke Loading→Failure via
  `safeApiCall` catching the throw). Removed the override (defaults to `false`); that screen now
  searches on submit through the normal `search()` path. Chose this over implementing `quickSearch`
  because live-as-you-type isn't needed and a `delay` debounce would be swallowed as a logged
  `CancellationException` by CloudStream's `safeApiCall`.
- `architecture.md` (§Data Flow step 2) updated. No version bump yet — not published.

## 2026-10-18 — Per-link language tags (CloudStream has no link language field)
- Question: "why doesn't CloudStream show language info for links?" Verified against the installed
  stable source: `ExtractorLink` (`library/.../utils/ExtractorApi.kt`) exposes only `source` + `name`
  in the Play dialog (plus `audioTracks: List<AudioFile>`, which is for merging separate audio
  streams, not display). There is **no** per-link language field, so the only way to surface it is to
  bake it into the link `name`.
- The data is in the addons but not where the plugin looked. `streamDisplayName` prefers
  `name`/`title` and only falls back to `description`, so language-only descriptions were dropped:
  HdHub (`description` = release name `...Hindi.English...` + bare `Hindi`), PenguPlay
  (`🎧 Audio: English, Hindi` + dotted filename), Meteor (`Rus.Eng` filename, `🔊 eac3`), while
  PlavioHD/CNCVerse put it in `title`. Confirmed by live `curl` against the `tools/test-addons.json`
  sources on 2026-10-18.
- Fix: new pure `StremioMapping.streamLanguages` / `languageTag` text-mine `name`/`title`/
  `description`/`behaviorHints.filename` for a deliberately tiny alias set — **English, Hindi,
  Multi** (`eng`/`hin`/`multi`, plus "dual audio" → `Multi`) — so detection stays cheap.
  Order-preserving, case-insensitive dedupe, capped at 3. `StremioProvider.candidatesFor` prefixes
  the label with `[Hindi, English]`. Streams with no detectable language are unchanged. Unit tests
  added in `StremioStreamTest` for each addon's field layout. `architecture.md` (§Data Flow step 4)
  updated.
- No version bump yet — not published.

## 2026-10-18 — PlavioHD subtitle 404: fall back to the extra-less URL — version 15
- Report: logs showed PlavioHD returning an HTML 404 for `subtitles/series/<id>/videoSize=….json`.
- Root cause is addon-side, not a `buildSubtitlesUrl` shape bug. Live-verified against the
  configured PlavioHD (`tools/test-addons.json`): `/subtitles/{type}/{id}.json` → `200
  application/json`, but **any** path extra (`videoHash`, `videoSize`, `filename`, `foo=bar`,
  encoded or not) → `404 text/html`; `?videoSize=…` query → `200`. PlavioHD advertises the
  `subtitles` resource yet only implements the extra-less route. Our shape matches Stremio's own
  client exactly (`stremio-core` `http_transport.rs` + `query_params_encode.rs`: extras are one
  path segment joined with raw `&`), and the SDK-built OpenSubtitles v3 accepts the same extra
  form — so gating on the manifest's declared `extra` was rejected (OpenSubtitles declares only the
  short `["subtitles"]` resource, yet needs `videoHash`).
- Why it triggers: `videoHash`/`videoSize` are captured from *any* source addon's streams
  (`candidatesFor`) and applied to *every* subtitles addon, so a hint supplied by e.g. hdhub leaks
  onto PlavioHD even though PlavioHD's own streams carry none. NiceHttp does not throw on 404, so
  the HTML body reached Jackson, threw, caused a logged failure + 30 s backoff, and dropped
  PlavioHD's subtitles for the session.
- Fix: `StremioMapping.buildSubtitlesUrlCandidates` returns `[with-extra, plain]` (or just
  `[plain]` with no hints); `StremioApi.getSubtitles` walks it, falling back to the plain URL on a
  non-JSON body. A null body (timeout/transport failure) does **not** retry, to avoid a second full
   90 s wait. Unit-tested in `StremioManifestTest`; `architecture.md` §5 updated. Bumped 14 → 15.

## 2026-10-19 — shorten stream cache so "reload links" refetches — version 16
- Report: same episode (The Flash S1E4) gave cnc 7 links on first play, 24 after "reload
  links", vs 20 in Nuvio; pengu/playvio counts also drifted. Live `curl` of cnc for
  `tt3107288:1:4` returned 9 streams (size 9129) cold, then 20 (size 18651) minutes later —
  the aggregator itself returns partial results until its server-side cache warms. So most of
  the app-to-app/phase gap is addon-side, not a parse bug (the plugin forwards every valid
  `url`/`infoHash`; only the `"url": false` entry and whitespace URLs are dropped).
- Real plugin-side aggravator: the 5-minute `streamCache` froze cnc's cold/partial result and
  re-served it, so reloading *because* the first set looked short could not improve it
  (bugG #11). Reduced `StremioApi.STREAM_CACHE_TTL_MS` 5 min → **60 s**: quick re-open stays
  cheap, a deliberate reload refreshes. `STREAM_TIMEOUT_MS` stays 90 s (real aggregators answer
  in 6–30 s) and `loadLinksTimeoutMs` stays 180 s so the stream phase (≤90 s) plus the
  post-stream subtitle phase (≤90 s) both fit.
- Not changed here: cnc's `subtitles` resource 404s (empty body) on any path extra and works on
  the plain URL; v15's `[with-extra, plain]` fallback covers it. Whether the reported missing
  cnc Hindi sub is v15 or a separate issue needs a device logcat capture. Bumped 15 → 16.

## 2026-10-23 — AIOMetadata: duplicate row titles hid movies; anime/genre catalogs dropped — version 17
- Report: AIOMetadata catalog (`aiometadata.elfhosted.com/stremio/<config>/manifest.json`) showed
  only series rows and "not all categories".
- Root cause 1 (movies): the manifest reuses the same `name` for the movie and series variant of
  `tmdb.top`, `tmdb.trending`, `lumiere.*` etc. CloudStream stores home rows in a map keyed by
  title (`HomeViewModel.expandable[list.name]`), so each later series row overwrote the movie row.
- Root cause 2 (missing rows): `SUPPORTED_MEDIA_TYPES` was `{movie, series}` (dropped all 18 MAL
  anime catalogs and anime search), and `catalogCanLoadHome` dropped every catalog with a required
  extra (14 here: TMDB By Year/Language, TVDB, MAL Genres/Schedule/Seasons/Studios), even though
  the manifest supplies a `default`/`options` value.
- Fix: `catalogTitles` appends `(Movies)`/`(Series)` only for duplicate names (tabs and rows share
  the same titles); `catalogPresetExtra` resolves required extras from `default` or the first
  option and is sent as `genre=…`, combined with `skip` (and `search`) by `catalogExtra`; anime
  catalog types are loadable and `isSeriesType`/`meta.type` drive the load response so anime
  series keep episodes. `showInHome:false` catalogs are surfaced deliberately (no Discover/genre
  picker in CloudStream), while un-satisfiable extras (`search`, `similar`, `calendarVideosIds`)
  stay excluded. Also fixed the `catalogTabs`/`catalogTabsBase` publish order (tabs first).
- Unit tests: preset extras, preset+skip/search joining, title disambiguation, anime types,
  series-type detection. Bumped 16 → 17.




## 2026-10-24 — Yukistreams (catalog + source) compat check; drop sentinel "no streams" links — version 18
- Tested `https://stremio.yukistreams.xyz/p.Yh4DGBks6bsMqxDc/manifest.json` (Stremio addon,
  catalog+meta+stream+subtitles, types movie/series/anime).
- Works as-is: manifest parses (resources mix the `"catalog"` string with `meta`/`stream`/
  `subtitles` objects); 37 of 46 catalogs load with items, 6 are `search`-required (used by
  plugin search only), 3 empty and 1 transient 502; anime catalogs are loadable and their items
  report `type: series`; `kitsu:`/`mkvdrama-`/`kisskh-`/`onetouchtv-`/`shortdrama:`/`tt` IDs
  all resolve through `/meta` + `/stream`; real `.m3u8` rows (KissKH, OneTouchTV; e.g.
  `tt0848228`, `tt22084616`, `kisskh-…:1:1`) return 200 with no UA/Referer requirement and the
  plugin maps them to `M3U8` with quality parsed from the stream name.
- Bug found: when no source exists, Yuki returns one fake stream named
  `[YS INFO] No streams found` with `url` pointing at `/assets/stream-errors/no-stream-found.mp4`
  (or `locked.mp4`) and `notWebReady: true`. `playbackUrlFor` accepted it, so users saw a bogus
  playable link that plays the error clip. Most mkvdrama/onetouchtv/shortdrama/anime items in the
  sample were this sentinel.
- Fix: `StremioMapping.isSentinelStream` (URL path `/stream-errors/`, or `[YS INFO]` +
  "No streams found" label); `playbackUrlFor` returns null for it. Unit test covers both Yuki
  sentinels plus a real HLS row. Bumped 17 → 18.
- Addon-side, not fixed (recorded in `known-issues.md`): `ys-v2-kisskh-*` ignores `skip`;
  `ys-v3-anime-seasonal` 502s for some offsets and repeats page 1 for others; its `subtitles`
  resource returns `[]`.

## 2026-10-24 — Pluto TV (live channels) support: `tv` catalogs as movie-like items — version 19
- Tested `https://dev.nebulawp.org/stremio/pluto-tv-addon/manifest.json` (live TV; `types:["tv"]`,
  resources `["catalog","meta","stream"]`, no idPrefixes, no subtitles).
- Before this change it was completely unusable: `tv` was not in `SUPPORTED_MEDIA_TYPES`, so both
  catalogs were rejected from home/search; `resourceTypesFor` falls back to `["tv"]`, so a
  movie/series payload never matched the stream resource; `/meta|stream/movie/tt…` returns an HTML
  page (HTTP 200, not JSON). Even bypassing the filters would have been broken because
  `isSeriesType("tv")` is true and Pluto metas have no `videos` (empty series, nothing to play).
- Addon shape: `tv/pluto` (1561 channels, supports `search=`, no `skip`) and `tv/pluto_popular`
  (59 channels, no search/skip); meta has name/poster/background/description and no videos; each
  channel returns exactly one `url` = Pluto `master.m3u8` (JWT-signed). Verified 5 channels: HTTP
  200 `application/x-mpegURL`, works with both `okhttp/4.12.0` and browser UA, no Referer needed.
  One transient non-JSON response was seen, succeeding on retry.
- Fix: added `tv` to `SUPPORTED_MEDIA_TYPES`; `LIVE_MEDIA_TYPES`/`isLiveType` + `usesEpisodes`
  (series and not live) drive the load/search response shape, so a channel is a `Movie` with one
  stream. `loadLinks` needs no change: payload type `tv` matches the manifest, and the HLS URL is
  classified `M3U8`. Unit tests cover support/search/live detection. Bumped 18 → 19.
- Caveats recorded in `known-issues.md`: the `pluto` row cannot paginate; `tv` also surfaces other
  catalog addons' `tv` rows; playback is live-only (no EPG/seek); Pluto URLs are time/geo-bound and
  regenerated per stream request.

## 2026-10-24 — multiple catalog addons + Plavio (VOD source, live TV catalog) — version 20
- Requested setup: Cinemeta/TMDB as primary home addon and Plavio as a second catalog addon for its
  live sports rows. The old single-catalog model could not do this: the catalog base was a global
  setting, so items from a second addon would fetch meta/streams from the primary addon.
- Plavio (`https://plaviohd.vercel.app/c/<config>/manifest.json`; test config also at
  `hd.plavio.site/c/<config>`): types `movie/series/tv`, resources `stream` + `meta` +
  `subtitles` (no subtitles for `tv`), stream/meta idPrefixes `tt` + `plaviohdtv-`. Catalogs are
  **live TV only** (type `tv`, 15 sport categories); no movie/series catalog exists.
- Live-tested as VOD source: `tt0111161` → 33 streams, `tt0903747:1:1` → 20 streams (MovieBox
  `video/mp4` and Google Drive `video/mkv` both HTTP 200 after the injected okhttp UA).
- Live-tested as catalog addon: 12 non-empty `tv` rows (baseball/AFL/billiards empty), meta has
  poster/logo/description and no videos; HLS verified end-to-end (master 200 +
  `video/MP2T` segment 200). Non-started events and "Tip: Use an external player" channels return
  `https://sports.highfly.to/health` (JSON, 130 bytes) as the stream URL → now a sentinel.
- Plavio `/meta` for VOD ids is a stub (`{"id":"tt0903747","type":"series","name":"tt0903747"}`,
  no videos), so `fetchMeta` must not stop at the item's own addon when the meta is a stub; it
  tries item base → primary catalog addon → metadata fallback and skips blank/stub metas.
- Implementation: settings store an ordered catalog list (`streamiolink_catalog_urls`) with a
  per-entry source flag (`streamiolink_catalog_source_urls`), migrating the old single URL/toggle;
  each item payload and each catalog ref now carries `base`; home rows are concatenated
  primary-first with global title dedupe (`disambiguateTitles`); search stays on the primary addon.
  Bumped 19 → 20.

## 2026-10-24 — per-addon edit + enable/disable in settings — version 21
- Settings rows previously only had a "Remove" button, and the source list had no toggle. Added a
  per-entry `enabled` flag for catalogs and sources, persisted as URL lists in the new
  `streamiolink_disabled_catalog_urls` / `streamiolink_disabled_source_urls` keys. Missing keys on
  upgrade mean "everything enabled", so existing configs migrate with no action.
- Semantics: a disabled catalog contributes no home rows, is not the primary search/metadata addon
  and is not queried for streams even if `useAsSource` is on. A disabled source is skipped by the
  stream/subtitle fan-out. Entries are kept in the list (order and `useAsSource` preserved) so they
  can be re-enabled. Disabling the primary catalog promotes the next enabled one; the UI disables
  the toggle when only one enabled catalog remains, and Save refuses a zero-enabled state.
- UI: every catalog/source row is now one line — URL, enable checkbox, `✏️` edit, `🗑️` delete.
  Edit is URL-only and replaces the entry in place (same position, flags); duplicates are rejected.
  `StremioSettings.save` writes legacy keys from the first enabled catalog so downgrading still works.
  Bumped 20 → 21.

## 2026-10-24 — Expanded per-link language detection
- Grew `StremioMapping.languageAliases` from English/Hindi/Multi to ~40 languages common in
  movies/series/anime: the Indian set (Tamil, Telugu, Malayalam, Kannada, Bengali, Marathi,
  Punjabi, Gujarati, Urdu), Asian (Japanese, Korean, Chinese, Thai, Indonesian, Vietnamese,
  Filipino/Tagalog, Malay), European (Spanish, French, German, Italian, Portuguese, Russian,
  Ukrainian, Polish, Dutch, Swedish, Danish, Norwegian, Finnish, Greek, Czech, Hungarian,
  Romanian) and Arabic/Turkish/Hebrew/Persian.
- Uses full English names plus unambiguous scene/ISO codes (`tam`, `tel`, `mal`, `kan`, `guj`,
  `urd`, `rus`, `ukr`, `jpn`, `kor`, `zho`, `tur`, `tha`, `vie`, `fil`, `msa`, `fra`/`fre`,
  `deu`/`ger`, `esp`/`spa`, `ita`, `por`, `nld`/`dut`, `swe`, `ces`/`cze`, `heb`, `fas`, `arb`)
  and native-Latin spellings (`español`, `français`, `português`, `türkçe`, `čeština`, `română`).
  Ambiguous codes that double as words/names (`ben`, `mar`, `per`, `may`, `dan`, `fin`, `nor`,
  `pol`, `rom`, `ron`, `ara`, `pan`, `hun`, `ind`, `gre`, `sin`) are deliberately omitted.
- Tokenizer changed from `[A-Za-z]+` to `\p{IsLatin}+` so accented Latin names match, while CJK
  and other native scripts stay excluded (anime titles in kanji/kana must not imply Japanese
  audio). Order-preserving dedupe and the 3-tag cap are unchanged.
- Tests: updated Meteor (`Rus.Eng` now `[Russian, English]`) and the cap case; added coverage for
  Indian, Japanese/Korean, scene codes, accented names, Arabic/Turkish/Hebrew, Filipino/Malay/
  Indonesian, dedupe, and false-positive guards (`Ben-Hur`, `Mar 2020`, `per episode`).
  `architecture.md` updated. publihed

## 2026-10-24 — Restore to f878d2a baseline and bump version 26
- Rolled back all changes after commit `f878d2a` (expand language detection) to restore baseline broad language detection behavior.
- Bumped plugin version to 26 (`StreamioLink/build.gradle.kts`) so CloudStream replaces installed copies.

## 2026-10-25 — Audio-first language parsing; subtitle lists no longer tagged as audio
- Live CNC payload for Breaking Bad S2E1 puts `🎧 Audio: …` and `📝 Subtitles: …` on separate
  lines. The previous whole-text scan tagged subtitle languages as audio, and subtitle-only streams
  produced fake audio tags.
- `StremioMapping.streamLanguages` now parses explicit audio lines first (`Audio:` / `Language:` /
  `🎧` / `🔊`, requiring a separator so "English audio" is not mistaken for a label), cuts at `|`
  or a subtitle marker, and returns those languages when found. Subtitles never leak into the tag.
- Only when no audio line exists does it fall back to the release name/filename, skipping
  subtitle-only lines (`Subtitles:`, `Subs:`, `Hardsub`, `Legendado`, `VOSTFR`, …). `ESub`-suffixed
  release names are kept, so HdHub/Meteor/Movies4u fallback tags still work.
- Added locale/dub forms: `ES-LA`/`ESLA`/`eslatam` → Spanish, `PT-BR`/`PTBR` → Portuguese,
  `Dublado` → Portuguese, `Castellano` → Spanish; `dual|multi audio` → `Multi`.
- Tests use real CNC/Pengu lines: audio wins over subtitles, subtitle-only → null, Arabic Hardsub →
  null, ES-LA/PT-BR/dub forms, Movies4u `ESub` fallback. 62 JVM tests pass via `streamiolink-tests`.
- `known-issues.md` now records that CloudStream's preferred audio language cannot select among
  separate Stremio links. Bumped 26 → 27.

## 2026-10-25 — IMDb ratings on cards and detail pages — version 28
- AIOMetadata and Nuvio catalog `metas` (and `/meta`) include `imdbRating` (e.g. `"9.3"`), but the
  plugin parsed it and never mapped it, so CloudStream showed no scores.
- `StremioMapping.ratingFromImdb` validates 0..10 (null for absent/invalid/0) and
  `StremioProvider.imdbScore` wraps it in `Score.from10`; applied to both search/home
  `SearchResponse`s and movie/series `LoadResponse`s. Card badges additionally require
  CloudStream's "Show ratings" setting.
- Verified the `Score` API against the cached CloudStream pre-release stub
  (`/root/iptv/.toolchain/cloudstream-stub/classes.jar`, source documented in its README); AGENTS
  now points at it. 63 JVM tests pass via `streamiolink-tests`. Bumped 27 → 28.

## 2026-10-25 — Richer addon metadata mapping — version 29
- AIOMetadata/Nuvio send `logo`, `runtime`, `status`, `ageRating`, cast, trailers and episode
  `rating`/`runtime`; only poster/background/plot/year/tags/`imdbRating` were mapped before.
- Added DTOs for `app_extras` (`cast` objects, `certification`, `ratings`), `trailers`,
  `trailerStreams`, and video `rating`/`runtime`/`available`.
- `StremioMapping.ratingFrom10` now covers `imdbRating`, episode ratings and `app_extras.ratings`;
  added `runtimeMinutes` (`2h22min`/`142m`/`57min`), `trailerUrl` and `showStateFrom`.
- Load responses map `logoUrl`, `duration`, `contentRating`, `actors` (app_extras first),
  `trailers` (trailerStreams then trailers), and series `showStatus`; episodes map `score` and
  `runTime` seconds. `videos[].available` is parsed but not filtered.
- 68 JVM tests pass via `streamiolink-tests`. CI run `40160565298` passed and published version 29
  (`builds/plugins.json`).

## 2026-10-25 — Episode runtime units — version 30
- Episode `runTime` was stored in seconds (`minutes * 60`), but CloudStream's episode card
  multiplies it by 60 again before formatting (`EpisodeAdapter.kt:329`), so a 44-minute episode
  showed as `1d 20h 0m`.
- `StremioProvider.toEpisode` now passes `runtimeMinutes(video.runtime)` directly (minutes), matching
  CloudStream's de-facto contract and its InternetArchive provider. Episode ratings unchanged.
- 68 JVM tests pass via `streamiolink-tests`. CI run `40163571764` passed and published version 30.
