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

## Build

1. Install **Android Studio** (JDK 17 + NDK + CMake via SDK Manager)
2. Open `KyrusAssistant/android`
3. First sync builds `libkyrus_llama.so` from `.deps/llama.cpp` (or FetchContent if missing)
4. Run on a **real arm64 phone** (recommended) or x86_64 emulator

```bash
cd android
./gradlew :app:assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

### NDK tips

- SDK Manager → SDK Tools → **NDK** + **CMake**
- Prefer `arm64-v8a` device for speed
- First native build can take several minutes (compiles llama.cpp)

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

Model release: https://github.com/arnabpluxury/kyrus-assistant/releases/tag/v0.2.0
