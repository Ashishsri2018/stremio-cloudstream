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
1. Home: catalog addon manifest → catalogs without required extras → `/catalog/{type}/{id}.json` → rows.
   `mainPage` exposes one tab per catalog (encoded via `StremioMapping.encodeCatalogRef`); the
   initial load returns every catalog at once. Catalogs advertising `skip`
   (`catalogSupportsSkip`) paginate: CloudStream expands a row by requesting a single catalog
   (`request.data` holds the ref) and the next page is fetched with `skip=<offset>`, where the
   offset advances by the number of items the addon actually returned (`catalogOffsets`).
2. Courses: search-capable catalogs → `/catalog/{type}/{id}/search=...json` (falls back to meta addon).
3. Load: `/meta/{type}/{id}.json` (catalog addon, then fallback) → movie page or episode list.
4. Links: all source addons declared `stream` resource and matching type/idPrefixes are queried
   in parallel **per addon** (`supervisorScope`, so one addon's failure cannot cancel the others) —
   each addon emits its `ExtractorLink`s as soon as it responds, so a slow addon never delays the
   fast ones while still being awaited (up to `STREAM_TIMEOUT_MS`). Every addon request passes an
   explicit per-call timeout (`app.get(url, timeout = seconds)`), because CloudStream's NiceHttp
   `app` defaults to `timeout = 0`, which leaves OkHttp's 10 s read timeout in place and silently
   drops slow aggregators. URL streams become `ExtractorLink`s (HLS/DASH/VIDEO); headers come from
   `behaviorHints.proxyHeaders`, and when the addon supplies no `User-Agent` the non-Mozilla
   `StremioMapping.DEFAULT_STREAM_USER_AGENT` is injected (some CDNs reject browser UAs with 428).
   `infoHash`-only streams (plain torrent addons) become a magnet via `StremioMapping.torrentUrlFor`
   (infoHash + `tracker:` sources + `fileIdx` as `index`) and are emitted as
   `ExtractorLinkType.MAGNET`; CloudStream's bundled torrent engine plays them from a local server,
   so P2P works without a debrid account.
5. Subtitles: addons declaring `subtitles` resource → `/subtitles/{type}/{id}.json`,
   enriched with `videoHash`/`videoSize` from the first stream when available. Emitted after all
   stream addons resolve so the probe hints are populated.

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
