"""Load and merge YAML config with sensible defaults."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG = ROOT / "config" / "default.yaml"


def _expand(path: str) -> Path:
    return Path(os.path.expanduser(path)).resolve()


def load_config(path: Path | None = None) -> dict[str, Any]:
    cfg_path = path or DEFAULT_CONFIG
    with open(cfg_path, encoding="utf-8") as f:
        data = yaml.safe_load(f) or {}

    # Expand ~ paths
    paths = data.setdefault("paths", {})
    for key in ("data_dir", "memory_file", "notes_dir", "session_log", "workspace"):
        if key in paths:
            paths[key] = str(_expand(str(paths[key])))
    sec = data.setdefault("security", {})
    if sec.get("workspace_root"):
        sec["workspace_root"] = str(_expand(str(sec["workspace_root"])))

    llm = data.setdefault("llm", {})
    model = llm.get("model_path", "models/default.gguf")
    mp = Path(model)
    if not mp.is_absolute():
        mp = ROOT / mp
    llm["model_path"] = str(mp)

    return data


def ensure_data_dirs(cfg: dict[str, Any]) -> None:
    paths = cfg.get("paths", {})
    for key in ("data_dir", "notes_dir", "session_log", "workspace"):
        p = paths.get(key)
        if p:
            Path(p).mkdir(parents=True, exist_ok=True)
    mem = paths.get("memory_file")
    if mem:
        Path(mem).parent.mkdir(parents=True, exist_ok=True)
    sec_root = cfg.get("security", {}).get("workspace_root")
    if sec_root:
        Path(os.path.expanduser(str(sec_root))).mkdir(parents=True, exist_ok=True)
