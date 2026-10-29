# Cloud Android builds (no Android Studio)

Expo has **EAS Build**. For this native Kotlin + NDK app you get the same idea via cloud CI: build on a remote machine, download the APK.

## Option A — Codemagic (recommended right now)

GitHub Actions on this account is currently **blocked by a billing lock**. Codemagic works the same way as EAS for native Android.

1. Go to [codemagic.io](https://codemagic.io) → sign in with GitHub  
2. Add repo **`arnabpluxury/kyrus-assistant`**  
3. It picks up [`codemagic.yaml`](../codemagic.yaml) automatically  
4. Start workflow **Kyrus Android (debug APK)**  
5. Download the `.apk` from the build artifacts  

First build can take 20–40+ minutes (compiles llama.cpp in the cloud).

## Option B — GitHub Actions (after billing is fixed)

Workflow file is already in the repo:

https://github.com/arnabpluxury/kyrus-assistant/actions/workflows/android-build.yml

1. Fix account billing: https://github.com/settings/billing  
2. **Actions → Android APK → Run workflow → debug**  
3. Download artifact **`kyrus-assistant-debug`**

CLI:

```bash
gh workflow run android-build.yml -f build_type=debug
gh run watch
gh run download -n kyrus-assistant-debug
```

## What you get

| Item | Detail |
|------|--------|
| APK ABI | `arm64-v8a` (real phones) |
| Debug APK | Sideload-ready (debug keystore) |
| Model | Still downloads on-device (~379 MB) after install |
| Studio on PC | **Not required** |

## Install on phone

1. Transfer the APK  
2. Allow “Install unknown apps”  
3. Open Kyrus → **Continue** → wait for model download → chat  
