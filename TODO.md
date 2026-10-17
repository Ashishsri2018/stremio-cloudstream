# StreamioLink — Ideas for Future Upgrades

Not a roadmap; nothing here is committed to a version. Just ideas worth revisiting.

## Presets
- Bundle one catalog addon + N source addons into a named preset.
- Create/select/delete presets in the settings dialog; applying one swaps the catalog and sources only.
- Would let users keep separate setups (e.g. "Anime", "Movies", "Debrid") without re-typing manifest URLs.

## Raise total link cap to 500 (Done)
- [x] `StremioProvider.MAX_LINKS` raised 120 → **500** (a single total ceiling shared across addons;
  the CloudStream app itself imposes no cap — `RepoLinkGenerator` only URL-dedups and stores links in a
  `PersistentSet`).
- [x] No per-addon cap — per-addon counts are the addon servers' decision.
- [x] Forwarding ceiling only: addons are already fully fetched regardless, so no extra network.
- (Decided 2026-10-16; deliberately left out of the v9 efficiency pass. Done 2026-10-17.)
