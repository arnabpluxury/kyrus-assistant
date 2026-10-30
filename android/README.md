# Kyrus Android app (v0.3)

Plug-and-play chat — **no Termux**. First launch downloads the fine-tuned GGUF with a progress bar, loads it via **llama.cpp (NDK/JNI)**, then chats like ChatGPT with sandboxed tools.

## Features

| Area | Status |
|------|--------|
| Setup UI (sizes + free space + progress) | Done |
| Private runtime prefix (sandbox workspace) | Done |
| GGUF download from GitHub Releases | Done |
| **On-device GGUF inference (llama.cpp JNI)** | Done |
| Tools (research, coding sandbox, notes, memory…) | Done |
| Internet permission dialogs | Done |
| Voice mic (STT) + optional TTS replies | Done |
| Settings | Done |

## Build in the cloud (recommended — no Android Studio)

Same idea as Expo EAS: GitHub builds the APK for you.

→ Full guide: [docs/CLOUD_BUILD.md](../docs/CLOUD_BUILD.md)

```bash
gh workflow run android-build.yml -f build_type=debug
# then download the artifact from the Actions run
```

Or: **Actions → Android APK → Run workflow** on GitHub.

## Build locally (optional)

1. Install **Android Studio** (JDK 17 + NDK + CMake)
2. Open `KyrusAssistant/android`
3. Run on an **arm64 phone**

```bash
cd android
./gradlew :app:assembleDebug -PkyrusAbi=arm64-v8a
```

## User flow

1. Install APK  
2. **Continue** → see disk needs → download ~379 MB model  
3. Chat — model loads in background  
4. Mic for voice input; Settings → Speak replies for TTS  
5. Web tools ask Allow once / session / Deny  

## Architecture

```
UI (Compose) → AppViewModel → KyrusEngine
                              ├─ LlamaRuntime (JNI → libkyrus_llama.so → GGUF)
                              └─ ToolRegistry (sandbox + OkHttp research)
BootstrapManager → downloads kyrus-low.gguf into app-private prefix
```

App release (APK): https://github.com/arnabpluxury/kyrus-assistant/releases/tag/v0.3.0  
Model release (GGUF): https://github.com/arnabpluxury/kyrus-assistant/releases/tag/v0.2.0
