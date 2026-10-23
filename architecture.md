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
- Read one Stremio **catalog addon** for home rows, search and metadata.
- Fan out stream/subtitle requests to N Stremio **source addons** and merge results.
- Persist addon configuration via the CloudStream data store.

## Module Layout
- `StreamioLink` (module) — plugin entry `StremioPlugin.kt`, `openSettings` hook.
- `StremioProvider` — `MainAPI` implementation; all CloudStream object construction.
- `StremioApi` — network + manifest cache (TTL 30 min, stale fallback).
- `StremioMapping` — pure, Android-free protocol logic (parsing, URL building, quality/prefix matching).
- `StremioModels` — Jackson DTOs of the Stremio protocol.
- `StremioSettings` — persisted keys and defaults.
- `StremioSettingsDialog` — programmatic (no XML resources) settings UI.

## Data Flow
1. Home: catalog addon manifest → loadable catalogs → `/catalog/{type}/{id}.json` → rows.
   A catalog is loadable when its type is `movie`, `series`, `anime`, `anime.movie` or
   `anime.series` and every required extra can be satisfied from the manifest itself
   (`StremioMapping.catalogPresetExtra`: its `default`, else the first `options` entry).
   `search`/`similar`/`calendarVideosIds` therefore stay off the home page. The resolved preset is
   sent as a path extra (`genre=Fall%202026`), so genre-locked catalogs (TMDB By Year, By Language,
   TVDB Genres, MAL Seasons/Studios) still get rows fixed to that one value; `showInHome:false`
   catalogs are surfaced too because CloudStream has no Discover/genre picker. Titles come from
   `StremioMapping.catalogTitles`, which appends `(Movies)`/`(Series)` when a name is shared —
   CloudStream keys home rows by title (`HomeViewModel.expandable[list.name]`), so identical
   movie/series names would otherwise collapse and leave only the later (series) row.
   `mainPage` exposes one tab per catalog (encoded via `StremioMapping.encodeCatalogRef`); the
   initial load returns every catalog at once. Catalogs advertising `skip`
   (`catalogSupportsSkip`) paginate: CloudStream expands a row by requesting a single catalog
   (`request.data` holds the ref) and the next page is fetched with `genre=<preset>&skip=<offset>`
   (`StremioMapping.catalogExtra`), where the offset advances by the number of items the addon
   actually returned (`catalogOffsets`).
   Rows are fetched with **bounded concurrency** (`MAX_CONCURRENT_CATALOGS = 6`, a `Semaphore`):
   a catalog addon can advertise dozens of rows (Nuvio: 27 loadable catalogs) and firing them all
   at once opened as many sockets. `getMainPageTimeoutMs = 90 s` / `searchTimeoutMs = 60 s` cap a
   stuck catalog. (Lazy row loading is **not** possible: CloudStream's `HomeViewModel.load()` calls
   `getMainPage(1, null)` once and renders every returned row; expansion only paginates existing rows.)
2. Search: search-capable catalogs (including `anime.series`/`anime.movie`) →
   `/catalog/{type}/{id}/search=...json`, keeping any preset genre extra (falls back to meta addon).
   Quick search is **not** implemented: `hasQuickSearch` is left at its default `false`, so the
   single-provider quick-search screen (reached from the home search icon) searches on submit via the
   normal `search()` path instead of on every keystroke. Advertising it without overriding
   `quickSearch` would call CloudStream's default, which throws `NotImplementedError`.
3. Load: `/meta/{type}/{id}.json` (catalog addon, then fallback) → movie page or episode list.
   The response shape follows `meta.type` (falling back to the payload type), so an `anime` catalog
   item that resolves to a series keeps its episode list and its episode links use the series type.
4. Links: all source addons declared `stream` resource and matching type/idPrefixes are queried
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
   `/hls/` path DASH and a direct file in a `playlist` folder VIDEO. Stream JSON is cached in memory per addon/id for 60 seconds
   (`StremioApi`), so replaying an episode does not refetch slow addons. Headers come from
   `behaviorHints.proxyHeaders.request`, falling back to a top-level `behaviorHints.headers` for
   addons that emit headers there; `proxyHeaders.request` always wins. When the addon supplies no
   `User-Agent` the non-Mozilla `StremioMapping.DEFAULT_STREAM_USER_AGENT` is injected (some CDNs
   reject browser UAs with 428).
   Each link's visible name is the addon's `name`/`title`/filename (`StremioMapping.streamDisplayName`).
   CloudStream's `ExtractorLink` has no language field, so audio languages text-mined from the addon
   payload (`name`/`title`/`description`/`behaviorHints.filename`, via `StremioMapping.streamLanguages`)
   are prefixed as a compact `[Hindi, English]` tag (`StremioMapping.languageTag`) so they appear in the
   Play dialog; streams with no detectable language are left unchanged.
   `infoHash`-only streams (plain torrent addons) become a magnet via `StremioMapping.torrentUrlFor`
   (infoHash + `tracker:` sources + `fileIdx` as `index`) and are emitted as
   `ExtractorLinkType.MAGNET`; CloudStream's bundled torrent engine plays them from a local server,
   so P2P works without a debrid account.
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
- `infoHash`-only streams are played through CloudStream's built-in torrent engine (as `MAGNET`
  links). This requires the host build to ship the torrent server and the user to accept the
  torrent consent prompt; store builds without a torrent engine cannot play them.
- `externalUrl` streams are skipped.
- Addon configuration web UIs are not embedded; users paste configured manifest URLs. Some addons
  reject malformed config blobs with `401 invalid session` even though their `/manifest.json` is a
  catch-all stub — such an addon fetches a manifest but yields no streams.
- The provider advertises `TvType.Others` (not episode-based) while still returning
  `Movie`/`TvSeries` items, so CloudStream lists it under a dedicated category instead of mixing it
  into Movies/Series. `loadLinksTimeoutMs` is raised to 180 s; individual network calls use
  `StremioApi` timeouts (20 s metadata, 90 s streams/subtitles) so slow addons are not dropped.
