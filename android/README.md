# Kyrus Android app

Plug-and-play chat app — **no Termux, no CLI**. First launch shows exact sizes, downloads the fine-tuned GGUF from GitHub Releases with a progress bar, then opens a ChatGPT-style chat. Tools (research, notes, sandbox coding, …) run in the background inside an app-private “prefix” (Termux-like layout, invisible to the user).

## Build (Android Studio)

1. Install [Android Studio](https://developer.android.com/studio) (JDK 17).
2. **File → Open** → `KyrusAssistant/android`
3. Wait for Gradle sync
4. Run on a phone/emulator (API 26+)

Debug APK:

```bash
cd android
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## What users see

1. **Setup** — lists runtime + model sizes, free space, download progress  
2. **Chat** — normal messaging UI  
3. **Permission dialog** — before any internet tool (Allow once / session / Deny)

## Architecture

| Piece | Role |
|-------|------|
| `BootstrapManager` | Creates private prefix, downloads `kyrus-low.gguf` |
| `KyrusEngine` + tools | Background agent (tools-first) |
| Compose UI | Setup + chat (cream/teal Kyrus look) |

Model URL: [Releases v0.2.0](https://github.com/arnabpluxury/kyrus-assistant/releases/tag/v0.2.0)

## Roadmap (next)

- JNI / llama.cpp Android for full GGUF chat generation (today: tools + smart router; model file is already installed for the next step)
- Optional voice on high-end devices
- Play Store listing
