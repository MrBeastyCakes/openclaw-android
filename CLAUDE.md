# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

```powershell
.\gradlew.bat assembleDebug          # build debug APK
.\gradlew.bat installDebug           # install to connected device/emulator
.\gradlew.bat :app:lint              # Android lint
.\gradlew.bat :app:compileDebugKotlin
```

The project has no unit or instrumented tests wired up yet — the `test`/`androidTest` source sets do not exist.

Toolchain pinned in `build.gradle.kts` / `app/build.gradle.kts`:
- AGP 8.11.1, Kotlin 2.2.20, Compose Compiler plugin 2.2.20
- `compileSdk` / `targetSdk` = 36, `minSdk` = 26
- JVM target 17 (Java 17 required on PATH for Gradle)

## Gateway connection targets

The app talks to a local OpenClaw gateway over WebSocket. Three reachability modes (see `README.md`):
- Emulator: `ws://10.0.2.2:18789`
- USB physical device: `adb reverse tcp:18789 tcp:18789`, then `ws://127.0.0.1:18789`
- Wi-Fi physical device: set `bind: 0.0.0.0` in `~\.openclaw\openclaw.json`, then `ws://<lan-ip>:18789`

The shared bootstrap token lives in `C:\Users\t8rto\.openclaw\openclaw.json` and is pasted into the app once. `AndroidManifest.xml` enables `usesCleartextTraffic` because these are local `ws://` endpoints.

## Architecture

Single-module Android app (`:app`), package `com.openclaw.androidclient`. Compose-only UI, no Hilt/DI framework — the `ChatRepository` is instantiated directly by `ChatViewModelFactory`.

Layering (read in this order):

1. **`data/model/GatewayModels.kt`** — the contract. Defines `ChatUiState`, `ConnectionConfig`, `GatewayEvent` (sealed: `Challenge`, `Chat`, `SessionTool`, `Unknown`), `GatewayResponse`, and all JSON frame builders (`buildConnectParams`, `buildHistoryParams`, `buildSendParams`). Also contains the **v3 device-auth payload canonicalization** (`buildDeviceAuthPayloadV3`) — the pipe-joined `v3|deviceId|clientId|mode|role|scopes|signedAt|token|nonce|platform|deviceFamily` string that the gateway re-derives and verifies. Change this and you break auth.

2. **`data/auth/DeviceAuthStore.kt`** — generates and persists a per-install Ed25519 keypair in `SharedPreferences` (`openclaw_client`). `deviceId` is `sha256(rawPublicKey)` hex. The raw 32-byte public key is extracted from the Java `SPKI` encoding by stripping the fixed `302a300506032b6570032100` prefix. Per-role device tokens (currently only `operator`) are stored under `device_auth_store` keyed by `deviceId` + `role`. `signDevicePayload` signs with `Ed25519` via JCA (requires API 33+ system or a JCA provider that supports it — the `minSdk` is 26, so verify on older devices before claiming support).

3. **`data/network/OpenClawWebSocketClient.kt`** — OkHttp `WebSocketListener` wrapper. Two frame shapes: `{type:"req",id,method,params}` requests matched by incrementing id to `CompletableDeferred<GatewayResponse>` in `pendingRequests`, and `{type:"event",event,payload}` events fanned out through the `onEvent` callback. The `ok` field is parsed as a **string `"true"`** (not a JSON boolean) — preserve this if the gateway protocol is updated. All pending requests are failed with `disconnected`/`connection_failed` on socket close.

4. **`data/repo/ChatRepository.kt`** — the state machine. Owns four `StateFlow`s (`messages`, `status`, `isConnected`, `connectionStatus`) consumed by the ViewModel. Connection flow:
   - `connect()` opens the socket → gateway emits `connect.challenge` with a nonce
   - `handleChallenge()` signs the v3 payload and sends `connect`. If a stored device token exists for `(deviceId, operator)` it is used as both `authToken` and `deviceToken`; otherwise the pasted bootstrap token is used and `deviceToken` is null (first-pairing path).
   - Successful `connect` response may contain `auth.deviceToken` in the hello payload — `extractHelloAuth` pulls it out and `DeviceAuthStore.storeDeviceToken` persists it for subsequent reconnects.
   - A `token mismatch` error clears the stored device token so the next connect falls back to bootstrap. A pairing error (`error.details.requestId`) surfaces the request id for out-of-band approval.
   - After connect: `chat.history` loads the session, `chat.send` returns a `runId`, and streaming `chat` events (`state` ∈ `delta`|`final`|`error`|`aborted`) are merged into a placeholder assistant message looked up via `streamingIdsByRun[runId]`.

5. **`ui/ChatViewModel.kt` + `ui/ChatScreen.kt`** — thin Compose layer. `ChatViewModel` mirrors repository flows into `ChatUiState`; `ChatScreen` is a single scaffold with `ConnectionPanel` (URL/token/sessionKey + Connect/Disconnect) on top, a `LazyColumn` of `MessageBubble`s, and a `Composer` bottom bar. `MainActivity` wires it up via `ChatViewModelFactory(applicationContext)`.

## Invariants to preserve when editing

- The v3 auth payload format in `buildDeviceAuthPayloadV3` must match the gateway's verifier byte-for-byte. Scope ordering is handled by `normalizeDeviceScopes` (dedup, expand `operator.admin` → `+read,+write`, then `sorted()`); don't change ordering without the gateway side.
- `protocol` is pinned to `minProtocol: 3` / `maxProtocol: 3` in `buildConnectParams`.
- The default session key is `agent:main:main` and the default requested scope set is `operator.admin` (which expands).
- Config and device-auth data both live in `SharedPreferences("openclaw_client")` — same file, different keys (`gateway_url`, `gateway_token`, `session_key`, `device_identity_*`, `device_auth_store`).
- `streamingIdsByRun` is a `ConcurrentHashMap` because `chat` events arrive on the OkHttp dispatcher thread via `scope.launch(Dispatchers.IO)` in the socket client; keep it thread-safe.
