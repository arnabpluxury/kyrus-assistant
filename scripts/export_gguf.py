#!/usr/bin/env python3
"""
Export fine-tuned HF weights -> on-device Kyrus GGUF (Q4_K_M).

Requires:
  - training/output/kyrus-0.5b-merged/  (from train_lora.py --merge)
  - .deps/llama.cpp/convert_hf_to_gguf.py
  - .deps/llama-bin/win/llama-quantize.exe

Usage:
  python scripts/export_gguf.py
  python scripts/export_gguf.py --tier low
"""

from __future__ import annotations

import argparse
import runpy
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MERGED = ROOT / "training" / "output" / "kyrus-0.5b-merged"
MODELS = ROOT / "models"
LLAMA = ROOT / ".deps" / "llama.cpp"
CONVERT = LLAMA / "convert_hf_to_gguf.py"
QUANTIZE = ROOT / ".deps" / "llama-bin" / "win" / "llama-quantize.exe"

DEST = {
    "low": "kyrus-low.gguf",
    "mid": "kyrus-mid.gguf",
    "high": "kyrus-high.gguf",
}


def convert_hf_to_f16(src: Path, outfile: Path, model_name: str) -> None:
    # Windows python313._pth isolates env — inject llama.cpp paths manually
    sys.path.insert(0, str(LLAMA))
    sys.path.insert(1, str(LLAMA / "gguf-py"))
    argv = [
        str(CONVERT),
        str(src),
        "--outfile",
        str(outfile),
        "--outtype",
        "f16",
        "--model-name",
        model_name,
    ]
    old = sys.argv
    try:
        sys.argv = argv
        runpy.run_path(str(CONVERT), run_name="__main__")
    finally:
        sys.argv = old


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", choices=["low", "mid", "high"], default="low")
    ap.add_argument("--src", type=Path, default=MERGED)
    ap.add_argument("--quant", default="Q4_K_M")
    args = ap.parse_args()

    if not args.src.exists():
        print(f"Missing merged model: {args.src}", file=sys.stderr)
        print("Run: python training/train_lora.py --merge", file=sys.stderr)
        return 1
    if not CONVERT.exists():
        print(f"Missing {CONVERT} — clone ggml-org/llama.cpp into .deps/llama.cpp", file=sys.stderr)
        return 1
    if not QUANTIZE.exists():
        print(f"Missing {QUANTIZE} — download win-cpu llama.cpp release into .deps/llama-bin/win", file=sys.stderr)
        return 1

    MODELS.mkdir(parents=True, exist_ok=True)
    dest_name = DEST[args.tier]
    dest = MODELS / dest_name
    base_backup = MODELS / f"{Path(dest_name).stem}-base.gguf"
    f16 = MODELS / f"{Path(dest_name).stem}-ft-f16.gguf"

    if dest.exists() and not base_backup.exists():
        shutil.copy2(dest, base_backup)
        print(f"Backed up previous -> {base_backup.name}")

    print(f"Converting {args.src} -> {f16.name} (f16)...")
    convert_hf_to_f16(args.src, f16, f"kyrus-{args.tier}-ft")
    if not f16.exists():
        print("Convert finished but F16 GGUF missing", file=sys.stderr)
        return 1

    tmp_q = MODELS / f"{Path(dest_name).stem}-ft-{args.quant}.gguf"
    print(f"Quantizing -> {tmp_q.name} ({args.quant})...")
    subprocess.check_call([str(QUANTIZE), str(f16), str(tmp_q), args.quant])

    shutil.copy2(tmp_q, dest)
    # Also keep an explicitly named fine-tune artifact
    branded = MODELS / f"kyrus-{args.tier}-ft.gguf"
    shutil.copy2(tmp_q, branded)
    if args.tier == "low":
        shutil.copy2(dest, MODELS / "default.gguf")

    print(f"[ok] On-device model: {dest} ({dest.stat().st_size // (1024 * 1024)} MB)")
    print(f"[ok] Branded copy:   {branded.name}")
    print("Tip: delete *-ft-f16.gguf later to free ~1GB.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
