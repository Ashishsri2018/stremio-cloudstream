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
   in parallel **per addon** — each addon emits its `ExtractorLink`s as soon as it responds, so a
   slow addon never delays the fast ones while still being awaited (up to `STREAM_TIMEOUT_MS`).
   URL streams become `ExtractorLink`s (HLS/DASH/VIDEO); headers come from
   `behaviorHints.proxyHeaders`, and when the addon supplies no `User-Agent` the non-Mozilla
   `StremioMapping.DEFAULT_STREAM_USER_AGENT` is injected (some CDNs reject browser UAs with 428).
5. Subtitles: addons declaring `subtitles` resource → `/subtitles/{type}/{id}.json`,
   enriched with `videoHash`/`videoSize` from the first stream when available. Emitted after all
   stream addons resolve so the probe hints are populated.

## CI/CD
- `build.yml`: test → `make` + `makePluginsJson` → publish `output_builds` to `builds` branch.
- `repo.json` (main branch) points CloudStream at `builds/plugins.json`.

## Known Limitations
- Magnet/infoHash-only streams are skipped (no torrent engine in CloudStream); debrid-configured addons work.
- `externalUrl` streams are skipped.
- Addon configuration web UIs are not embedded; users paste configured manifest URLs.
- The provider advertises `TvType.Others` (not episode-based) while still returning
  `Movie`/`TvSeries` items, so CloudStream lists it under a dedicated category instead of mixing it
  into Movies/Series; `loadLinksTimeoutMs` is raised to 180 s for slow debrid addons.
