# StreamioLink vs. Open-Source CloudStream / Stremio Implementations

**Date:** 2026-10-20
**Scope:** Research only — no code changes.
**Compared against:** `dev` branch, `StreamioLinkDev` version 103 (stable `StreamioLink` v16 architecture is the same).

---

## What I reviewed

Open-source CloudStream/Stremio implementations closest to ours:

| Project | What it is | Main difference from StreamioLink |
|---|---|---|
| [hexated/StremioX](https://github.com/hexated/cloudstream-extensions-hexated) | CloudStream plugin | TMDB metadata + **hardcoded Torrentio**; no multi-addon config; resolves `ytId`/`externalUrl` via `loadExtractor`; downloads a public tracker list for magnets |
| [DieGon7771/ItaliaInStreaming](https://github.com/DieGon7771/ItaliaInStreaming) (hadEnough fork) | CloudStream plugin | "Sections": each section = catalog + stream addons and is registered as its **own provider**; settings fragment; same tracker/`loadExtractor` behavior |
| [phisher98/cloudstream-extensions-phisher](https://github.com/phisher98/cloudstream-extensions-phisher) (docs) | CloudStream plugin | Typed stream-addon list (subtitle/torrent/https/debrid), one provider per catalog addon, AIOStreams wrapping |
| [SaurabhKaperwan/CSX](https://github.com/SaurabhKaperwan/CSX) `CineStream` | CloudStream plugin | Not a generic Stremio client: Cinemeta/Kitsu metadata + its own extractors; very rich metadata (actors, recommendations, trailers, anime IDs) and curated genre rows |
| [Emre-Kahveci/CloudStreamHub](https://github.com/Emre-Kahveci/CloudStreamHub) `TorrentioResolver` | CloudStream repo | Torrentio-only resolver with serious stream hygiene: filters CAM/TS/SCR and low seeds, tags REMUX/HDR/DV/Atmos/size/seeds, keeps top 3 per quality, requires `fileIdx`, adds tracker magnets |
| [errorcode26/CS3-desktop-client-unofficial](https://github.com/errorcode26/CS3-desktop-client-unofficial) | CloudStream Desktop | Full addon manager: manifest preview, enable/disable/reorder/refresh, default addons, per-addon errors; correct `stremio://`, query-string and ID-encoding handling |
| [codegeasse1/hikari](https://github.com/codegeasse1/hikari) `StremioAddon` | Standalone app, most complete protocol impl. | Multi-ID-spelling walk (`tt`/`tmdb:`/custom), 10 s per spelling, partial results, placeholder/error-row detection, config-page discovery, query preservation, YouTube resolver |
| [abrogani/automatic-sniffle](https://github.com/abrogani/automatic-sniffle) | Flixclusive (not CloudStream) | Full Compose addon CRUD, catalogs from all addons, metadata fallback; useful protocol reference |

**TL;DR:** among actual CloudStream plugins, ours is already the most complete *generic, configurable* Stremio-addon client. The peers are either hardcoded to one source (StremioX, CineStream, TorrentioResolver) or not CloudStream (Hikari, CS3 Desktop, Flixclusive). The real gaps are protocol edge cases, unused metadata, magnet quality, stream hygiene, and addon-management UX.

---

## Where their implementation differs

### 1. Addon URL/install handling — we have concrete bugs

`StremioMapping.normalizeAddonUrl` (`StremioMapping.kt:187`) does not handle:

- `stremio://host/manifest.json` → becomes `https://stremio://host` (invalid).
- Query-string config: `https://host/configure?key=x` → `buildStreamUrl` produces `https://host/configure?key=x/stream/movie/tt....json` — the query swallows the path.
- `/manifest.json?key=x` is not stripped at all.

Hikari (`baseAndQuery`) and CS3 Desktop (`StremioTransport.kt`) split base and query and append the query after the resource path. CS3 Desktop also URL-encodes the media ID; ours does not (`buildMetaUrl`/`buildStreamUrl`/`buildSubtitlesUrl`).

### 2. Manifest capability matching is stricter than the protocol

`StremioProvider.kt:418` and `:698` require `type in resourceTypesFor(...)`. If a manifest/resource omits `types`, `resourceTypesFor` returns an empty list and the addon is **silently dropped**. Our own `idMatchesPrefixes` already treats an empty list as "all"; types should do the same. Hikari treats absent declarations permissively and assumes streams when `resources` is absent. This is bugG #1, still open.

### 3. We parse metadata we never use

`StremioMetaDetails.cast`, `imdbRating`, `runtime` and `logo` are parsed (`StremioModels.kt:74-81`), but `load()` only sets poster/background/plot/year/tags (`StremioProvider.kt:283-306`). StremioX/CineStream surface rating, duration, actors, logo, content rating and recommendations. This is a low-risk enrichment: `rating = Score.from10(imdbRating)`, `duration` from `"142 min"`, `actors = cast.map { Actor(it, null) }`, `logoUrl`.

### 4. Magnet construction is weaker

`StremioMapping.torrentUrlFor` (`StremioMapping.kt:295`) only uses the addon's own `tracker:` sources. A bare `infoHash` produces a trackerless magnet. StremioX/hadEnough/CloudStreamHub add a public tracker list (ngosang `trackers_best.txt` or newtrackon), dedupe and cap (~10). This is TODO D, still open. Put fetch+cache in `StremioApi` (long TTL), keep merge/dedupe/cap in the pure layer, and unit-test it.

### 5. Stream hygiene/ranking

- `TorrentioResolver` filters CAM/TS/SCR, low seed counts (<3) and missing `fileIdx`; it tags REMUX/DV/HDR/Atmos/size/seeds and keeps top 3 per quality.
- Hikari detects placeholder rows ("sign in", "subscribe", "no sources", etc.) so an error message is not shown as a playable server.
- Ours forwards everything after only URL/whitespace checks (`StremioProvider.kt:551-615`). That was a deliberate earlier choice (no filtering), so this should be opt-in or a non-destructive reorder/tagging pass, not a silent filter.

### 6. `ytId` and `externalUrl`

`playbackUrlFor` turns `ytId` into a YouTube **watch page** and `linkTypeFor` marks it `VIDEO` (`StremioMapping.kt:283`, `StremioProvider.kt:813`). ExoPlayer will try to parse HTML. `externalUrl` is parsed (`StremioModels.kt:115`) but never used. StremioX/hadEnough pass both to `loadExtractor`; Hikari has a full YouTube resolver. This is bugG #6 plus TODO B, both previously dropped — worth revisiting because it converts guaranteed-dead links into possible playback.

### 7. Addon management UI/health — biggest UX difference

Ours is three text fields plus a plain URL list (`StremioSettingsDialog.kt:34-157`): no manifest validation, addon names, roles, enable/disable, reorder, logos, capabilities, config URL, or last error. CS3 Desktop/abrogani manage typed addon objects with manifest preview and status. Suggested store: `{name, base, enabled, roles}` JSON, migrating the existing `Array<String>` and keeping the `streamiolink_*` keys; add manifest fetch/preview on add, per-addon toggle/reorder, `behaviorHints.configurationURL` open, and last-error display.

### 8. Smaller correctness items visible from peer patterns

- `MAX_LINKS` is checked per addon coroutine (`StremioProvider.kt:468`), so the global 500 cap is not actually enforced (bugF #3).
- `catalogTabsBase` is set before `catalogTabs` (`StremioProvider.kt:131-143`), and `allCatalogsPage` returns `hasNext = any(catalogSupportsSkip)` (`:150`), which can duplicate every row on vertical scroll (bugG #13/#14).
- Manifest cache is not single-flight; Hikari shares a `Deferred`. A cold home load can fetch the same manifest several times (one per catalog addon + sources). Add a per-base in-flight `Deferred`/mutex.

### 9. Sections / multiple providers

hadEnough and StreamPlay register each section or catalog addon as a **separate provider**, giving per-section isolation. Ours is one provider with all catalogs. That is simpler and avoids provider clutter; TODO C/H already dropped it. I would not revisit unless users ask.

---

## What we do better

- Genuinely generic: one catalog addon + N source addons, manifest-driven, no TMDB key.
- Per-addon progressive fan-out with individual timeouts (`supervisorScope`), so one slow addon never blocks the rest.
- Subtitle handling is stronger than every CloudStream peer: per-addon `videoHash`/`videoSize` hints, embedded `stream.subtitles`, URL dedupe, per-addon fan-out with a 20 s cap.
- Auto-discovered catalogs with per-catalog `skip` pagination and bounded concurrency (6).
- Caches + failure back-off + stale-manifest fallback + cancellation preservation.
- Pure `StremioMapping` layer with JVM unit tests and very detailed device diagnostics — peers have little or none of this.

---

## If you want to act on it

Proposed phased work on `dev` (stable untouched):

1. **Protocol correctness (low risk):** `stremio://` + base/query split + ID encoding; empty `types` = wildcard; use parsed rating/duration/cast/logo; fix `MAX_LINKS`, catalog-tab state, `hasNext`, single-flight manifests. Pure-layer changes get unit tests.
2. **Playback quality:** cached public tracker list merged into magnets; placeholder/error-row detection; optional filter/rank setting; `ytId`/`externalUrl` via `loadExtractor`.
3. **Addon manager:** typed addon store with migration, manifest preview/validation, enable/disable/reorder, config URL, last-error display.
4. **Optional/larger:** sections or multiple catalog providers, anime/`other` catalog types.

Verification: unit tests locally are not runnable here; push to `dev` and watch CI (`gh run watch`), then device logcat via the documented `rish` setup and the live addons in `tools/test-addons.json`. Bump the dev version per publish and cherry-pick onto `main` once verified.
