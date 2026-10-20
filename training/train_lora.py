#!/usr/bin/env python3
"""
LoRA fine-tune Qwen2.5-0.5B-Instruct on Kyrus phone/coding/security data.

Designed for consumer GPUs (e.g. RTX 2070 8GB).

  pip install -r training/requirements-train.txt
  python training/expand_dataset.py
  python training/train_lora.py

Outputs:
  training/output/kyrus-0.5b-lora/   (adapters)
  training/output/kyrus-0.5b-merged/ (optional merged HF weights)
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "dataset" / "kyrus_sft.jsonl"
OUT = ROOT / "output"


def load_rows(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="Qwen/Qwen2.5-0.5B-Instruct")
    ap.add_argument("--epochs", type=float, default=2.0)
    ap.add_argument("--batch", type=int, default=2)
    ap.add_argument("--grad-accum", type=int, default=8)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--max-seq", type=int, default=1024)
    ap.add_argument("--merge", action="store_true", help="Merge LoRA into full weights")
    args = ap.parse_args()

    if not DATA.exists():
        print("Run: python training/expand_dataset.py first")
        return 1

    import torch
    from datasets import Dataset
    from peft import LoraConfig, get_peft_model, TaskType
    from transformers import (
        AutoModelForCausalLM,
        AutoTokenizer,
        DataCollatorForLanguageModeling,
        Trainer,
        TrainingArguments,
    )

    print("CUDA:", torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu")
    rows = load_rows(DATA)
    print(f"Examples: {len(rows)}")

    tok = AutoTokenizer.from_pretrained(args.base, trust_remote_code=True)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token

    def format_example(ex: dict) -> str:
        # Chat template
        return tok.apply_chat_template(ex["messages"], tokenize=False, add_generation_prompt=False)

    texts = [format_example(r) for r in rows]
    ds = Dataset.from_dict({"text": texts})

    def tokenize(batch):
        return tok(
            batch["text"],
            truncation=True,
            max_length=args.max_seq,
            padding=False,
        )

    ds = ds.map(tokenize, batched=True, remove_columns=["text"])

    dtype = torch.float16 if torch.cuda.is_available() else torch.float32
    model = AutoModelForCausalLM.from_pretrained(
        args.base,
        torch_dtype=dtype,
        device_map="auto" if torch.cuda.is_available() else None,
        trust_remote_code=True,
    )
    model.config.use_cache = False

    lora = LoraConfig(
        task_type=TaskType.CAUSAL_LM,
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    out_dir = OUT / "kyrus-0.5b-lora"
    out_dir.mkdir(parents=True, exist_ok=True)

    targs = TrainingArguments(
        output_dir=str(out_dir),
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch,
        gradient_accumulation_steps=args.grad_accum,
        learning_rate=args.lr,
        logging_steps=5,
        save_steps=50,
        save_total_limit=2,
        fp16=torch.cuda.is_available(),
        bf16=False,
        report_to=[],
        remove_unused_columns=False,
        warmup_steps=5,
        lr_scheduler_type="cosine",
        optim="adamw_torch",
    )

    collator = DataCollatorForLanguageModeling(tok, mlm=False)
    trainer = Trainer(model=model, args=targs, train_dataset=ds, data_collator=collator)
    trainer.train()
    model.save_pretrained(str(out_dir))
    tok.save_pretrained(str(out_dir))
    print(f"Saved LoRA -> {out_dir}")

    if args.merge:
        merged = OUT / "kyrus-0.5b-merged"
        print("Merging LoRA into base weights...")
        m = model.merge_and_unload()
        merged.mkdir(parents=True, exist_ok=True)
        m.save_pretrained(str(merged))
        tok.save_pretrained(str(merged))
        print(f"Merged HF model -> {merged}")
        print("Convert to GGUF with llama.cpp convert script, then quantize Q4_K_M.")
        print("See training/README.md")

    # Avoid leftover CUDA context issues on Windows
    del model
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    return 0


if __name__ == "__main__":
    # Reduce tokenizer parallelism noise on Windows
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    raise SystemExit(main())
