# OpenClaw Android Client

Minimal native Android chat client for a local OpenClaw install.

## First slice

- Connects to the local OpenClaw gateway over WebSocket
- Waits for `connect.challenge`, then sends a signed device-aware `connect`
- Loads `chat.history` for `agent:main:main`
- Sends `chat.send`
- Renders streaming `chat` delta/final events
- Persists the issued device token for later scoped reconnects

## Connection options

### Emulator
- Gateway URL: `ws://10.0.2.2:18789`

### Physical device over USB (no Wi-Fi needed)
- Run `adb reverse tcp:18789 tcp:18789`
- Set app URL to `ws://127.0.0.1:18789`

### Physical device over Wi-Fi
- Ensure the gateway `bind` in `openclaw.json` is set to `0.0.0.0` (not `loopback`)
- Find your PC's local IP (e.g. `192.168.92.79`)
- Set app URL to `ws://192.168.92.79:18789`

All paths use the same WebSocket protocol.

## Token

Paste the shared gateway token from `C:\Users\t8rto\.openclaw\openclaw.json` into the app once.

On first signed connect, OpenClaw can silently pair the local Android device and issue a per-device operator token. The app stores that device token and reuses it on later reconnects.

## Build

```powershell
.\gradlew.bat assembleDebug
```
