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
