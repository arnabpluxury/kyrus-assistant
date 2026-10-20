"""Detect phone resources and unlock feature tiers."""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
from dataclasses import dataclass, field
from typing import Any


@dataclass
class DeviceProfile:
    tier: str  # low | mid | high
    ram_mb: int
    cpu_cores: int
    free_disk_mb: int
    is_termux: bool
    has_termux_api: bool
    features: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def has(self, feature: str) -> bool:
        return feature in self.features

    def summary(self) -> str:
        return (
            f"tier={self.tier} | RAM~{self.ram_mb}MB | "
            f"CPU={self.cpu_cores} | free_disk~{self.free_disk_mb}MB | "
            f"termux={self.is_termux} | termux-api={self.has_termux_api}"
        )


def _read_meminfo_mb() -> int:
    """Best-effort total RAM in MB (Linux/Android /proc/meminfo)."""
    try:
        with open("/proc/meminfo", encoding="utf-8") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    kb = int(line.split()[1])
                    return kb // 1024
    except OSError:
        pass
    # Fallback guess for desktop testing
    return 4096


def _free_disk_mb(path: str = ".") -> int:
    try:
        usage = shutil.disk_usage(path)
        return usage.free // (1024 * 1024)
    except OSError:
        return 0


def _cpu_cores() -> int:
    return os.cpu_count() or 2


def _is_termux() -> bool:
    return bool(os.environ.get("TERMUX_VERSION") or os.environ.get("PREFIX", "").endswith("com.termux"))


def _has_termux_api() -> bool:
    return shutil.which("termux-battery-status") is not None or shutil.which("termux-speech-to-text") is not None


def _run_safe(cmd: list[str], timeout: float = 3.0) -> str:
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or "").strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def detect_device(cfg: dict[str, Any]) -> DeviceProfile:
    device_cfg = cfg.get("device", {})
    ram = _read_meminfo_mb()
    cores = _cpu_cores()
    free_disk = _free_disk_mb(os.path.expanduser("~"))
    is_termux = _is_termux()
    has_api = _has_termux_api()

    forced = (device_cfg.get("tier") or "auto").lower()
    low_th = int(device_cfg.get("low_ram_mb", 4096))
    mid_th = int(device_cfg.get("mid_ram_mb", 6144))

    if forced in ("low", "mid", "high"):
        tier = forced
    else:
        # On phones with ≤4GB usable pressure, stay on low even if MemTotal says 4GB
        if ram <= low_th or free_disk < 512:
            tier = "low"
        elif ram < mid_th:
            tier = "mid"
        else:
            tier = "high"
        # Extra cores / headroom bump mid→high
        if tier == "mid" and cores >= 8 and ram >= mid_th:
            tier = "high"

    features_map = device_cfg.get("features") or {}
    features = list(features_map.get(tier, features_map.get("low", [])))

    notes: list[str] = []
    if not is_termux:
        notes.append("Not running inside Termux — some phone tools are limited.")
    if tier == "high" and not has_api:
        notes.append("Install termux-api for voice, battery, and notifications.")
    if free_disk < 800:
        notes.append("Low free storage — prefer tiny GGUF or heuristic mode.")

    # Soft-disable voice if no API
    if "voice" in features and not has_api:
        features = [f for f in features if f != "voice"]
        notes.append("Voice disabled (termux-api missing).")

    return DeviceProfile(
        tier=tier,
        ram_mb=ram,
        cpu_cores=cores,
        free_disk_mb=free_disk,
        is_termux=is_termux,
        has_termux_api=has_api,
        features=features,
        notes=notes,
    )


def suggest_llm_settings(profile: DeviceProfile) -> dict[str, Any]:
    """Recommend llama.cpp-ish knobs by tier."""
    if profile.tier == "low":
        return {
            "preferred_models": [
                "kyrus-low.gguf (Qwen2.5-0.5B Q4)",
                "python scripts/download_models.py --tier low",
            ],
            "n_ctx": 1024,
            "n_threads": max(1, min(2, profile.cpu_cores // 2 or 1)),
            "max_tokens": 256,
            "hint": "4GB: keep Q4 0.5B + tools; short context for speed.",
        }
    if profile.tier == "mid":
        return {
            "preferred_models": [
                "kyrus-mid.gguf (Qwen2.5-1.5B Q4)",
                "python scripts/download_models.py --tier mid",
            ],
            "n_ctx": 2048,
            "n_threads": min(4, profile.cpu_cores),
            "max_tokens": 512,
            "hint": "6GB: 1.5B Q4 + coding/shell tools.",
        }
    return {
        "preferred_models": [
            "kyrus-high.gguf (Qwen2.5-3B Q4)",
            "python scripts/download_models.py --tier high",
        ],
        "n_ctx": 4096,
        "n_threads": min(6, profile.cpu_cores),
        "max_tokens": 768,
        "hint": "8GB+: 3B Q4, voice, longer coding sessions.",
    }


def platform_banner() -> str:
    return f"{platform.system()} {platform.release()} | Python {platform.python_version()}"
