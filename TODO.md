# StreamioLink — Ideas for Future Upgrades

Not a roadmap; nothing here is committed to a version. Just ideas worth revisiting.

## Presets — DROPPED (not needed)
- ~~Bundle one catalog addon + N source addons into a named preset; create/select/delete in settings.~~
- Decided against: not needed. (2026-10-17)

## Port from StreamPlay (Phisher98) Stremio support
Reference: `phisher98/cloudstream-extensions-phisher` — `StreamPlayStremioAddon.kt`,
`StreamPlayStremioCatelog.kt`, `StreamPlayPlugin.kt` (mirrored in `xr3ed/xr3ed-Repo` under
`StreamPlayBackup/`). StreamPlay keeps a typed stream-addon list (`SUBTITLE`/`TORRENT`/`HTTPS`/
`DEBRID`) and registers one provider per catalog addon; our manifest-driven single-catalog design
covers the same ground with caching/back-off/timeouts, so port only these specific capabilities.

### A. Parse and emit per-stream embedded subtitles + `behaviorHints.headers` + subtitle `lang_code`
- [x] `StremioStream` has no `subtitles` field, so addon-embedded subtitles (a valid Stremio
  protocol field on the stream object) are ignored. Add `subtitles: List<StremioSubtitle>` and emit
  them (deduped by URL) alongside the subtitle-addon results in `emitSubtitleLinks`.
  (Done 2026-10-17: emitted per-addon as streams arrive via a shared URL-dedup set;
  `emitSubtitleLinks` no longer skips when no subtitle addon is configured.)
- [ ] `StremioBehaviorHints` models only `proxyHeaders`; some addons put request headers under a
  top-level `behaviorHints.headers`. Add the field and use it as a fallback in
  `StremioMapping.requestHeadersFor` without overriding `proxyHeaders.request`.
- [x] `StremioSubtitle` only has `lang`; fall back to `lang_code` when `lang` is missing.
  (Done 2026-10-17: `StremioMapping.subtitleLanguage` — `lang` → `lang_code` → `"Unknown"`.)
- Scope: `StremioModels` + `StremioMapping` + `StremioProvider` + unit tests. No new network paths.

### B. Resolve `externalUrl` via `loadExtractor`  — DROPPED (not needed)
- [ ] `StremioStream.externalUrl` (`StremioModels.kt`) is parsed but deliberately skipped, so
  addons that return a file-host page instead of a direct link produce no playable link. Hand the
  `externalUrl` to CloudStream's `loadExtractor(url, referer, subtitleCallback, callback)` so any
  registered `ExtractorApi` resolves it to a real link.
- [ ] Keep it behind the existing failure/cancellation guards; unknown hosts still yield nothing.
  Adds a page fetch + scrape per externalUrl (see architecture note below).

### C. (larger, not scoped) multiple catalog addons  — DROPPED (not needed)
- StreamPlay registers each user catalog addon as its own provider (`StreamPlayStremioCatelog`).
  We support one catalog addon; this (or the sections design in "H" below) is how it would grow.

## `externalUrl` → `loadExtractor` (what it is / why)
In the Stremio protocol a stream entry carries either a direct, playable `url` (or an `infoHash`
for a torrent) **or** an `externalUrl`. `externalUrl` is a link to a web **page** that hosts the
video (e.g. a FileMoon/Dood/StreamTape embed page, or the site's watch page) — not a media file.
Stremio hands it to a browser; there is nothing to play directly.
`loadExtractor(url, referer, subtitleCallback, callback)` is a CloudStream utility that looks up
the registered `ExtractorApi` whose URL pattern matches that page, lets it fetch and scrape the
page, and converts it into real playable `ExtractorLink`(s) (the underlying `.m3u8`/`.mp4`). If no
registered extractor matches the host, it resolves to nothing.
Today we skip `externalUrl` (see architecture.md Known Limitations) and register no extractors, so
those streams never play. Adopting B means: when a stream has no playable `url`/`infoHash`, pass
its `externalUrl` to `loadExtractor` instead of dropping it — it only helps for hosts CloudStream
(or an installed plugin) already knows, and costs one page fetch + parse.

## Port from hadEnough / StremioX (doGior)
Reference: `DieGon7771/ItaliaInStreaming` (fork of `doGior/hadEnough`) — `Stremio/…/StremioXPlugin.kt`
+ `SectionProvider.kt` + `Utils.kt` + `SubsExtractors.kt`. "Sections" = a name + one optional
catalog URL + up to 5 stream addons; **each section is registered as its own `MainAPI` provider**.
This repo independently confirms A (headers fallback `proxyHeaders.request ?: headers`, embedded
`stream.subtitles`, `SubtitleHelper` language normalization) and B (`externalUrl`/`ytId` via
`loadExtractor`), and adds these:

### D. Enrich infoHash magnets with a public tracker list
- [ ] `Stream.runCallback` builds `magnet:` from `infoHash` **plus** the stream's `tracker:` sources
  **plus** trackers fetched from a public list (`ngosang/trackerslist …/trackers_best.txt`; their
  Torrentio uses `newtrackon.com/api/stable`). Our `StremioMapping.torrentUrlFor` only uses the
  addon's own trackers, so a bare `infoHash` yields a magnet with no trackers and may never connect.
- [ ] Fetch + cache the list (long TTL), merge/dedup, cap (doGior caps at ~10 trackers). Pure win for
  P2P playback; keep it out of the pure `StremioMapping` layer (it's a network concern for `StremioApi`).

### E. Built-in subtitle fallbacks when no subtitle addon is configured  — DROPPED (not needed)
- [ ] Always query OpenSubtitles v3 (`https://opensubtitles-v3.strem.io/subtitles/{movie|series}/{id}.json`)
  and merge with addon subtitles (`SubsExtractors.invokeOpenSubs`). Their WatchSoMuch scrape is
  optional/brittle — likely skip. Gives subtitles to users who only configured stream addons.

### F. Normalize `stremio://` install links  — DROPPED (not needed)
- [ ] `fixSourceUrl()` maps `stremio://…` → `https://…` (Stremio addon install URLs) and strips
  `/manifest.json`. `StremioMapping.normalizeAddonUrl` currently only strips the manifest suffix, so a
  pasted `stremio://` link is rejected. Tiny, high-value settings UX fix (+unit test).

### G. Force Cinemeta for imdb/tmdb meta ids
- [ ] When loading an entry whose `type` is movie/series and whose id is `tt…`/`tmdb:…`, they query
  `v3-cinemeta.strem.io` for meta instead of the catalog addon, because catalog addons often can't
  serve meta for external ids. We have a manual "meta fallback addon" setting; this is an automatic
  rule that removes a failure mode (+unit test on the id/type predicate).

### H. Sections / multiple providers (larger — overlaps StreamPlay "C")  — DROPPED (not needed)
- Per-section catalog + addon set, each registered as a separate provider, so users can run
  independent setups (e.g. Italian movies catalog + Italian torrents) side by side.

### I. TMDB-fallback metadata mode (larger — needs a TMDB API key) — DROPPED (not needed)
- With no catalog addon, build home rows/search/metadata from TMDB and resolve the imdb id for the
  stream addons. Lets the plugin work with stream addons only (no catalogs). Requires embedding a
  TMDB key (`secrets.properties` → `BuildConfig`), which we deliberately avoid today (Cinemeta is free).

## Raise total link cap to 500 (Done)
- [x] `StremioProvider.MAX_LINKS` raised 120 → **500** (a single total ceiling shared across addons;
  the CloudStream app itself imposes no cap — `RepoLinkGenerator` only URL-dedups and stores links in a
  `PersistentSet`).
- [x] No per-addon cap — per-addon counts are the addon servers' decision.
- [x] Forwarding ceiling only: addons are already fully fetched regardless, so no extra network.
- (Decided 2026-10-16; deliberately left out of the v9 efficiency pass. Done 2026-10-17.)
