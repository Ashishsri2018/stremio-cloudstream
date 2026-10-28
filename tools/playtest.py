#!/usr/bin/env python3
"""Playability test for Stremio addons used with the StreamioLink CloudStream plugin.

STATUS: INCOMPLETE / LOW PRIORITY — do not rely on this tool.
It is a best-effort diagnostic that is not maintained. In particular it still
mirrors the *old* stream mapping: it never builds magnet/P2P links and knows
nothing about the CloudStream torrent path, so its verdicts can be wrong or
misleading (e.g. it reports "no streams" for torrent-only addons). The plugin and
its JVM unit tests are the source of truth; treat this script as optional and
unimportant.

Given an addon base URL this fetches the manifest, resolves a movie/series (or a
catalog item), builds stream candidates the way `StremioMapping` used to
(URL normalisation, User-Agent fallback, Referer, HLS/DASH detection, quality),
then probes every link and reports which ones actually play.

It is dependency-free (Python 3 stdlib only).

Usage:
    python3 tools/playtest.py <addon-url> --manifest
    python3 tools/playtest.py <addon-url> --id tt0111161 --type movie
    python3 tools/playtest.py <addon-url> --search "shawshank"
    python3 tools/playtest.py <addon-url> --catalog other/cnc_TamilUltra_tv --play
    python3 tools/playtest.py <addon-url> --id tt0903747:1:1 --type series --json
"""

from __future__ import annotations

import argparse
import json
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

# ---------------------------------------------------------------------------
# Constants mirrored from StremioMapping.kt / StremioApi.kt / StremioProvider.kt
# ---------------------------------------------------------------------------

DEFAULT_STREAM_USER_AGENT = "okhttp/4.12.0"
SUPPORTED_MEDIA_TYPES = {
    "movie", "series", "anime", "anime.movie", "anime.series", "tv", "live", "other", "music",
    "radio",
}
DEFAULT_TIMEOUT_S = 20.0
STREAM_TIMEOUT_S = 90.0
PROBE_TIMEOUT_S = 20.0
PROBE_RANGE = "bytes=0-131071"
MAX_PROBE_BYTES = 131072

_manifest_suffix_re = re.compile(r"/manifest\.json/?$", re.IGNORECASE)
_year_re = re.compile(r"\b(19|20)\d{2}\b")
_whitespace_re = re.compile(r"\s+")

_quality_patterns = [
    (2160, re.compile(r"(?<![0-9])(2160p|4k|uhd)(?![0-9a-z])")),
    (1440, re.compile(r"(?<![0-9])1440p?(?![0-9a-z])")),
    (1080, re.compile(r"(?<![0-9])(1080p?|fhd|fullhd|full\shd)(?![0-9a-z])")),
    (720, re.compile(r"(?<![0-9])(720p?|hd)(?![0-9a-z])")),
    (480, re.compile(r"(?<![0-9])480p?(?![0-9a-z])")),
    (360, re.compile(r"(?<![0-9])360p?(?![0-9a-z])")),
    (240, re.compile(r"(?<![0-9])240p?(?![0-9a-z])")),
    (144, re.compile(r"(?<![0-9])144p(?![0-9a-z])")),
]

_MEDIA_CONTENT_HINTS = (
    "video/",
    "audio/",
    "mpegurl",
    "dash+xml",
    "application/octet-stream",
    "application/x-matroska",
)
_HTML_CONTENT_HINTS = ("text/html", "application/xhtml", "text/plain", "application/xml")


# ---------------------------------------------------------------------------
# StremioMapping equivalents
# ---------------------------------------------------------------------------


def normalize_addon_url(raw: str | None) -> str | None:
    if not raw:
        return None
    url = raw.strip()
    if not url:
        return None
    if not url.lower().startswith(("http://", "https://")):
        if "." not in url:
            return None
        url = "https://" + url
    url = _manifest_suffix_re.sub("", url)
    url = url.rstrip("/")
    return url or None


def encode_query_value(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def build_catalog_url(base: str, ctype: str, cid: str, extra: str | None = None) -> str:
    extra_segment = f"/{extra}" if extra and extra.strip() else ""
    return f"{base.rstrip('/')}/catalog/{ctype}/{cid}{extra_segment}.json"


def build_stream_url(base: str, ctype: str, sid: str) -> str:
    return f"{base.rstrip('/')}/stream/{ctype}/{sid}.json"


def guess_stream_kind(url: str, filename: str | None = None) -> str:
    path = url.split("?", 1)[0].split("#", 1)[0].lower()
    name = (filename or "").lower()
    if ".m3u8" in path or ".m3u8" in name:
        return "HLS"
    if ".mpd" in path or ".mpd" in name:
        return "DASH"
    return "VIDEO"


def parse_quality(*texts: str | None) -> int:
    haystack = " ".join(t for t in texts if t).lower()
    if not haystack.strip():
        return 0
    for quality, regex in _quality_patterns:
        if regex.search(haystack):
            return quality
    return 0


def extract_referer(headers: dict[str, str]) -> str:
    for key, value in headers.items():
        if key.lower() in ("referer", "referrer"):
            return value
    return ""


def request_headers_for(stream: dict) -> dict[str, str]:
    provided = dict(((stream.get("behaviorHints") or {}).get("proxyHeaders") or {}).get("request") or {})
    if any(k.lower() == "user-agent" for k in provided):
        return provided
    provided["User-Agent"] = DEFAULT_STREAM_USER_AGENT
    return provided


def playback_url_for(stream: dict) -> str | None:
    url = (stream.get("url") or "").strip()
    if url:
        return url
    yt = (stream.get("ytId") or "").strip()
    if yt:
        return f"https://www.youtube.com/watch?v={yt}"
    return None


def stream_display_name(stream: dict) -> str | None:
    name = (stream.get("name") or "").strip() or None
    title = (stream.get("title") or "").strip() or None
    filename = (((stream.get("behaviorHints") or {}).get("filename")) or "").strip() or None
    if name and title and not title.startswith(name):
        combined = f"{name} - {title}"
    elif title:
        combined = title
    elif name:
        combined = name
    elif filename:
        combined = filename
    else:
        combined = (stream.get("description") or "").strip() or None
    if combined is None:
        return None
    return _whitespace_re.sub(" ", combined)[:180]


def year_from_release_info(release_info: str | None) -> int | None:
    if not release_info:
        return None
    match = _year_re.search(release_info)
    return int(match.group(0)) if match else None


def resource_names(manifest: dict) -> set[str]:
    names = set()
    for node in manifest.get("resources") or []:
        if isinstance(node, str):
            names.add(node)
        elif isinstance(node, dict) and node.get("name"):
            names.add(node["name"])
    return names


def resource_node(manifest: dict, resource: str):
    for node in manifest.get("resources") or []:
        if isinstance(node, dict) and (node.get("name") or "").lower() == resource.lower():
            return node
    return None


def resource_types_for(manifest: dict, resource: str) -> list[str]:
    node = resource_node(manifest, resource)
    if node and isinstance(node.get("types"), list) and node["types"]:
        return [t for t in node["types"] if isinstance(t, str)]
    return [t for t in (manifest.get("types") or []) if isinstance(t, str)]


def resource_id_prefixes_for(manifest: dict, resource: str) -> list[str]:
    node = resource_node(manifest, resource)
    if node and isinstance(node.get("idPrefixes"), list) and node["idPrefixes"]:
        return [p for p in node["idPrefixes"] if isinstance(p, str)]
    return [p for p in (manifest.get("idPrefixes") or []) if isinstance(p, str)]


def id_matches_prefixes(stream_id: str, prefixes: list[str]) -> bool:
    return not prefixes or any(stream_id.startswith(p) for p in prefixes)


def catalog_can_load_home(catalog: dict) -> bool:
    if (catalog.get("type") or "").lower() not in SUPPORTED_MEDIA_TYPES:
        return False
    if not catalog.get("id"):
        return False
    if any(e.get("isRequired") is True for e in catalog.get("extra") or []):
        return False
    if catalog.get("extraRequired"):
        return False
    return True


def catalog_supports_search(catalog: dict) -> bool:
    if (catalog.get("type") or "").lower() not in SUPPORTED_MEDIA_TYPES:
        return False
    if not catalog.get("id"):
        return False
    names = {(e.get("name") or "").lower() for e in catalog.get("extra") or []}
    names |= {s.lower() for s in catalog.get("extraSupported") or []}
    return "search" in names


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


class Fetcher:
    def __init__(self, timeout: float, insecure: bool):
        self.timeout = timeout
        self.context = ssl._create_unverified_context() if insecure else None

    @staticmethod
    def _sanitize(url: str) -> str:
        """Percent-encode characters (e.g. spaces) that urllib rejects but OkHttp encodes."""
        parts = urllib.parse.urlsplit(url)
        path = urllib.parse.quote(parts.path, safe="/%:@!$&'()*+,;=~-._")
        query = urllib.parse.quote(parts.query, safe="=&%:@!$'()*+./?~-._")
        return urllib.parse.urlunsplit((parts.scheme, parts.netloc, path, query, parts.fragment))

    def _open(self, url: str, headers: dict[str, str], timeout: float):
        request = urllib.request.Request(self._sanitize(url), headers=headers, method="GET")
        return urllib.request.urlopen(request, timeout=timeout, context=self.context)

    def get(self, url: str, headers: dict[str, str] | None = None, timeout: float | None = None, read_bytes: int | None = None):
        """Return (status, content_type, body_bytes). Raises on network failure."""
        response = self._open(url, headers or {}, timeout or self.timeout)
        status = getattr(response, "status", response.getcode())
        content_type = (response.headers.get("Content-Type") or "").split(";")[0].strip().lower()
        body = response.read(read_bytes) if read_bytes is not None else response.read()
        response.close()
        return status, content_type, body

    def get_text(self, url: str, headers: dict[str, str] | None = None, timeout: float | None = None) -> str | None:
        try:
            _, _, body = self.get(url, headers, timeout)
            return body.decode("utf-8", "replace")
        except Exception:
            return None

    def get_json(self, url: str, timeout: float | None = None):
        text = self.get_text(url, timeout=timeout)
        if text is None:
            return None, f"request failed: {url}"
        try:
            return json.loads(text), None
        except json.JSONDecodeError as exc:
            return None, f"invalid JSON from {url}: {exc}"


# ---------------------------------------------------------------------------
# Probing
# ---------------------------------------------------------------------------

PLAYABLE = "PLAYABLE"
NOT_MEDIA = "NOT-MEDIA"
DEAD = "DEAD"


def probe_stream(fetcher: Fetcher, url: str, headers: dict[str, str], kind: str, deep: bool) -> dict:
    result = {"verdict": DEAD, "status": None, "content_type": "", "note": ""}
    probe_headers = dict(headers)
    probe_headers["Range"] = PROBE_RANGE
    try:
        status, content_type, body = fetcher.get(url, probe_headers, timeout=PROBE_TIMEOUT_S, read_bytes=MAX_PROBE_BYTES)
    except urllib.error.HTTPError as exc:
        result["status"] = exc.code
        result["content_type"] = (exc.headers.get("Content-Type") or "").split(";")[0].strip().lower()
        result["note"] = "http error"
        return result
    except Exception as exc:  # timeout, dns, tls, reset...
        result["note"] = type(exc).__name__
        return result

    result["status"] = status
    result["content_type"] = content_type

    if status >= 400:
        result["note"] = "http error"
        return result

    if any(h in content_type for h in _HTML_CONTENT_HINTS):
        text = body[:2048].decode("utf-8", "replace").lstrip().lower()
        if "<html" in text or "<?xml" in text or "<error" in text:
            result["verdict"] = NOT_MEDIA
            result["note"] = "html/xml body, not a media stream"
            return result

    looks_media = (
        any(h in content_type for h in _MEDIA_CONTENT_HINTS)
        or content_type == ""
        or kind in ("HLS", "DASH")
    )
    if looks_media:
        result["verdict"] = PLAYABLE

    if deep and result["verdict"] == PLAYABLE and kind == "HLS":
        override, note = _probe_hls_segment(fetcher, url, headers, body)
        result["note"] = note
        if override:
            result["verdict"] = override
    return result


def _probe_hls_segment(fetcher: Fetcher, playlist_url: str, headers: dict[str, str], body: bytes) -> tuple[str | None, str]:
    """Fetch the first HLS segment and return (verdict override or None, note)."""
    text = body.decode("utf-8", "replace")
    if "#EXTM3U" not in text:
        return NOT_MEDIA, "not an m3u8 playlist"
    segment = None
    lines = [line.strip() for line in text.splitlines()]
    for i, line in enumerate(lines):
        if line.startswith("#EXTINF"):
            nxt = next((candidate for candidate in lines[i + 1:] if candidate and not candidate.startswith("#")), None)
            if nxt:
                segment = nxt
                break
    if not segment:
        segment = next((line for line in lines if line and not line.startswith("#")), None)
    if not segment:
        return NOT_MEDIA, "no segment entries"
    segment_url = urllib.parse.urljoin(playlist_url, segment)
    try:
        status, content_type, chunk = fetcher.get(segment_url, headers, timeout=PROBE_TIMEOUT_S, read_bytes=32768)
        if status >= 400 or not chunk:
            return DEAD, f"segment {status} unusable"
        if content_type.startswith("image/"):
            return NOT_MEDIA, f"HLS segments are images ({content_type}), not video (trickplay)"
        return None, f"segment {status} ok ({content_type or 'binary'})"
    except Exception as exc:
        return DEAD, f"segment failed: {type(exc).__name__}"


# ---------------------------------------------------------------------------
# Resolution helpers
# ---------------------------------------------------------------------------


def fetch_manifest(fetcher: Fetcher, base: str):
    data, err = fetcher.get_json(base + "/manifest.json", timeout=DEFAULT_TIMEOUT_S)
    if data is None:
        print(f"error: could not load manifest: {err}", file=sys.stderr)
        sys.exit(2)
    return data


def search_catalog(fetcher: Fetcher, base: str, catalog: dict, query: str) -> list[dict]:
    ctype = catalog.get("type")
    cid = catalog.get("id")
    url = build_catalog_url(base, ctype, cid, f"search={encode_query_value(query)}")
    data, _ = fetcher.get_json(url, timeout=DEFAULT_TIMEOUT_S)
    return (data or {}).get("metas") or []


def fetch_catalog(fetcher: Fetcher, base: str, ctype: str, cid: str, skip: int | None = None) -> list[dict]:
    extra = f"skip={skip}" if skip else None
    url = build_catalog_url(base, ctype, cid, extra)
    data, _ = fetcher.get_json(url, timeout=DEFAULT_TIMEOUT_S)
    return (data or {}).get("metas") or []


def fetch_streams(fetcher: Fetcher, base: str, ctype: str, sid: str, manifest: dict) -> list[dict]:
    if not id_matches_prefixes(sid, resource_id_prefixes_for(manifest, "stream")):
        print(f"warning: id '{sid}' does not match the addon's stream idPrefixes", file=sys.stderr)
    if ctype.lower() not in [t.lower() for t in resource_types_for(manifest, "stream")]:
        print(f"warning: type '{ctype}' is not in the addon's stream resource types", file=sys.stderr)
    url = build_stream_url(base, ctype, sid)
    data, err = fetcher.get_json(url, timeout=STREAM_TIMEOUT_S)
    if data is None:
        print(f"error: could not load streams: {err}", file=sys.stderr)
        return []
    return data.get("streams") or []


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------


def print_manifest_summary(base: str, manifest: dict) -> None:
    print(f"Addon    : {manifest.get('name')} ({manifest.get('id')}) v{manifest.get('version')}")
    print(f"Base URL : {base}")
    print(f"Types    : {', '.join(manifest.get('types') or [])}")
    print(f"Resources: {', '.join(sorted(resource_names(manifest)))}")
    print(f"Prefixes : {', '.join(manifest.get('idPrefixes') or []) or '(none)'}")
    catalogs = manifest.get("catalogs") or []
    loadable = [c for c in catalogs if catalog_can_load_home(c)]
    searchable = [c for c in catalogs if catalog_supports_search(c)]
    print()
    print(f"Catalogs : {len(catalogs)} total, {len(loadable)} loadable on home, {len(searchable)} searchable")
    if catalogs and not loadable and not searchable:
        kinds = sorted({(c.get('type') or '?') for c in catalogs})
        print(f"  NOTE: no supported catalogs (types present: {', '.join(kinds)}).")
        print("  StreamioLink browses/searches movie, series, anime, tv, music, radio and other")
        print("  catalogs, so this addon is usable as a *source* addon, not as the catalog addon.")
    for catalog in catalogs:
        tags = []
        if catalog_can_load_home(catalog):
            tags.append("home")
        if catalog_supports_search(catalog):
            tags.append("search")
        status = ",".join(tags) if tags else "skipped"
        print(f"  [{status:<11}] {catalog.get('type')}/{catalog.get('id')}  {catalog.get('name') or ''}")


def print_search_results(metas: list[dict]) -> None:
    if not metas:
        print("No search results.")
        return
    print(f"\nSearch results ({len(metas)}):")
    for i, meta in enumerate(metas):
        year = year_from_release_info(meta.get("releaseInfo"))
        suffix = f" ({year})" if year else ""
        print(f"  [{i}] {meta.get('name') or meta.get('id')}{suffix}  [{meta.get('type')}]  {meta.get('id')}")


def print_catalog_results(metas: list[dict]) -> None:
    if not metas:
        print("No catalog items.")
        return
    print(f"\nCatalog items ({len(metas)}):")
    for i, meta in enumerate(metas):
        print(f"  [{i}] {meta.get('name') or meta.get('id')}  [{meta.get('type')}]  {meta.get('id')}")


def playtest(fetcher: Fetcher, base: str, manifest: dict, ctype: str, sid: str, title: str,
             workers: int, deep: bool, min_quality: int, as_json: bool) -> int:
    if not as_json:
        print(f"\nResolving {ctype}/{sid}  ({title})")
    streams = fetch_streams(fetcher, base, ctype, sid, manifest)
    if not streams:
        print("No streams returned by the addon.")
        return 1 if not as_json else _emit_json([])

    candidates = []
    for stream in streams:
        url = playback_url_for(stream)
        if not url:
            continue
        headers = request_headers_for(stream)
        filename = (stream.get("behaviorHints") or {}).get("filename")
        kind = guess_stream_kind(url, filename)
        quality = parse_quality(stream.get("title"), stream.get("name"), filename)
        candidates.append({
            "addon": (stream.get("name") or "").strip(),
            "label": stream_display_name(stream) or "Stream",
            "url": url,
            "headers": headers,
            "referer": extract_referer(headers),
            "kind": kind,
            "quality": quality,
        })

    if min_quality:
        candidates = [c for c in candidates if c["quality"] >= min_quality] or candidates

    if not as_json:
        print(f"Built {len(candidates)} candidates; probing (workers={workers}, kind detection mirrors StremioMapping)...\n")

    def work(candidate):
        probe = probe_stream(fetcher, candidate["url"], candidate["headers"], candidate["kind"], deep)
        return candidate, probe

    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        results = list(pool.map(work, candidates))

    results.sort(key=lambda cp: cp[0]["quality"], reverse=True)
    return _emit_json(results) if as_json else _print_results(results)


def _verdict_icon(verdict: str) -> str:
    return {PLAYABLE: "PASS", NOT_MEDIA: "NOMEDIA", DEAD: "DEAD"}.get(verdict, verdict)


def _print_results(results) -> int:
    print(f"{'#':>2}  {'Q':>4}  {'KIND':<6}  {'RESULT':<7}  {'HTTP':<4}  {'CONTENT-TYPE':<28}  LABEL")
    print("-" * 120)
    playable = 0
    for i, (candidate, probe) in enumerate(results):
        if probe["verdict"] == PLAYABLE:
            playable += 1
        status = probe["status"] if probe["status"] is not None else "-"
        note = f"  ({probe['note']})" if probe["note"] else ""
        print(
            f"{i:>2}  {candidate['quality']:>4}  {candidate['kind']:<6}  "
            f"{_verdict_icon(probe['verdict']):<7}  {str(status):<4}  "
            f"{(probe['content_type'] or '-'):<28}  {candidate['label'][:60]}{note}"
        )

    print("-" * 120)
    total = len(results)
    print(f"Summary: {playable}/{total} playable, "
          f"{sum(1 for _, p in results if p['verdict'] == NOT_MEDIA)} not-media, "
          f"{sum(1 for _, p in results if p['verdict'] == DEAD)} dead.")
    if total and playable == 0:
        print("No playable links found.")
    return 0 if playable else 1


def _emit_json(results) -> int:
    payload = [
        {
            "quality": candidate["quality"],
            "kind": candidate["kind"],
            "addon": candidate["addon"],
            "label": candidate["label"],
            "url": candidate["url"],
            "referer": candidate["referer"],
            "verdict": probe["verdict"],
            "status": probe["status"],
            "content_type": probe["content_type"],
            "note": probe["note"],
        }
        for candidate, probe in results
    ]
    print(json.dumps(payload, indent=2, ensure_ascii=False))
    return 0 if any(item["verdict"] == PLAYABLE for item in payload) else 1


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Test whether a Stremio addon's streams actually play (mirrors StreamioLink's StremioMapping).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("addon_url", help="Addon base URL or full manifest.json URL")
    parser.add_argument("--manifest", action="store_true", help="Only print the manifest summary and exit")
    parser.add_argument("--id", dest="stream_id", help="Stremio id, e.g. tt0111161 or tt0903747:1:1")
    parser.add_argument("--type", choices=["movie", "series", "other", "tv"], help="Type for --id")
    parser.add_argument("--search", help="Search query; resolves the first (or --pick) result")
    parser.add_argument("--catalog", help="Catalog as type/id (e.g. other/cnc_TamilUltra_tv)")
    parser.add_argument("--pick", type=int, default=0, help="Index to playtest from search/catalog results (default 0)")
    parser.add_argument("--play", action="store_true", help="With --catalog, playtest the picked item instead of listing")
    parser.add_argument("--limit", type=int, default=20, help="Catalog listing limit (default 20)")
    parser.add_argument("--quality", type=int, default=0, help="Only probe links at/above this quality when any match")
    parser.add_argument("--workers", type=int, default=8, help="Concurrent probes (default 8)")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_S, help="Catalog/manifest timeout seconds")
    parser.add_argument("--deep", action="store_true", help="Also fetch the first HLS segment")
    parser.add_argument("--insecure", action="store_true", help="Disable TLS certificate verification")
    parser.add_argument("--json", action="store_true", help="Emit machine-readable results")
    return parser


def resolve_pick(items: list[dict], pick: int) -> tuple[str, str, str] | None:
    if not items:
        return None
    index = pick if 0 <= pick < len(items) else 0
    meta = items[index]
    return meta.get("type") or "movie", meta.get("id"), meta.get("name") or meta.get("id")


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    base = normalize_addon_url(args.addon_url)
    if not base:
        print(f"error: invalid addon URL: {args.addon_url}", file=sys.stderr)
        return 2

    fetcher = Fetcher(args.timeout, args.insecure)
    manifest = fetch_manifest(fetcher, base)
    if not args.json:
        print_manifest_summary(base, manifest)

    if args.manifest:
        return 0

    if args.stream_id:
        ctype = args.type or "movie"
        return playtest(fetcher, base, manifest, ctype, args.stream_id, args.stream_id,
                        args.workers, args.deep, args.quality, args.json)

    if args.search:
        searchable = [c for c in manifest.get("catalogs") or [] if catalog_supports_search(c)]
        if not searchable:
            print("\nThis addon exposes no movie/series catalog with search support.")
            return 1
        metas = []
        for catalog in searchable:
            metas.extend(search_catalog(fetcher, base, catalog, args.search))
        metas = list({m.get("id"): m for m in metas}.values())
        if not args.json:
            print_search_results(metas)
        target = resolve_pick(metas, args.pick)
        if not target:
            return 1
        ctype, sid, title = target
        return playtest(fetcher, base, manifest, ctype, sid, title,
                        args.workers, args.deep, args.quality, args.json)

    if args.catalog:
        if "/" not in args.catalog:
            print("error: --catalog must be type/id (e.g. other/cnc_TamilUltra_tv)", file=sys.stderr)
            return 2
        ctype, cid = args.catalog.split("/", 1)
        metas = fetch_catalog(fetcher, base, ctype, cid)
        if not args.json:
            print_catalog_results(metas[: args.limit])
        if not args.play:
            return 0
        target = resolve_pick(metas[: args.limit], args.pick)
        if not target:
            return 1
        rtype, sid, title = target
        return playtest(fetcher, base, manifest, rtype, sid, title,
                        args.workers, args.deep, args.quality, args.json)

    print("\nNothing to do: pass --id, --search or --catalog (use --manifest for a summary only).")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        sys.exit(130)
