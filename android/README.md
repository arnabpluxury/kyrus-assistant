# Kyrus Android app (v0.4)

Plug-and-play chat — **no Termux**. Claude-mobile style shell + Kyrus Code turn chrome (thinking rows, live run status). First launch shows a **live setup log**, downloads the fine-tuned GGUF, loads it via **llama.cpp (NDK/JNI)** with streaming, then chats with sandboxed tools. Optional **BYOK** cloud providers.

## Features

| Area | Status |
|------|--------|
| Live setup console + error codes | Done |
| Private runtime prefix (sandbox, images, exports) | Done |
| GGUF download + verify from GitHub Releases | Done |
| On-device GGUF streaming (llama.cpp JNI) + Stop | Done |
| Chat drawer, history, projects | Done |
| Thinking / tool process rows + run status | Done |
| Markdown replies + inline fetched images | Done |
| File attachments / uploads | Done |
| Web research, fetch URL/image, storage search | Done |
| Archive / unarchive, pkg info, clipboard | Done |
| Network DLP (secret / local-path guards) | Done |
| BYOK (OpenAI, Anthropic, Gemini, Groq, OpenRouter, custom) | Done |
| Import / Export chats & memory | Done |
| 32-bit (`armeabi-v7a`) + 64-bit ABIs | Done |
| Voice mic (STT) + optional TTS | Done |

## Uninstall & data

Models, prefix, and Room DB live under **app-private storage**. Uninstalling Kyrus removes them. Use **Import / Export** in the drawer before uninstall if you want a backup.

## Build in the cloud

→ [docs/CLOUD_BUILD.md](../docs/CLOUD_BUILD.md)

Codemagic builds `armeabi-v7a,arm64-v8a` by default.

```bash
cd android
./gradlew :app:assembleDebug -PkyrusAbi=armeabi-v7a,arm64-v8a
```

## Architecture

```
UI (Compose drawer) → AppViewModel → KyrusEngine
                                    ├─ LlmClient (local stream | BYOK)
                                    ├─ LlamaRuntime → JNI → libkyrus_llama.so
                                    └─ ToolRegistry + NetworkDlp
BootstrapManager → live log + GGUF download
ChatRepository (Room) → conversations / messages / projects
```

App release: https://github.com/arnabpluxury/kyrus-assistant/releases/tag/v0.3.0  
Model release (GGUF): https://github.com/arnabpluxury/kyrus-assistant/releases/tag/v0.2.0
