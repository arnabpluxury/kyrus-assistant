"""Pick GGUF by device tier — LLM-first, heuristic only as emergency fallback."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from ..device.capabilities import DeviceProfile, suggest_llm_settings
from .base import BaseLLM
from .heuristic import HeuristicLLM


def _tier_model_path(cfg: dict[str, Any], profile: DeviceProfile) -> Path:
    llm = cfg.get("llm", {})
    models = llm.get("models") or {}
    key = profile.tier if profile.tier in ("low", "mid", "high") else "low"
    # Fall through: high -> mid -> low -> model_path
    candidates = []
    if key == "high":
        candidates += [models.get("high"), models.get("mid"), models.get("low")]
    elif key == "mid":
        candidates += [models.get("mid"), models.get("low"), models.get("high")]
    else:
        candidates += [models.get("low"), models.get("mid")]
    candidates.append(llm.get("model_path"))

    root = Path(__file__).resolve().parent.parent.parent
    for c in candidates:
        if not c:
            continue
        p = Path(c)
        if not p.is_absolute():
            p = root / p
        if p.exists():
            return p
    # Return preferred path even if missing (caller handles)
    preferred = models.get(key) or llm.get("model_path") or "models/kyrus-low.gguf"
    p = Path(preferred)
    return p if p.is_absolute() else root / p


def _apply_tier_presets(cfg: dict[str, Any], profile: DeviceProfile) -> dict[str, Any]:
    presets = (cfg.get("device", {}).get("llm_presets") or {}).get(profile.tier) or {}
    hints = suggest_llm_settings(profile)
    llm = dict(cfg.get("llm") or {})
    return {
        "n_ctx": int(presets.get("n_ctx") or llm.get("n_ctx") or hints["n_ctx"]),
        "n_threads": int(presets.get("n_threads") or llm.get("n_threads") or hints["n_threads"]),
        "max_tokens": int(presets.get("max_tokens") or llm.get("max_tokens") or hints["max_tokens"]),
        "temperature": float(presets.get("temperature") or cfg.get("agent", {}).get("temperature", 0.25)),
        "n_gpu_layers": int(llm.get("n_gpu_layers") or 0),
        "top_p": float(llm.get("top_p") or 0.9),
        "repeat_penalty": float(llm.get("repeat_penalty") or 1.08),
    }


def create_llm(cfg: dict[str, Any], profile: DeviceProfile) -> tuple[BaseLLM, str]:
    llm_cfg = cfg.get("llm", {})
    mode = (llm_cfg.get("mode") or "local").lower()
    path = _tier_model_path(cfg, profile)
    knobs = _apply_tier_presets(cfg, profile)
    hints = suggest_llm_settings(profile)

    if mode == "heuristic":
        return HeuristicLLM(), "Heuristic tool-router (no GGUF) — download models for full LLM."

    if path.exists():
        try:
            from .local import LocalLLM

            llm = LocalLLM(
                model_path=str(path),
                n_ctx=knobs["n_ctx"],
                n_threads=knobs["n_threads"],
                n_gpu_layers=knobs["n_gpu_layers"],
                max_tokens=knobs["max_tokens"],
                temperature=knobs["temperature"],
                top_p=knobs["top_p"],
                repeat_penalty=knobs["repeat_penalty"],
            )
            return (
                llm,
                f"GGUF [{profile.tier}]: {path.name} | ctx={knobs['n_ctx']} threads={knobs['n_threads']} max_tok={knobs['max_tokens']}",
            )
        except Exception as gguf_err:
            # Desktop GPU fallback: Transformers (prefer local fine-tune if present)
            root = Path(__file__).resolve().parent.parent.parent
            hf_id = (llm_cfg.get("hf_model_id") or "Qwen/Qwen2.5-0.5B-Instruct").strip()
            ft = root / "training" / "output" / "kyrus-0.5b-merged"
            if ft.exists() and (ft / "config.json").exists():
                hf_id = str(ft)
            elif not hf_id.startswith("Qwen/") and not Path(hf_id).is_absolute():
                cand = root / hf_id
                if cand.exists():
                    hf_id = str(cand)
            try:
                from .hf_local import HfLocalLLM

                llm = HfLocalLLM(
                    model_id=hf_id,
                    max_tokens=knobs["max_tokens"],
                    temperature=knobs["temperature"],
                )
                return (
                    llm,
                    f"HF GPU backend [{Path(hf_id).name}] (GGUF runtime unavailable: {gguf_err}). Phone: llama-cpp + {path.name}",
                )
            except Exception as hf_err:
                return (
                    HeuristicLLM(),
                    f"GGUF load failed ({gguf_err}); HF fallback failed ({hf_err}). Heuristic active. Model file OK: {path.name}",
                )

    msg = (
        f"No GGUF for tier={profile.tier} at {path}. "
        f"Run: python scripts/download_models.py --tier {profile.tier}  "
        f"Suggested: {', '.join(hints['preferred_models'][:2])}"
    )
    if mode == "local":
        return HeuristicLLM(), msg + " | Temporary heuristic until model downloads."
    return HeuristicLLM(), msg
