# StreamioLink Known Issues

Current, unresolved issues and limitations. New entries go here, not in `memory.md` (which stays a
dated change/release log). Addon-side problems are included because they surface as plugin bugs to
users. Dates mark when an issue was recorded.

## CloudStream app

### Video-track selection is a no-op for live HLS (2026-10-24)
Pluto TV and Plavio live HLS masters advertise variants with `BANDWIDTH` only and no `RESOLUTION`,
so ExoPlayer reports `width/height = NO_VALUE` for every variant. CloudStream then labels the video
tracks `0`, `1`, `2`… and `GeneratorPlayer.showTracksDialogue()` only applies the choice when both
dimensions are known:

```kotlin
// app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt ~1551
if (width != NO_VALUE && height != NO_VALUE) {
    player.setMaxVideoSize(width, height, currentVideo?.id)
}
```

Selecting a numbered video track and pressing Apply is therefore silently discarded. Audio selection
works because `setPreferredAudioTrack(...)` is called unconditionally.

The player is capable of switching: media3 assigns multivariant HLS ids `"0".."N"`
(`HlsPlaylistParser.java:611-619`) and `CS3IPlayer.setMaxVideoSize` overrides by id when one is
supplied (`CS3IPlayer.kt:364-383`). The fix belongs upstream: apply the override when
`currentVideo?.id != null` even if width/height are `NO_VALUE`, and optionally label unknown
variants by bitrate instead of a bare index.

Not fixable in the plugin: `ExtractorLink` carries no track metadata, and pre-resolving to one
variant would drop audio (Pluto variant playlists are video-only; audio is a separate
`EXT-X-MEDIA` group).

## Playback / link policy

- **P2P needs a torrent-capable host**: `infoHash`-only streams become `MAGNET` links and require a
  CloudStream build with the torrent server plus the per-session consent prompt.
- **No P2P opt-out setting**: the plugin always emits magnets (left out by request); CloudStream's
  consent dialog is the only gate.
- **No cross-addon dedup or liveness filtering**: duplicate mirrors and dead links are left to the
  addon servers; a dead link can appear first. `MAX_LINKS = 500` is only an emission ceiling.
- **`externalUrl` streams are skipped** (not playable through the provider pipeline).

## Live TV

- Live channels are movie-like single streams: no EPG, no seek, no recording.
- Pluto `tv/pluto` has ~1561 channels and does not support `skip`, so that row cannot paginate.
- Adding `tv` to the supported types also surfaces another catalog addon's `tv` rows (e.g.
  CNCVerse) when it is used as a catalog addon.
- Pluto HLS URLs are time- and geo-bound; they are regenerated per stream request.

## Provider / settings

- **Addon config web UIs are not embedded**: users paste configured manifest URLs. Addons whose
  `/manifest.json` is a catch-all stub will "load" even with a bad config, then silently return no
  streams (Meteor is a known example: malformed `languageFormat` → `401 invalid session`).
- **Provider category**: `TvType.Others` makes CloudStream list StreamioLink in its own category
  rather than mixing items into Movies/Series; `loadLinksTimeoutMs` is raised to 180 s and
  `StremioApi` uses 20 s metadata / 90 s stream timeouts.
- **No quick search**: `hasQuickSearch` stays `false`; the search screen submits instead of
  searching on each keystroke. Advertising the capability without implementing it throws.
- **Eager home rows**: CloudStream calls `getMainPage(1, null)` once and renders every row, so all
  catalog addons are fetched on the first home load; lazy rows are impossible through the API.
  Requests are bounded by a semaphore (6).

## Addon-side issues (not plugin bugs)

- **Cinejoy**: some streams end in upstream HTTP 500; addons that serve expired signed URLs fail
  after a while.
- **CNCVerse**: many dead links (404/connection refused/HTML), occasional literal-space URLs
  (guarded by `isPlayableStreamUrl`), and partial results until its server-side cache warms.
- **Pengu**: ~8 s redirect is paid at playback.
- **Plavio**: `/meta` is a stub for VOD ids (name = id, no videos; plugin skips it and falls back)
  and `subtitles` rejects path extras (plugin retries the plain URL). The Vidzee HLS for
  `tt0903747:1:1` is a JPEG trickplay playlist, not video.
- **Yukistreams**: `ys-v2-kisskh-*` ignores `skip` (same 10 items/page); `ys-v3-anime-seasonal`
  502s for some `skip` values and repeats page 1 for others; its `subtitles` resource returns `[]`.
- **Meteor**: catch-all `/manifest.json` hides invalid configs; a bad config yields
  `401 invalid session` for streams.
- **Pluto**: one transient non-JSON response was observed (succeeds on retry).

## Tooling

- `tools/playtest.py` is **incomplete / low priority, not maintained**: it mirrors the old mapping,
  does not build magnet/P2P links, and can report "no streams" for torrent-only addons. The plugin
  and its JVM unit tests are the source of truth.
