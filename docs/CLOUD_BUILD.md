# Cloud Android builds (no Android Studio)

Expo has **EAS Build**. For this native Kotlin + NDK app, the equivalent is **GitHub Actions** — builds run on GitHub’s cloud runners; you only download the APK.

## One-click build

1. Open: https://github.com/arnabpluxury/kyrus-assistant/actions/workflows/android-build.yml  
2. Click **Run workflow** → choose `debug` (or `release`) → **Run workflow**  
3. Wait for the green check (first run can take 20–40+ min — compiles llama.cpp)  
4. Open the run → **Artifacts** → download `kyrus-assistant-debug`  
5. Unzip → install the `.apk` on your phone (enable “Install unknown apps”)

Or from a terminal with `gh` logged in:

```bash
gh workflow run android-build.yml -R arnabpluxury/kyrus-assistant -f build_type=debug
gh run watch -R arnabpluxury/kyrus-assistant
gh run download -R arnabpluxury/kyrus-assistant -n kyrus-assistant-debug
```

## What gets built

- **arm64-v8a** APK (real phones) — faster CI than also building x86_64  
- Debug APK is signed with the default debug keystore (fine for sideload testing)  
- Release APK from the workflow is **unsigned** until you add a signing secret (optional later)

## Notes

- You do **not** need Android Studio on your PC  
- Native GGUF engine is compiled in the cloud into the APK  
- The ~379 MB model still downloads **on the phone** after install (GitHub Releases)
