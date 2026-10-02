# Off-Grid Mesh Workspace (working title)

Android-only shared workspace for teams with no cell service: mesh text, pins on offline maps, shared
checklists and a status board, synced phone-to-phone. No server, no accounts.

Spec: `docs/Off-Grid_Mesh_Workspace_Build_Guide.docx` (plain-text copy: `docs/build-guide.txt`).
Owner decisions: `docs/decisions.md`.

## Status

| Milestone | Scope | State |
|---|---|---|
| M1 | Nearby transport behind `MeshTransport`, foreground `MeshService`, node list | Done; two phones connected in the field (formal timings not measured). |
| M2 | Chat, store-and-forward, TTL relay, message states | 2-phone field test passed (queued→delivered, receipts). **3-phone A–B–C relay not run.** |
| M3 | Offline maps (MapLibre + MBTiles) + pins | Done on one phone: pack download, offline render, GPS pin. Pin sync to a 2nd phone + true airplane-mode run not yet done. |
| M4 | Checklists, status board, passphrase encryption, paywall | — |
| M5 | Battery/Doze tuning, billing edge cases, release | — |

## Layout

```
transport/  MeshTransport interface, NearbyTransport (Play Services), PeerInfo advert codec
mesh/       MeshCore (connection policy, presence; transport-agnostic), MeshService (FGS), MeshConfig, permissions
sync/       Envelope wire format (kotlinx ProtoBuf — frozen; field numbers are an API), SyncEngine (gossip)
maps/       PMTiles reader + area extractor, MBTiles writer, MapPackManager, bundled style, grid/GeoJSON helpers
data/       Room (node, gossip, message, message_recipient, pin, map_pack, clamped_edit), DataStore, repositories
ui/         Compose: onboarding (incl. forced map step), Mesh, Chat, Map (+ packs, area download), settings
```

Connection policy: every phone advertises and discovers (`P2P_CLUSTER`). Only the lower deviceId requests
the connection; the higher one falls back after `CONNECT_FALLBACK_MS` in case discovery is one-sided. On
connect, SyncEngine takes over. All timings live in `MeshConfig`.

Sync (§5.6): every relayable object is a row in `gossip`, which doubles as the persistent relay queue
and the 7-day dedup memory. On connect, peers swap inventories of what they can relay and request what
they lack. New objects flood to every other connected peer; a relay sends `ttl−1`, so TTL 3 = at most
three radio hops. Receivers ACK messages; the author marks a message Delivered once every member known
when it first left the phone has ACKed. Presence beacons (60 s) ride the same path, giving hop counts.

Maps (§6): a pack is an MBTiles file cut on the phone from a planet-wide PMTiles archive over HTTPS range
requests: frame an area, "Check download size" reads only the directories (exact size, ~1 s), then the
tiles download in coalesced ranges. Sample sizes for z0–15: 10 km city box ≈ 5 MB, 40 km city box ≈ 18 MB,
40 km rural box ≈ 1 MB. Rendering is fully offline: `mbtiles://` source, bundled Noto Sans glyphs
(OFL, `assets/glyphs`), no sprites. Pins/nodes/grid are GeoJSON layers, never tiles. No pack → grid fallback.

## Build & test

```
./gradlew assembleDebug testDebugUnitTest
```

JVM tests cover codecs, MeshCore and SyncEngine (A–B–C lines, TTL, dedup, kill-and-relaunch) against a
simulated radio (`FakeAir`). Room SQL + the v1→v2 migration run on a device:
`adb shell am instrument -w dev.offgridmesh.test/androidx.test.runner.AndroidJUnitRunner`. They check policy, not radios —
emulators can't test this app; acceptance needs 3+ physical phones (§14).
