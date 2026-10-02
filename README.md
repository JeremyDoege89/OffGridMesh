# Off-Grid Mesh Workspace (working title)

Android-only shared workspace for teams with no cell service: mesh text, pins on offline maps, shared
checklists and a status board, synced phone-to-phone. No server, no accounts.

Spec: `docs/Off-Grid_Mesh_Workspace_Build_Guide.docx` (plain-text copy: `docs/build-guide.txt`).
Owner decisions: `docs/decisions.md`.

## Status

| Milestone | Scope | State |
|---|---|---|
| M1 | Nearby transport behind `MeshTransport`, foreground `MeshService`, node list | Done; two phones connected in the field (formal timings not measured). |
| M2 | Chat, store-and-forward, TTL relay, message states | Code done; JVM + on-device DAO tests pass. **3-phone A–B–C acceptance not run.** |
| M3 | Offline maps (MapLibre + MBTiles) + pins | — |
| M4 | Checklists, status board, passphrase encryption, paywall | — |
| M5 | Battery/Doze tuning, billing edge cases, release | — |

## Layout

```
transport/  MeshTransport interface, NearbyTransport (Play Services), PeerInfo advert codec
mesh/       MeshCore (connection policy, presence; transport-agnostic), MeshService (FGS), MeshConfig, permissions
sync/       Envelope wire format (kotlinx ProtoBuf — frozen; field numbers are an API), SyncEngine (gossip)
data/       Room (node, gossip, message, message_recipient), DataStore identity + settings, ChatRepository
ui/         Compose: onboarding, Mesh home, Chat, settings; Map/Tasks/Status are placeholders
```

Connection policy: every phone advertises and discovers (`P2P_CLUSTER`). Only the lower deviceId requests
the connection; the higher one falls back after `CONNECT_FALLBACK_MS` in case discovery is one-sided. On
connect, SyncEngine takes over. All timings live in `MeshConfig`.

Sync (§5.6): every relayable object is a row in `gossip`, which doubles as the persistent relay queue
and the 7-day dedup memory. On connect, peers swap inventories of what they can relay and request what
they lack. New objects flood to every other connected peer; a relay sends `ttl−1`, so TTL 3 = at most
three radio hops. Receivers ACK messages; the author marks a message Delivered once every member known
when it first left the phone has ACKed. Presence beacons (60 s) ride the same path, giving hop counts.

## Build & test

```
./gradlew assembleDebug testDebugUnitTest
```

JVM tests cover codecs, MeshCore and SyncEngine (A–B–C lines, TTL, dedup, kill-and-relaunch) against a
simulated radio (`FakeAir`). Room SQL + the v1→v2 migration run on a device:
`adb shell am instrument -w dev.offgridmesh.test/androidx.test.runner.AndroidJUnitRunner`. They check policy, not radios —
emulators can't test this app; acceptance needs 3+ physical phones (§14).
