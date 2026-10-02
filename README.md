# Off-Grid Mesh Workspace (working title)

Android-only shared workspace for teams with no cell service: mesh text, pins on offline maps, shared
checklists and a status board, synced phone-to-phone. No server, no accounts.

Spec: `docs/Off-Grid_Mesh_Workspace_Build_Guide.docx` (plain-text copy: `docs/build-guide.txt`).
Owner decisions: `docs/decisions.md`.

## Status

| Milestone | Scope | State |
|---|---|---|
| M1 | Nearby transport behind `MeshTransport`, foreground `MeshService`, node list | Code done; single-device checks pass. **Two-device acceptance not run yet.** |
| M2 | Chat, store-and-forward, TTL relay, message states | — |
| M3 | Offline maps (MapLibre + MBTiles) + pins | — |
| M4 | Checklists, status board, passphrase encryption, paywall | — |
| M5 | Battery/Doze tuning, billing edge cases, release | — |

## Layout

```
transport/  MeshTransport interface, NearbyTransport (Play Services), PeerInfo advert codec
mesh/       MeshCore (connection policy, presence; transport-agnostic), MeshService (FGS), MeshConfig, permissions
sync/       Envelope + Presence wire format (kotlinx ProtoBuf — frozen; field numbers are an API)
data/       Room (Node), DataStore identity (device UUID + display name)
ui/         Compose: onboarding, Mesh home, settings; Chat/Map/Tasks/Status are placeholders
```

Connection policy: every phone advertises and discovers (`P2P_CLUSTER`). Only the lower deviceId requests
the connection; the higher one falls back after `CONNECT_FALLBACK_MS` in case discovery is one-sided. On
connect, both sides send a presence beacon (name, battery). All timings live in `MeshConfig`.

## Build & test

```
./gradlew assembleDebug testDebugUnitTest
```

JVM tests cover codecs and MeshCore against a simulated radio (`FakeAir`). They check policy, not radios —
emulators can't test this app; acceptance needs 3+ physical phones (§14).
