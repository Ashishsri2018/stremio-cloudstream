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

## 2026-10-26 — stremio:// protocol normalization & clipboard auto-fill — version 33
- Addons installed from Stremio apps and web portals use the `stremio://` protocol scheme.
  `StremioMapping.normalizeAddonUrl` previously treated non-http(s) URLs as bare domains and
  prepended `https://`, corrupting them into `https://stremio://<domain>` (`bugG.md` #3).
- Fixed safely via anchored regex prefix stripping (`^(?:https?://)?stremio:/+`):
  - Strips `stremio://`, `stremio:///`, and heals pre-existing corruptions (`https://stremio://`).
  - Preserves explicit `http://` for local Docker/LAN servers while defaulting bare domains to `https://`.
  - Strictly enforces `contains(".")` validation so bare/invalid schemes (`stremio://`, `stremio://not a url`)
    remain safely rejected as `null`.
  - Rejects empty host residues (`https:`, `http:`).
- `StremioSettingsDialog`: auto-detects `stremio:` links on clipboard in "+ Add source addon" / "+ Add catalog addon"
  dialogs and adds `clip.itemCount > 0` guard to prevent `IndexOutOfBoundsException` on empty clipboard states (`bugG.md` #17).
- 74 JVM tests pass via `streamiolink-tests`, including comprehensive regression and edge case tests for `stremio://`.
  Bumped version 32 → 33.

## 2026-10-26 — 720 quality lookbehind precision & audio/CAM false-positive exclusion — version 34
- `StremioMapping.qualityPatterns`: the `720` entry previously used `(?<![0-9])(720p?|hd)(?![0-9a-z])`.
  The numeric lookbehind `(?<![0-9])` allowed non-digit letters before `hd`, causing Dolby TrueHD
  (`Movie.480p.TrueHD`) and DTS-HD (`Movie.480p.DTS-HD`) to match `hd` before 480 was checked (`bugG.md` #4).
  Additionally, camcorder recordings (`Movie.HD-CAM`, `Movie.HD-TS`, `Movie.HD-TC`) matched `hd` and were
  misclassified as 720p HD.
- Fixed safely:
  - Separated numeric resolution (`(?<![0-9])720p?(?![0-9a-z])`, allowing `1280x720`) from alias `hd`.
  - Enforced strict alphanumeric boundaries `(?<![0-9a-z])hd(?![0-9a-z])` on `hd`.
  - Added bounded lookbehinds `(?<!true[-_.\s])(?<!dts[-_.\s])` excluding TrueHD and DTS-HD audio formats.
  - Added negative lookahead `(?!\s*[-_.]?(cam|ts|tc|audio))` excluding camcorder captures and HD Audio.
  - Added `ultra[\s._-]?hd` to `2160` (4K UHD) so `Ultra-HD` releases resolve to 4K instead of falling through to 720.
- 74 JVM tests pass via `streamiolink-tests`, with 16 new test assertions verifying audio formats, CAM rips, and Ultra HD.
  Bumped version 33 → 34.

## 2026-10-27 — bugG audit fixes: YouTube, episode ids, wildcard types, relative subs — version 35
- Audited every `bugG.md` concern against the code, Stremio SDK types, and CloudStream 4.8.0
  (`8bdc599`). Twelve were already fixed; the remaining actionable ones are addressed here.
- `ytId` / YouTube page URLs (`youtube.com`, `m.youtube.com`, `youtu.be`,
  `youtube-nocookie.com`) are now handed to CloudStream's `loadExtractor` instead of being emitted
  as `VIDEO` links, which ExoPlayer cannot play. Extraction failures drop only that candidate.
- Episode numbers: when `season`/`episode` are null, `StremioMapping.episodeIndexFromId` reads the
  last two numeric segments of the video id (`tt1234567:2:5`); a season above 100 is rejected so
  `kitsu:123:5` is not misread as S123E5. `toEpisode` falls back to that index.
- Resource type checks use `StremioMapping.resourceSupportsType`: a missing/empty `types` list is
  treated as a wildcard instead of dropping the addon (Stremio requires `types`, but malformed
  addons are common). Plain `anime` metas also infer episodes from numbered videos.
- Relative subtitle URLs are resolved against the addon base via
  `StremioMapping.resolveResourceUrl`; absolute, protocol-relative, and `data:` URLs pass through.
- `StremioApi.pruneStreams` is serialized with a lock so concurrent queries cannot over-evict the
  bounded stream cache. `StremioMapping.mapper` is now `internal`.
- Chose not to change `allCatalogsPage` (#13, not reproducible: CloudStream expands rows via
  `nameIndex` → `singleCatalogPage`) or the 60 s stream-cache reload window (#11, deliberate
  battery/network trade-off; documented in `known-issues.md`).
- 78 JVM tests pass via `streamiolink-tests` (4 new test methods covering the helpers).
  Bumped version 34 → 35.

## 2026-10-27 — Radio/music catalog support — version 36
- `music` and `radio` catalogs (Stremio Radios) are now in `SUPPORTED_MEDIA_TYPES`, so their home
  rows and search results load. Their items declare `type: "movie"` and ids such as `radios:…`, so
  the existing movie-like load path and the stream idPrefix gate work unchanged.
- Home/search cards for audio catalogs fall back from `poster` to `logo`/`background`
  (`StremioMapping.previewPoster`); movie/series cards keep the previous poster-only behavior.
- Audio-only URLs (extension-less Shoutcast/Icecast, `.mp3`) stay `VIDEO` and play through
  ExoPlayer's progressive MP3/ADTS extractors; HLS radio uses `M3U8`. CloudStream has no audio
  `ExtractorLinkType`, so ICY/Shoutcast and `.pls`/`.m3u` edge cases are documented in
  `known-issues.md`.
- 81 JVM tests pass via `streamiolink-tests` (3 new test methods). Bumped 35 → 36. CI run
  `40486937856` passed and published version 36; Radios and Flix-Music are recorded in
  `tools/test-addons.json` under `musicRadio`.

## 2026-10-27 — Channel (YouTube) support — version 37
- `channel` is now in `SUPPORTED_MEDIA_TYPES`, so channel catalogs are home-loadable/searchable.
  It is deliberately NOT in `isSeriesType`/`LIVE_MEDIA_TYPES`: `usesEpisodes(type, videos)` only
  treats a channel as a series when its meta has numbered videos. YouTube-style channels expose
  uploads as `season`/`episode` videos and load as series; live channels without videos stay
  movie-like single streams.
- Channel catalogs with required `search` (Reddate YT's "YouTube channels") remain search-only;
  channel search uses the existing primary-catalog-only path.
- Search cards for channel items stay movie-like until `load()` sees the meta videos, matching the
  existing `other`/`anime` behavior. `number`-only channel videos are not inferred (Reddate YT uses
  `season`/`episode`).
- 83 JVM tests pass via `streamiolink-tests` (2 new test methods); existing media types re-asserted
  unchanged. Bumped 36 → 37. CI run `40491088787` passed and published version 37.

## 2026-10-27 — Search across all enabled catalogs — version 38
- `search()` now queries every enabled catalog addon (settings order, primary first) instead of
  only the primary, and always includes the separately configured metadata addon when its
  normalized URL is not already in that list.
- Addons are searched concurrently with `supervisorScope`; one failing addon is logged and does
  not cancel the others. Per-catalog requests still share the existing `catalogSemaphore` (6) and
  the whole search stays under `searchTimeoutMs = 60 s`.
- Results are concatenated in addon order; dedupe remains per addon only, so cross-addon
  duplicates are kept intentionally (different addons may return different ids for the same title).
- 83 JVM tests pass via `streamiolink-tests` (no mapping-layer changes). Bumped 37 → 38.

## 2026-10-28 — Live media type + torrent category — version 39
- `live` is now a supported catalog/media type. `StremioMapping.mediaShape` classifies items once:
  numbered episode lists win as series, then `tv`/`live` or `meta.behaviorHints.isLive` become
  `TvType.Live`, and everything else stays movie-like. Search uses `newLiveSearchResponse`; load
  uses `newLiveStreamLoadResponse`, whose `dataUrl` is the encoded payload.
- Live addons disagree about the type advertised for `meta`/`stream`/`subtitles`, so
  `resolveResourceType` resolves it through the cached manifest with the fallback
  `live → tv → channel → movie`. Non-live requests still require an exact type match. All three
  resources use the resolved type.
- `behaviorHints.isLive` is parsed on catalog previews and meta details.
- Provider is advertised under `TvType.Others`, `TvType.Live` and `TvType.Torrent`; Gradle
  `tvTypes` now lists `Movie`, `TvSeries`, `Live`, `Torrent`. Torrent advertisement is metadata
  only — existing `infoHash`/magnet emission is unchanged.
- 86 JVM tests pass via `streamiolink-tests` (3 new/updated test methods). Bumped 38 → 39. CI run
  `40628014593` passed and published version 39; `builds/plugins.json` lists
  `Movie`/`TvSeries`/`Live`/`Torrent`.
