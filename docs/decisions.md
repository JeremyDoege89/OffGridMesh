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
  the next inventory exchange. Unknown object types are stored and relayed, so older builds carry newer data.
- **TTL semantics:** author sends ttl = setting; each relay forwards with ttl−1 and drops at 0 → TTL N = N hops.
- **Inventory is capped** at the newest 2,000 relayable records (pages of 400 to fit Nearby's 32 KB BYTES
  payload). Older records still relay live but aren't backfilled.
- **Relayed** = the payload was handed to a connected peer's link (or any receipt came back).
  **Delivered** = every member seen in the last 10 min when the message first left this phone has ACKed.
  Receipts from later joiners are kept but don't count.
- **ACK cost:** every receiver floods one receipt per message (≈ N² small envelopes per message for N nodes).
  Fine at ≤ 12 nodes; revisit in the M4 8–12 node stress test.
- **Seen-cache cleanup** runs when the mesh starts, not in a nightly WorkManager job yet (M5).
