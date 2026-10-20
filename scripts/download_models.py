#!/usr/bin/env python3
"""
Download tiered GGUF models for Kyrus (Hugging Face).

  python scripts/download_models.py --tier low
  python scripts/download_models.py --tier all

Requires: pip install huggingface_hub
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"

# Public Q4 GGUFs — small enough for phones; rename to kyrus-*.gguf
CATALOG = {
    "low": {
        "repo": "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        "filename": "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "dest": "kyrus-low.gguf",
        "alt": [
            ("bartowski/Qwen2.5-0.5B-Instruct-GGUF", "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"),
        ],
    },
    "mid": {
        "repo": "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        "filename": "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "dest": "kyrus-mid.gguf",
        "alt": [
            ("bartowski/Qwen2.5-1.5B-Instruct-GGUF", "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"),
        ],
    },
    "high": {
        "repo": "Qwen/Qwen2.5-3B-Instruct-GGUF",
        "filename": "qwen2.5-3b-instruct-q4_k_m.gguf",
        "dest": "kyrus-high.gguf",
        "alt": [
            ("bartowski/Qwen2.5-3B-Instruct-GGUF", "Qwen2.5-3B-Instruct-Q4_K_M.gguf"),
        ],
    },
}


def download_one(tier: str) -> Path:
    try:
        from huggingface_hub import hf_hub_download
    except ImportError:
        print("Install: pip install huggingface_hub", file=sys.stderr)
        raise SystemExit(2)

    info = CATALOG[tier]
    MODELS.mkdir(parents=True, exist_ok=True)
    dest = MODELS / info["dest"]
    if dest.exists() and dest.stat().st_size > 1_000_000:
        print(f"[ok] already have {dest}")
        return dest

    attempts = [(info["repo"], info["filename"]), *info.get("alt", [])]
    last_err: Exception | None = None
    for repo, filename in attempts:
        print(f"Downloading {tier}: {repo} / {filename}")
        try:
            path = hf_hub_download(
                repo_id=repo,
                filename=filename,
                local_dir=str(MODELS / "_hf_cache"),
            )
            shutil.copy2(path, dest)
            # Also expose as default.gguf for low tier convenience
            if tier == "low":
                shutil.copy2(dest, MODELS / "default.gguf")
            print(f"[ok] saved -> {dest} ({dest.stat().st_size // (1024*1024)} MB)")
            return dest
        except Exception as e:
            last_err = e
            print(f"  failed: {e}")
    raise RuntimeError(f"Could not download {tier}: {last_err}")


def main() -> int:
    p = argparse.ArgumentParser(description="Download Kyrus GGUF models")
    p.add_argument("--tier", choices=["low", "mid", "high", "all"], default="low")
    args = p.parse_args()
    tiers = list(CATALOG) if args.tier == "all" else [args.tier]
    for t in tiers:
        download_one(t)
    print("\nNext: pip install llama-cpp-python && python -m kyrus --status")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
