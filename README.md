# StreamioLink — Stremio addons inside CloudStream

A [CloudStream 3](https://github.com/recloudstream/cloudstream) plugin that turns any
[Stremio addon](https://stremio.github.io/stremio-addon-sdk/) ecosystem into a CloudStream provider.

- **1 catalog addon** powers the home page, search and metadata (series episodes included).
- **N source addons** are queried in parallel for every play and their streams are merged.
- **Subtitles** are pulled from any configured addon that declares the `subtitles` resource.
- Addons are managed in **Settings → Plugins → StreamioLink (gear icon)**.

## Install

1. CloudStream → **Settings → Plugins → Add repository**
2. Paste:

   ```
   https://raw.githubusercontent.com/Ashishsri2018/stremio-cloudstream/main/repo.json
   ```

3. Install **StreamioLink** from the repository page.

## Setup

Open **Settings → Plugins → StreamioLink** (gear icon) and fill in:

| Field | Meaning |
|---|---|
| Catalog addon URL | Provides home rows, search and metadata. Defaults to `https://v3-cinemeta.strem.io` |
| Metadata fallback addon | Used when the catalog addon has no `meta` resource. Defaults to Cinemeta |
| Source addons | Any number of addons queried for streams and subtitles |
| Use catalog addon as source | Also query the catalog addon for streams (default on) |

Paste either a base URL (`https://addon.example`) or a full manifest URL
(`https://torrentio.strem.fun/realdebrid=KEY/manifest.json`) — both are normalized automatically.

## What plays

- **Direct HTTP(S) streams**: HLS (`.m3u8`), DASH (`.mpd`) and progressive files (`mp4`/`mkv`/...).
  Content type is sniffed for extension-less URLs (typical for debrid links).
- **Debrid-configured addons** (e.g. Torrentio with `realdebrid=KEY` baked into the manifest URL)
  return direct URLs, so they work out of the box.
- **Skipped for now**: `infoHash` magnets (plain torrent addons without debrid), `externalUrl`,
  and addon configuration UIs. YouTube (`ytId`) links are opened as YouTube URLs.

## Example addons

| Addon | Role | URL |
|---|---|---|
| Cinemeta | Catalog + meta | `https://v3-cinemeta.strem.io` |
| Torrentio (Real-Debrid) | Streams | `https://torrentio.strem.fun/realdebrid=YOUR_KEY` |
| MediaFusion (Debrid) | Streams | your configured manifest URL |
| OpenSubtitles v3 | Subtitles | `https://opensubtitles-v3.strem.io` |

## Build

Everything is built by GitHub Actions (`.github/workflows/build.yml`):

- on every push to `main`, unit tests run (`./gradlew testDebugUnitTest`),
  then `./gradlew make makePluginsJson`
- the `.cs3` plugin + `plugins.json` are published to the `builds` branch

## Project structure

```
StreamioLink/
  src/main/kotlin/com/magic/stremio/
    StremioPlugin.kt          # plugin entry point, registers the provider
    StremioProvider.kt        # MainAPI implementation (home, search, load, links)
    StremioApi.kt             # HTTP layer + manifest cache
    StremioMapping.kt         # pure protocol logic (unit tested)
    StremioModels.kt          # Jackson DTOs for the Stremio protocol
    StremioSettings.kt        # persisted settings
    StremioSettingsDialog.kt  # programmatic settings UI
  src/test/kotlin/com/magic/stremio/  # JVM unit tests for the pure logic
```
