# Decision log (Build Guide §15)

Development proceeds on the guide's defaults until the owner rules. Silence is not a ruling.

| # | Decision | Current default | Owner ruling |
|---|---|---|---|
| D1 | Transport: Nearby Connections (needs Play Services) vs DIY BLE first | Nearby for MVP, DIY behind `MeshTransport` | _pending — decide before M2_ |
| D2 | Final product name | Working title "Off-Grid Mesh", package `dev.offgridmesh` | _pending — package id should change early_ |
| D3 | Pro price | $9.99 one-time | _pending_ |
| D4 | Encryption default | Team passphrase; open mesh visibly distinct | _pending — decide before M4_ |

## Engineering decisions made in M1

- **Wire format:** kotlinx.serialization ProtoBuf (Appendix B "pick one in M1 and freeze it"). Pinned by
  `CodecTest.envelopeWireFormatIsFrozen`.
- **Nearby strategy:** `P2P_CLUSTER` (M-to-N). Relay is app-level (M2).
- **Advert payload:** `[v1][uuid 16B][name ≤32B utf-8]`; full name arrives in presence.

## Engineering decisions made in M2

- **Gossip table is the relay queue.** No separate queue: anything with `outTtl > 0` a peer lacks is sent on
  the next inventory exchange. A new object type in a later build is NOT relayed by older builds (unknown enum fails to decode): teams should update together.
- **TTL semantics:** author sends ttl = setting; each relay forwards with ttl−1 and drops at 0 → TTL N = N hops.
- **Inventory is capped** at the newest 2,000 relayable records (pages of 400 to fit Nearby's 32 KB BYTES
  payload). Older records still relay live but aren't backfilled.
- **Relayed** = the payload was handed to a connected peer's link (or any receipt came back).
  **Delivered** = every member seen in the last 10 min when the message first left this phone has ACKed.
  Receipts from later joiners are kept but don't count.
- **ACK cost:** every receiver floods one receipt per message (≈ N² small envelopes per message for N nodes).
  Fine at ≤ 12 nodes; revisit in the M4 8–12 node stress test.
- **Seen-cache cleanup** runs when the mesh starts, not in a nightly WorkManager job yet (M5).

## Open decision added in M3

| # | Decision | Current default | Owner ruling |
|---|---|---|---|
| D5 | Where map packs come from (production) | App cuts the framed area from the newest **Protomaps public daily planet build** (`build.protomaps.com`). Fine for development; Protomaps doesn't offer it as a production backend. Production: host one planet `.pmtiles` (~138 GB, static file with range requests, e.g. Cloudflare R2) and set `MapSource.PLANET_URL`. No app changes needed. | _pending — before release_ |

## Engineering decisions made in M3

- **Packs are cut on the phone** (PMTiles → MBTiles) instead of prebuilt region files: any area, exact
  size shown before download, no build pipeline. Spec §6's tilemaker/planetiler step is unnecessary.
- **Map schema:** Protomaps basemap v4 layers; style is bundled (`MapStyle`), glyphs in assets, light palette.
- **Shared state is not hop-limited.** Pins (and in M4 checklists/status) replicate to every member through
  inventories; TTL limits only chat, receipts and presence. §5.6 says relay-with-TTL applies to messages.
- **Pins are last-write-wins** on (updatedAtMs, editorId). Deletes are tombstones relayed 30 days.
  Local edits use max(now, previous+1) so they always supersede what the phone shows.
- **Clock skew:** versions > 24 h ahead are clamped *and remembered*, so the skewed phone's re-offers can't
  win again; the Mesh screen warns when a teammate's clock is > 10 min off. Residual: the skewed phone
  itself keeps its own version until its clock is fixed.
- **Location sharing is off by default**; toggle on the Map screen and in Settings.
- **Pack download runs in an app-scoped coroutine** with the screen kept on, not a WorkManager job (M5).

## Engineering decisions made in M4

- **Crypto library:** platform JCA (PBKDF2WithHmacSHA256 + AES/GCM/NoPadding, API 26+) instead of Tink or
  Lazysodium — standard primitives, no hand-rolled crypto, zero extra dependency. Construction in README.
- **Encryption at the link layer:** the whole frame (headers included) is sealed per hop, so non-members
  learn nothing but frame sizes. Relays re-seal because they change ttl/relays.
- **Fixed salt** (every member must derive the same key from the passphrase alone): passphrase strength is
  the protection; onboarding asks for 8+ characters and recommends four random words.
- **Team tag in adverts** (PeerInfo v2): phones only connect within a team; M1–M3 builds (v1 adverts)
  count as "different team". Every phone needs this build.
- **Free node cap = roster display cap**, not a radio cap: members beyond 8 still relay and their messages,
  pins and help calls arrive; the lists show "+N more". Help calls are never hidden. Auto paywall once per
  run when a 9th member is active, deferred while anyone has an active help call.
- **Help calls** may carry the caller's position (opt-in per call), shown as a red marker on teammates' maps.
- **Billing:** ported from MaintenanceTracker (PBL 9.1, cache-first, Play authoritative, acks on every pass).
  Product id `pro_unlock` must be created in Play Console.
