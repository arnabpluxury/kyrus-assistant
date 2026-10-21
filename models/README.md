# Kyrus GGUF models (on-device)

## Download (users)

Fine-tuned **low** model is on **GitHub Releases** (not inside git — too large):

**https://github.com/arnabpluxury/kyrus-assistant/releases/tag/v0.2.0**

```bash
python scripts/download_models.py --tier low    # Kyrus fine-tune from GitHub Releases
python scripts/download_models.py --tier mid    # stock Qwen (HF) until mid FT ships
python scripts/download_models.py --tier high
```

Direct file: `kyrus-low.gguf` (~379 MB, Q4_K_M).

## Local files after download / export

| File | What it is |
|------|------------|
| **`kyrus-low.gguf`** | **Kyrus fine-tune** — phone/coding/security LoRA, Q4_K_M |
| `kyrus-low-ft.gguf` | Same weights, branded local copy |
| `kyrus-low-base.gguf` | Stock upstream backup (maintainers only) |
| `default.gguf` | Copy of `kyrus-low.gguf` |

Rebuild + re-upload (maintainers):

```bash
python training/train_lora.py --merge
python scripts/export_gguf.py --tier low
gh release upload v0.2.0 models/kyrus-low.gguf --clobber
```

| File | Phone RAM | Role |
|------|-----------|------|
| `kyrus-low.gguf` | ~4GB | Fast everyday agent |
| `kyrus-mid.gguf` | ~6GB | Stronger coding/chat |
| `kyrus-high.gguf` | 8GB+ | Best quality on-device |

Then on Termux / a Python with llama-cpp wheels:

```bash
pip install llama-cpp-python
python -m kyrus --status
```

### Termux note

```bash
pkg install clang cmake make
CMAKE_ARGS="-DGGML_OPENMP=ON" pip install llama-cpp-python
```

Third-party base weights (Qwen) keep their upstream licenses; Kyrus fine-tune recipes/scripts use the project [LICENSE](../LICENSE).
