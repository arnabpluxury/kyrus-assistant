# Kyrus model training (GPU)

Fine-tune a **tiny** Instruct model so tool-calling, phone tasks, coding, and security refusals feel native — then ship as GGUF for phones.

## Hardware

| GPU VRAM | Suggested base |
|----------|----------------|
| 6–8GB (RTX 2070 etc.) | `Qwen/Qwen2.5-0.5B-Instruct` LoRA |
| 12GB+ | `Qwen2.5-1.5B-Instruct` LoRA |
| 24GB+ | `Qwen2.5-3B-Instruct` LoRA |

## Steps

```bash
pip install -r training/requirements-train.txt
python training/expand_dataset.py
python training/train_lora.py --epochs 2 --merge
```

Adapters land in `training/output/kyrus-0.5b-lora/`.  
Merged HF weights (with `--merge`) in `training/output/kyrus-0.5b-merged/`.

### Convert merged HF → GGUF (llama.cpp)

1. Clone [llama.cpp](https://github.com/ggerganov/llama.cpp)
2. `python convert_hf_to_gguf.py training/output/kyrus-0.5b-merged --outfile models/kyrus-low-ft.gguf`
3. Quantize: `./llama-quantize models/kyrus-low-ft.gguf models/kyrus-low.gguf Q4_K_M`

Or start from a public GGUF immediately:

```bash
python scripts/download_models.py --tier low
pip install llama-cpp-python
python -m kyrus --status
```

Fine-tuned weights improve tool JSON style; the **agent runtime + sandbox** still enforce security.
