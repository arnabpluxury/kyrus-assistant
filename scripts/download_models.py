#!/usr/bin/env python3
"""
Download tiered GGUF models for Kyrus.

  python scripts/download_models.py --tier low     # fine-tuned from GitHub Releases
  python scripts/download_models.py --tier mid     # stock Qwen (HF) until we ship mid FT
  python scripts/download_models.py --tier all

Low tier prefers the official Kyrus fine-tune on GitHub Releases.
"""

from __future__ import annotations

import argparse
import shutil
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"

# Official Kyrus fine-tune (GitHub Releases)
GITHUB_REPO = "arnabpluxury/kyrus-assistant"
RELEASE_TAG = "v0.2.0"

CATALOG = {
    "low": {
        "dest": "kyrus-low.gguf",
        # Primary: our fine-tuned on-device brain
        "github_asset": "kyrus-low.gguf",
        # Fallback: stock Qwen if release missing
        "hf": [
            ("Qwen/Qwen2.5-0.5B-Instruct-GGUF", "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            ("bartowski/Qwen2.5-0.5B-Instruct-GGUF", "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"),
        ],
    },
    "mid": {
        "dest": "kyrus-mid.gguf",
        "github_asset": None,  # not shipped yet
        "hf": [
            ("Qwen/Qwen2.5-1.5B-Instruct-GGUF", "qwen2.5-1.5b-instruct-q4_k_m.gguf"),
            ("bartowski/Qwen2.5-1.5B-Instruct-GGUF", "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"),
        ],
    },
    "high": {
        "dest": "kyrus-high.gguf",
        "github_asset": None,
        "hf": [
            ("Qwen/Qwen2.5-3B-Instruct-GGUF", "qwen2.5-3b-instruct-q4_k_m.gguf"),
            ("bartowski/Qwen2.5-3B-Instruct-GGUF", "Qwen2.5-3B-Instruct-Q4_K_M.gguf"),
        ],
    },
}


def _github_url(asset: str) -> str:
    return (
        f"https://github.com/{GITHUB_REPO}/releases/download/"
        f"{RELEASE_TAG}/{asset}"
    )


def _download_url(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".partial")
    print(f"Downloading:\n  {url}")

    def _reporthook(block_num: int, block_size: int, total: int) -> None:
        if total <= 0:
            return
        done = min(block_num * block_size, total)
        pct = done * 100 // total
        mb = done // (1024 * 1024)
        sys.stdout.write(f"\r  {pct:3d}% ({mb} MB)")
        sys.stdout.flush()

    try:
        urllib.request.urlretrieve(url, tmp, reporthook=_reporthook)
        print()
        tmp.replace(dest)
    except Exception:
        if tmp.exists():
            tmp.unlink(missing_ok=True)
        raise


def _download_hf(repo: str, filename: str, dest: Path) -> None:
    from huggingface_hub import hf_hub_download

    path = hf_hub_download(
        repo_id=repo,
        filename=filename,
        local_dir=str(MODELS / "_hf_cache"),
    )
    shutil.copy2(path, dest)


def download_one(tier: str) -> Path:
    info = CATALOG[tier]
    MODELS.mkdir(parents=True, exist_ok=True)
    dest = MODELS / info["dest"]
    if dest.exists() and dest.stat().st_size > 1_000_000:
        print(f"[ok] already have {dest}")
        return dest

    errors: list[str] = []

    asset = info.get("github_asset")
    if asset:
        url = _github_url(asset)
        try:
            _download_url(url, dest)
            if tier == "low":
                shutil.copy2(dest, MODELS / "default.gguf")
            print(f"[ok] Kyrus fine-tune -> {dest} ({dest.stat().st_size // (1024 * 1024)} MB)")
            return dest
        except Exception as e:
            errors.append(f"GitHub release: {e}")
            print(f"  GitHub failed: {e}")

    # Hugging Face fallback (stock / mid / high)
    try:
        import huggingface_hub  # noqa: F401
    except ImportError:
        print("Install for HF fallback: pip install huggingface_hub", file=sys.stderr)
        if not asset:
            raise SystemExit(2)

    for repo, filename in info.get("hf") or []:
        print(f"HF fallback {tier}: {repo} / {filename}")
        try:
            _download_hf(repo, filename, dest)
            if tier == "low":
                shutil.copy2(dest, MODELS / "default.gguf")
            print(f"[ok] saved -> {dest} ({dest.stat().st_size // (1024 * 1024)} MB)")
            return dest
        except Exception as e:
            errors.append(f"{repo}: {e}")
            print(f"  failed: {e}")

    raise RuntimeError(f"Could not download {tier}: " + " | ".join(errors))


def main() -> int:
    p = argparse.ArgumentParser(description="Download Kyrus GGUF models")
    p.add_argument("--tier", choices=["low", "mid", "high", "all"], default="low")
    args = p.parse_args()
    tiers = list(CATALOG) if args.tier == "all" else [args.tier]
    for t in tiers:
        download_one(t)
    print("\nNext: pip install llama-cpp-python && python -m kyrus --status")
    print(f"Low-tier fine-tune release: https://github.com/{GITHUB_REPO}/releases/tag/{RELEASE_TAG}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
