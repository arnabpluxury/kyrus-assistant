# GGUF models (required for LLM mode)

Kyrus is **GGUF-first**. Heuristic routing is only a temporary fallback until a model is present.

## Download (recommended)

```bash
pip install huggingface_hub
python scripts/download_models.py --tier low    # -> models/kyrus-low.gguf   (~0.5B Q4)
python scripts/download_models.py --tier mid    # -> models/kyrus-mid.gguf
python scripts/download_models.py --tier high   # -> models/kyrus-high.gguf
python scripts/download_models.py --tier all
```

| File | Phone RAM | Role |
|------|-----------|------|
| `kyrus-low.gguf` | ~4GB | Fast everyday agent |
| `kyrus-mid.gguf` | ~6GB | Stronger coding/chat |
| `kyrus-high.gguf` | 8GB+ | Best quality on-device |

Then:

```bash
pip install llama-cpp-python
python -m kyrus --status
```

### Termux note

Building `llama-cpp-python` may need:

```bash
pkg install clang cmake make
CMAKE_ARGS="-DGGML_OPENMP=ON" pip install llama-cpp-python
```

## Fine-tuned weights

After GPU LoRA training (`training/`), convert merged HF → GGUF and replace `kyrus-low.gguf`. See `training/README.md`.

Third-party base weights (Qwen) keep their upstream licenses; Kyrus scripts/datasets use the project [LICENSE](../LICENSE).
