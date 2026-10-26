# StreamioLink Architecture

## Overview
A CloudStream 3 (Android) plugin that acts as a client for the Stremio addon ecosystem.

## Technology Stack
- **Language**: Kotlin
- **Build System**: Gradle (Kotlin DSL) + Cloudstream gradle plugin, executed in GitHub Actions
- **Framework**: Cloudstream Plugin API (`MainAPI`, `Plugin`)
- **Parsing**: Jackson (`jackson-module-kotlin`, pinned to 2.13.1 for app compatibility)

## Responsibilities
- Register one CloudStream provider (`StreamioLink`) into the app.
- Read one or more Stremio **catalog addons** (the first enabled is primary for home ordering,
  search and metadata) for home rows, search and metadata.
- Fan out stream/subtitle requests to N Stremio **source addons** and merge results.
- Persist addon configuration via the CloudStream data store.

## Module Layout
- `StreamioLink` (module) — plugin entry `StremioPlugin.kt`, `openSettings` hook.
- `StremioProvider` — `MainAPI` implementation; all CloudStream object construction.
- `StremioApi` — network + manifest cache (TTL 30 min, stale fallback).
- `StremioMapping` — pure, Android-free protocol logic (parsing, URL building, quality/prefix matching).
- `StremioModels` — Jackson DTOs of the Stremio protocol.
- `StremioSettings` — persisted keys and defaults. Catalog and source addons have an `enabled`
  flag (persisted as the `streamiolink_disabled_catalog_urls` / `streamiolink_disabled_source_urls`
  URL lists). Disabled entries stay configured and visible in settings but are skipped everywhere:
  a disabled catalog contributes no home rows, is not the primary search/metadata addon and is not
  queried for streams, even when its `useAsSource` flag is on. The primary catalog is the first
  *enabled* entry, so disabling it promotes the next enabled one; the settings UI prevents
  disabling the last enabled catalog.
- `StremioSettingsDialog` — programmatic (no XML resources) settings UI. Each catalog/source row is
  one line: URL, enable checkbox, edit (`✏️`) and delete (`🗑️`) buttons. Editing changes only the
  URL, keeping the entry's position, `useAsSource` and enabled state. Disabling reuses the cached
  tab invalidation because `StremioProvider.catalogSettingsKey()` is derived from the enabled
  catalog URLs.

## Data Flow
1. Home: each catalog addon's manifest → loadable catalogs → `/catalog/{type}/{id}.json` → rows.
   A catalog is loadable when its type is `movie`, `series`, `anime`, `anime.movie`,
   `anime.series`, `tv` or `other` and every required extra can be satisfied from the manifest
   itself (`StremioMapping.catalogPresetExtra`: its `default`, else the first `options` entry).
   `search`/`similar`/`calendarVideosIds` therefore stay off the home page. The resolved preset is
   sent as a path extra (`genre=Fall%202026`), so genre-locked catalogs (TMDB By Year, By Language,
   TVDB Genres, MAL Seasons/Studios) still get rows fixed to that one value; `showInHome:false`
   catalogs are surfaced too because CloudStream has no Discover/genre picker. Titles come from
   `StremioMapping.catalogTitles`, which appends `(Movies)`/`(Series)`/`(Other)` when a name is
   shared —
   CloudStream keys home rows by title (`HomeViewModel.expandable[list.name]`), so identical
   movie/series names would otherwise collapse and leave only the later (series) row.
   With multiple catalog addons the primary addon's rows come first, then each additional addon's
   rows in settings order; `StremioMapping.disambiguateTitles` suffixes cross-addon title collisions
   with the addon name so no row is dropped. `mainPage` exposes one tab per catalog (encoded via
   `StremioMapping.encodeCatalogRef`, including the owning addon as `base`); the initial load
   returns every catalog from every addon at once. Catalogs advertising `skip`
   (`catalogSupportsSkip`) paginate: CloudStream expands a row by requesting a single catalog
   (`request.data` holds the ref) and the next page is fetched with `genre=<preset>&skip=<offset>`
   (`StremioMapping.catalogExtra`), where the offset advances by the number of items the addon
   actually returned (`catalogOffsets`, keyed by addon+catalog).
   Rows are fetched with **bounded concurrency** (`MAX_CONCURRENT_CATALOGS = 6`, a `Semaphore`):
   a catalog addon can advertise dozens of rows (Nuvio: 27 loadable catalogs) and firing them all
   at once opened as many sockets. `getMainPageTimeoutMs = 90 s` / `searchTimeoutMs = 60 s` cap a
   stuck catalog. (Lazy row loading is **not** possible: CloudStream's `HomeViewModel.load()` calls
   `getMainPage(1, null)` once and renders every returned row; expansion only paginates existing rows.)
2. Search: the primary catalog addon's search-capable catalogs (including
   `anime.series`/`anime.movie` and `other`) → `/catalog/{type}/{id}/search=...json`, keeping any
   preset genre extra (falls back to the metadata addon). Additional catalog addons only contribute
   home rows.
   Quick search is **not** implemented: `hasQuickSearch` is left at its default `false`, so the
   single-provider quick-search screen (reached from the home search icon) searches on submit via the
   normal `search()` path instead of on every keystroke. Advertising it without overriding
   `quickSearch` would call CloudStream's default, which throws `NotImplementedError`.
3. Load: `/meta/{type}/{id}.json` from the item's own catalog addon (payload `base`), then the
   primary catalog addon, then the metadata fallback → movie page or episode list. The item payload
   carries its addon `base` for this reason: Plavio live `tv` ids (`plaviohdtv-…`) only resolve on
   Plavio, while Cinemeta/TMDB only know `tt…` ids. Stub metas (blank name, or a series meta with no
   videos / a name equal to the id) are skipped so the next addon can answer: Plavio's `/meta` is a
   stub for VOD ids but complete for live channels. The response shape follows `meta.type` (falling
   back to the payload type), so an `anime` catalog item that resolves to a series keeps its episode
   list and its episode links use the series type. `other` metas are common in VOD bridge addons
   such as CNCVerse: they stay movie-like unless their `videos` contain a numbered episode with an
   id, in which case `StremioMapping.usesEpisodes(type, videos)` loads a series. The episode payload
   keeps `other`, so the bridge's own stream endpoint is used.
   Live `tv` channels (Pluto TV and similar) are the exception: their meta carries no `videos`, so
   `StremioMapping.usesEpisodes` (series and not `LIVE_MEDIA_TYPES`) makes each channel a single
   movie-like item whose play action hits `/stream/tv/{id}.json` for one endless HLS URL.
   Metadata: catalog previews and metas expose `imdbRating`; `StremioMapping.ratingFrom10` parses
   it (0..10, null when absent/unrated) and both home/search cards and load responses map it to
   CloudStream's `Score`; `app_extras.ratings` (Nuvio) is the fallback. Load responses also map
   `logo` → `logoUrl`, `runtime` → `duration` (minutes), `ageRating`/`app_extras.certification` →
   `contentRating`, cast (`app_extras.cast` objects first, then top-level names) → `actors`,
   `trailerStreams`/`trailers` → `trailers`, and series `status` → `showStatus`
   (`Ended`/`Released`/`Canceled` → `Completed`; `Continuing`/`Returning Series`/`In Production`/
   `Planned` → `Ongoing`). Episode `rating` → `Episode.score` and `runtime` → `Episode.runTime`
   (minutes; CloudStream's episode UI multiplies by 60 before formatting). `videos[].available` is
   parsed but not filtered; season posters have no CloudStream field. Card badges additionally
   depend on CloudStream's "Show ratings" setting.
4. Links: all enabled source addons (plus enabled catalog addons with `useAsSource`) declaring a
   `stream` resource and matching type/idPrefixes are queried
   in parallel **per addon** (`supervisorScope`, so one addon's failure cannot cancel the others) —
   each addon emits its `ExtractorLink`s as soon as it responds, so a slow addon never delays the
   fast ones while still being awaited (up to `STREAM_TIMEOUT_MS`). Every addon request passes an
   explicit per-call timeout (`app.get(url, timeout = seconds)`), because CloudStream's NiceHttp
   `app` defaults to `timeout = 0`, which leaves OkHttp's 10 s read timeout in place and silently
   drops slow aggregators. URL streams become `ExtractorLink`s; the kind (M3U8/DASH/VIDEO) is
   derived purely from the URL/filename via `StremioMapping.guessStreamKind` — there is **no**
   per-link network probe (a HEAD probe was mostly wasted: the tested hosts answer HEAD with
   403/404 and it delayed emission). `guessStreamKind` treats explicit extensions as authoritative
   over semantic path hints: `.mpd` → DASH and `.m3u8` → HLS win outright, a known video-container
   extension (`.mp4/.mkv/.webm/…`) → VIDEO, and only then do the `/hls`, `/playlist`, `mpegurl`
   path hints and a `.m3u8` hidden in a proxy query apply. This keeps a DASH manifest under an
   `/hls/` path DASH and a direct file in a `playlist` folder VIDEO. Addon "nothing found"
   sentinel streams (URLs under `/assets/stream-errors/`, e.g. Yukistreams' `[YS INFO] No streams
   found`, or placeholder health endpoints such as Plavio's `https://sports.highfly.to/health`) are dropped by `StremioMapping.isSentinelStream` instead of being offered as a link
   that plays a stock error clip. Stream JSON is cached in memory per addon/id for 60 seconds
   (`StremioApi`), so replaying an episode does not refetch slow addons. Headers come from
   `behaviorHints.proxyHeaders.request`, falling back to a top-level `behaviorHints.headers` for
   addons that emit headers there; `proxyHeaders.request` always wins. When the addon supplies no
   `User-Agent` the non-Mozilla `StremioMapping.DEFAULT_STREAM_USER_AGENT` is injected (some CDNs
   reject browser UAs with 428).
   Each link's visible name is the addon's `name`/`title`/filename (`StremioMapping.streamDisplayName`).
   CloudStream's `ExtractorLink` has no language field, so audio languages text-mined from the addon
   payload (`name`/`title`/`description`/`behaviorHints.filename`, via `StremioMapping.streamLanguages`)
   are prefixed as a compact `[Tamil, English]` tag (`StremioMapping.languageTag`) so they appear in the
   Play dialog; streams with no detectable language are left unchanged. Explicit
   `Audio:`/`Language:`/`🎧`/`🔊` lines are authoritative, so subtitle lists never become audio tags;
   only when no audio line exists is the release name/filename scanned, with subtitle-only lines
   skipped. Detection covers ~40 common movie/series/anime languages using full English names plus
   unambiguous scene/ISO codes; ambiguous short codes that double as ordinary words or names are
   intentionally excluded.
   `infoHash`-only streams (plain torrent addons) become a magnet via `StremioMapping.torrentUrlFor`
   (infoHash + `tracker:` sources + up to 10 public trackers + `fileIdx` as `index`) and are emitted
   as `ExtractorLinkType.MAGNET`; CloudStream's bundled torrent engine plays them from a local
   server, so P2P works without a debrid account. The public tracker list (ngosang
   `trackers_best.txt`) is fetched only when an `infoHash` magnet is about to be produced — never on
   home/search/meta: `StremioApi.getPublicTrackers` caches it in memory for 12 h behind a dedicated
   mutex (at most one 5 s request per process; on failure the stale list is served and the regular
   30 s back-off applies). Addon trackers stay permissive and come first; public entries are
   scheme-filtered and deduped. Magnet URLs carried in `stream.url` are passed through unchanged.
5. Subtitles: addons declaring `subtitles` resource → `/subtitles/{type}/{id}.json`,
   enriched with `videoHash`/`videoSize` from the first stream when available. The canonical
   hint-bearing URL is tried first; if the addon answers with a non-JSON body (some, e.g.
   PlavioHD, implement only the extra-less route and return an HTML 404 for any path extra),
   `StremioApi.getSubtitles` retries the plain URL (`StremioMapping.buildSubtitlesUrlCandidates`).
   A transport failure/timeout does not trigger the retry, to avoid paying a second full timeout.
   Fetched **concurrently**
   with the stream addons: emission starts once the fastest **stream-capable** addon has answered
   (`firstAddonDone` `CompletableDeferred`, completed only by an addon that declared the `stream`
   resource and reached its stream query) using the probe hints available at that point, instead of
   waiting for every (possibly 90 s) stream addon. Catalog/meta-only addons (e.g. Cinemeta, first in
   the list by default) must not release the gate, or subtitles would be queried with null hints
   before any stream has been seen; if no addon supports streams, a fallback `joinAll` releases the
   gate once every addon has settled so the subtitle coroutine cannot hang. A second gate,
   `manifestDone` (one `CompletableDeferred` per addon, completed in that addon's `finally`), is
   awaited before the fan-out: it reads the shared `manifests` map, so an addon whose manifest has
   not arrived yet would be silently dropped along with its external subtitles. Manifests are a
   single 20 s round-trip (vs. the 90 s stream path), so waiting for all of them preserves the
   latency goal while making the queried addon set complete.
   The fan-out itself also emits per addon: each subtitle addon's results are forwarded as that
   addon answers, so a slow one no longer holds back a fast one's subtitles (which a batched
   `awaitAll()` did, up to the 90 s stream timeout). The shared `seen` set is concurrent and the
   subtitle callback is already invoked from several stream coroutines, so only the ordering of
   addon subtitles is nondeterministic.
   Per-stream embedded subtitles (`stream.subtitles`, a valid Stremio protocol field) are emitted as
   each addon's streams arrive, independent of whether any addon declares the `subtitles` resource;
   a shared URL-dedup set merges embedded and addon results. Language uses `lang`, falling back to
   `lang_code` then `"Unknown"` (`StremioMapping.subtitleLanguage`).

## Performance & resilience
- **Bounded fan-out** for catalog/home/search requests (`Semaphore`), so a wide manifest cannot
  exhaust sockets.
- **Total link ceiling**: `StremioProvider.MAX_LINKS = 500` caps links forwarded across all source
  addons (a shared `AtomicInteger`). It is only an emission ceiling — every addon is fetched in full
  regardless, so it adds no network cost. No per-addon cap: per-addon counts are the servers' call.
- **Failure back-off** (`StremioApi`): a URL that fails (timeout/exception) is not retried for 30 s,
  so a down addon is not hammered once per screen. Successful responses clear the back-off, and the
  backing map is bounded: each failure evicts expired entries and, past 256, the soonest-expiring.
- **Cancellation is preserved**: every `StremioApi` catch rethrows `CancellationException` instead of
  swallowing it as an empty result.
- **Bounded caches**: the manifest cache is pruned + capped (32), the stream cache evicts expired and
  then oldest entries instead of being cleared wholesale, and the failure-back-off map is bounded (256).
- **Dead-link guard**: `StremioMapping.isPlayableStreamUrl` drops non-`http(s)`/whitespace-containing
  URLs (e.g. CNCVerse's literal-space URLs) before they reach the player.

## CI/CD
- `build.yml`: test → `make` + `makePluginsJson` → publish `output_builds` to `builds` branch.
- `repo.json` (main branch) points CloudStream at `builds/plugins.json`.

## Known Limitations
See `known-issues.md` for the current list (torrent/P2P constraints, skipped `externalUrl`
streams, addon-config quirks, live-TV caveats, CloudStream video-track selection, and addon-side
issues).
