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
- `StremioPlugin` — plugin entry, `openSettings` hook.
- `StremioProvider` — `MainAPI` implementation; all CloudStream object construction.
- `StremioApi` — network + manifest cache (TTL 30 min, stale fallback).
- `StremioMapping` — pure, Android-free protocol logic (parsing, URL building, quality/prefix matching).
- `StremioModels` — Jackson DTOs of the Stremio protocol.
- `StremioSettings` — persisted keys and defaults.
- `StremioSettingsDialog` — programmatic (no XML resources) settings UI.

## Data Flow
1. Home: catalog addon manifest → catalogs without required extras → `/catalog/{type}/{id}.json` → rows.
2. Courses: search-capable catalogs → `/catalog/{type}/{id}/search=...json` (falls back to meta addon).
3. Load: `/meta/{type}/{id}.json` (catalog addon, then fallback) → movie page or episode list.
4. Links: all source addons declared `stream` resource and matching type/idPrefixes →
   `/stream/{type}/{id}.json` in parallel → URL streams become `ExtractorLink`s
   (HLS/DASH/VIDEO, headers from `behaviorHints.proxyHeaders`).
5. Subtitles: addons declaring `subtitles` resource → `/subtitles/{type}/{id}.json`,
   enriched with `videoHash`/`videoSize` from the first stream when available.

## CI/CD
- `build.yml`: test → `make` + `makePluginsJson` → publish `output_builds` to `builds` branch.
- `repo.json` (main branch) points CloudStream at `builds/plugins.json`.

## Known Limitations
- Magnet/infoHash-only streams are skipped (no torrent engine in CloudStream); debrid-configured addons work.
- `externalUrl` streams are skipped.
- Addon configuration web UIs are not embedded; users paste configured manifest URLs.
