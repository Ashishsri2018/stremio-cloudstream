# StreamioLink — Ideas for Future Upgrades

Not a roadmap; nothing here is committed to a version. Just ideas worth revisiting.

## Presets
- Bundle one catalog addon + N source addons into a named preset.
- Create/select/delete presets in the settings dialog; applying one swaps the catalog and sources only.
- Would let users keep separate setups (e.g. "Anime", "Movies", "Debrid") without re-typing manifest URLs.

## Raise total link cap to 500
- `StremioProvider.MAX_LINKS` is currently 120 (a single total ceiling shared across addons; the
  CloudStream app itself imposes no cap — `RepoLinkGenerator` only URL-dedups and stores links in a
  `PersistentSet`).
- Raise it to **500** for the next update so users with many source addons (up to ~10) get the links
  each addon's server returns.
- Do **not** add a per-addon cap — per-addon counts are the addon servers' decision.
- This is only a forwarding ceiling: addons are already fully fetched regardless, so no extra network.
- (Decided 2026-10-16; deliberately left out of the v9 efficiency pass.)
