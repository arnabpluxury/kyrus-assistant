# Kyrus GGUF models (on-device)

## Fine-tuned low-tier model (shipped locally after train + export)

| File | What it is |
|------|------------|
| **`kyrus-low.gguf`** | **Kyrus fine-tune** — Qwen2.5-0.5B Instruct + phone/coding/security LoRA, **Q4_K_M** (~379 MB) |
| `kyrus-low-ft.gguf` | Same weights, branded filename |
| `kyrus-low-base.gguf` | Stock upstream Qwen2.5-0.5B Q4 (backup before fine-tune replace) |
| `default.gguf` | Copy of `kyrus-low.gguf` for convenience |

Rebuild from your GPU merge anytime:

```bash
python training/train_lora.py --merge
python scripts/export_gguf.py --tier low
```

## Download stock tiers (no fine-tune)

```bash
pip install huggingface_hub
python scripts/download_models.py --tier low    # stock -> models/kyrus-low.gguf
python scripts/download_models.py --tier mid
python scripts/download_models.py --tier high
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
