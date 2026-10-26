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
- Ideas for future upgrades live in TODO.md (see "Presets"); no version assigned, may never ship.

## Earlier milestones — versions 4–26 (2026-10-16 – 2026-10-24, condensed)
- **v3 — PenguPlay live test**: streams carry `behaviorHints.proxyHeaders.request` + `filename`;
  entries without `url` are skipped. `guessStreamKind` must use `contains`, not `endsWith`, so
  `file.mkv|^|meta` filenames classify correctly.
- **v4 — provider/catalog plumbing**: provider uses `TvType.Others` so it is not listed under
  Movies/Series; items stay `Movie`/`TvSeries`. Home uses one tab per catalog with per-catalog
  `skip` offsets. The Pengu/MovieBox CDN returns HTTP 428 for Mozilla UAs, so inject
  `okhttp/4.12.0` when the addon supplies no User-Agent (covers playback and downloads).
  Timeout split: manifest/catalog/meta 20 s, streams/subtitles 90 s.
- **v5 — why slow addons were skipped**: NiceHttp `timeout = 0` leaves OkHttp's default 10 s read
  timeout, so a longer `withTimeoutOrNull` cannot help. Every `app.get` must pass an explicit
  `timeout` in seconds. Stream fan-out is `supervisorScope` with per-addon catch rethrowing
  `CancellationException`.
- **v6 — P2P/torrent support**: `infoHash`-only streams become `magnet:` links
  (`StremioMapping.torrentUrlFor`: `dn`, `tr` from `sources` excluding `dht:`, `index=fileIdx`)
  and `ExtractorLinkType.MAGNET`. CloudStream's bundled torrent server plays them after a one-time
  consent prompt; plugins need no engine. No P2P opt-out by request.
- **v7 — episode names**: Nuvio puts the label in `video.title`; `episodeName` prefers `name`,
  then `title`, then the video id.
- **v8 — removed the HEAD probe**: per-link HEADs returned 403/404 and only added up to 3.5 s per
  candidate; classification is URL-only via `guessStreamKind` (scans the full URL for
  `.m3u8`/`.mpd`). Added a stream cache (TTL shortened later).
- **v9 — efficiency**: CloudStream loads home with one `getMainPage` call and cannot lazy-load
  rows, so every catalog is fetched eagerly, bounded by `Semaphore(6)`. Subtitles emit
  concurrently; every catch rethrows `CancellationException`; failed URLs back off 30 s; caches
  are pruned and capped; whitespace URLs are dropped.
- **v10**: `MAX_LINKS` raised to 500 (one shared ceiling).
- **v11–v14 — subtitles/parsing hardening**: embedded `subtitles` + `lang_code`; top-level
  `behaviorHints.headers` fallback (proxyHeaders wins, default UA still injected). Subtitle gate
  lessons: only an addon that actually queried streams may release `firstAddonDone`, and the
  consumer must await every addon's manifest (`manifestDone`) before snapshotting the shared map.
  `StremioPayload` ignores unknown fields and rejects blank id/type. `guessStreamKind` gives
  explicit extensions priority over `/hls`/`/playlist` hints; `failedUntil` capped at 256;
  external subtitles emitted per addon instead of after `awaitAll`; removed the unimplemented
  `hasQuickSearch` flag.
- **v15 — subtitle/language fixes**: PlavioHD 404s on any subtitle path extra, so try
  `[with-extra, plain]` and fall back only on a non-JSON body (not on transport timeouts).
  Because CloudStream has no per-link language field, bake `[Hindi, English]`-style tags into
  link names (superseded by the audio-first parser in v27).
- **v16 — stream cache**: TTL 5 min → 60 s so "Reload mirrors" actually refetches. Aggregator
  cold caches (cnc 9 → 20 streams) explain many count differences; not a parser bug.
- **v17 — AIOMetadata**: duplicate catalog names collapsed in CloudStream's title-keyed home map,
  so append `(Movies)`/`(Series)` only when names collide. Required catalog extras use `default`
  or the first option; anime catalogs are loadable; `showInHome:false` catalogs are surfaced
  because CloudStream has no Discover/genre picker.
- **v18 — Yukistreams**: drop sentinel "No streams found" streams (path `/stream-errors/` or the
  `[YS INFO]` label) so users never get the error clip.
- **v19 — Pluto TV**: `tv` types are live, movie-like items with one HLS stream;
  `usesEpisodes` excludes live types from the series path. Pluto URLs are time/geo-bound; its
  main row ignores `skip`.
- **v20 — multiple catalog addons**: catalogs are an ordered list, each item/catalog ref carries
  its `base`; the primary addon supplies search/meta, and meta lookup falls back past stubs.
  Home rows concatenate primary-first with global title dedupe.
- **v21 — per-addon settings**: catalogs/sources have `enabled` flags persisted as disabled-URL
  lists (missing keys = enabled); rows support edit/delete; disabling the primary promotes the
  next enabled catalog.
- **v22–v25 — language expansion**: grew aliases to ~40 languages and accented-Latin matching.
- **v26 — rollback**: reverted everything after `f878d2a` to restore the baseline language
  detection; version bumped to 26.

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

## 2026-10-25 — `other` VOD catalogs (CNCVerse) — version 31
- CNCVerse's 30 catalogs are 18 `other` VOD + 12 `tv`; only the `tv` rows were loadable because
  `other` was excluded from `SUPPORTED_MEDIA_TYPES`. Adding `other` surfaces the VOD rows and makes
  them searchable when CNCVerse is the primary catalog addon (additional catalog addons remain
  home-only, as before).
- CNCVerse `other` metas always declare `type: "other"`; movie metas have no `videos`, series metas
  do (Punisher 28, Black Clover 170). New `usesEpisodes(type, videos)` delegates to the unchanged
  one-arg form, then only for `other` requires a non-blank video id and a positive season/episode.
  `movie`/`series`/`anime`/`tv` behavior is untouched, and movie-like `other` items stay movies.
- Episode payloads keep type `other`: CNCVerse ignores the type path for `cnc:` ids and its stream
  resource falls back to the manifest types, so its own links still resolve. Normalizing to
  `series` would only add useless source queries for ids other addons do not know.
- Sampled VOD catalogs: 9/18 returned no streams for their first item; the 9 with links were
  playable (4KHDHUB, HDHub4U, Movies4u, VegaMovies, Rogmovies, CastleTV, MovieBoxIN, AniPM,
  ReANIME). Both VOD and TV catalogs advertise `skip` but ignore it, so load-more repeats.
- 71 JVM tests pass via `streamiolink-tests`, including regression coverage for existing media
  types and negative `other` cases. Bumped 30 → 31.

## 2026-10-26 — Public trackers for infoHash magnets — version 32
- The decompiled `pluginsExample/StremioAddon` enriches magnet links with the ngosang public
  tracker list; ours only used addon `tracker:` sources, so a bare `infoHash` could yield a magnet
  with no trackers. This was the last open item from that comparison (TODO §D).
- `StremioMapping.torrentUrlFor(stream, publicTrackers)` now merges up to 10 public trackers after
  the addon's own, deduped; `dn`, addon trackers and `fileIdx`/`index` keep their existing order.
  `parsePublicTrackers` accepts only `udp/http/https/wss`, drops blanks/comments/dupes, and
  `needsInfoHashMagnet` mirrors the direct-URL → magnet fallback (magnets in `stream.url` are
  passed through unchanged).
- Battery/on-device policy: no fetch at plugin load, home, search or meta. `StremioApi`
  `getPublicTrackers()` is called from `loadLinks` only when an addon response contains an
  infoHash-only stream; it caches in memory for 12 h behind a dedicated mutex (one 5 s request per
  process), uses the regular 30 s failure back-off, and serves the stale list on refresh failure.
- 74 JVM tests pass via `streamiolink-tests` (3 new: parser, magnet merge, magnet predicate).
  CI run `40283806944` passed and published version 32.
