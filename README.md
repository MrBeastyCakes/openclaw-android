# OpenClaw Android Client

Minimal native Android chat client for a local OpenClaw install.

## First slice

- Connects to the local OpenClaw gateway over WebSocket
- Waits for `connect.challenge`, then sends a signed device-aware `connect`
- Loads `chat.history` for `agent:main:main`
- Sends `chat.send`
- Renders streaming `chat` delta/final events
- Persists the issued device token for later scoped reconnects

## Defaults

- Gateway URL: `ws://10.0.2.2:18789`
- Session key: `agent:main:main`

`10.0.2.2` is the Android emulator alias for your host machine's localhost.

For a physical device, use `adb reverse tcp:18789 tcp:18789` and set the app URL to `ws://127.0.0.1:18789`.

## Token

Paste the shared gateway token from `C:\Users\t8rto\.openclaw\openclaw.json` into the app once.

On first signed connect, OpenClaw can silently pair the local Android device and issue a per-device operator token. The app stores that device token and reuses it on later reconnects.

## Build

```powershell
.\gradlew.bat assembleDebug
```
