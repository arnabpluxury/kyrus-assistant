"""CLI entry: python -m kyrus  |  kyrus"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __app_name__, __version__
from .agent.core import Agent
from .agent.memory import MemoryStore
from .config import ensure_data_dirs, load_config
from .device.capabilities import detect_device, platform_banner
from .llm.router import create_llm
from .permissions.gate import PermissionGate
from .security.sandbox import SecurityPolicy, Sandbox
from .tools.registry import build_default_registry
from .ui.repl import run_repl


def build_agent(cfg_path: Path | None = None):
    cfg = load_config(cfg_path)
    ensure_data_dirs(cfg)
    # Ensure workspace exists
    policy = SecurityPolicy.from_config(cfg)
    sandbox = Sandbox(policy)

    profile = detect_device(cfg)
    llm, llm_status = create_llm(cfg, profile)
    tools = build_default_registry()
    enabled = list(cfg.get("tools", {}).get("enabled") or [])
    if enabled:
        for name in list(tools.names()):
            if name not in enabled:
                tools._tools.pop(name, None)  # noqa: SLF001

    perm = cfg.get("permissions", {})
    gate = PermissionGate(
        ask_network=bool(perm.get("ask_network", True)),
        ask_shell=bool(perm.get("ask_shell", True)),
        remember_session=bool(perm.get("remember_session", True)),
    )
    memory = MemoryStore(cfg["paths"]["memory_file"])
    agent = Agent(cfg, profile, llm, tools, gate, memory, sandbox=sandbox)
    return agent, profile, cfg, llm_status, sandbox


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="kyrus",
        description=f"{__app_name__} — secure on-device LLM agent (Termux / CLI)",
    )
    parser.add_argument("--version", action="version", version=f"{__app_name__} {__version__}")
    parser.add_argument("-c", "--config", type=Path, default=None, help="Path to config YAML")
    parser.add_argument("-q", "--query", type=str, default=None, help="One-shot question (no REPL)")
    parser.add_argument("--status", action="store_true", help="Print device/LLM status and exit")
    parser.add_argument("--workspace", action="store_true", help="Print sandbox workspace path")
    parser.add_argument(
        "--yes",
        "-y",
        action="store_true",
        help="Auto-allow network/shell/write for this run (still logged; use carefully)",
    )
    args = parser.parse_args(argv)

    try:
        agent, profile, cfg, llm_status, sandbox = build_agent(args.config)
    except Exception as e:
        print(f"Failed to start: {e}", file=sys.stderr)
        return 1

    if args.yes:
        agent.gate.assume_yes = True

    if args.workspace:
        print(sandbox.root)
        return 0

    if args.status:
        print(platform_banner())
        print(profile.summary())
        print(llm_status)
        print("workspace:", sandbox.root)
        print("features:", ", ".join(profile.features))
        for n in profile.notes:
            print("-", n)
        return 0

    if args.query:
        def on_event(kind: str, msg: str) -> None:
            if kind == "tool":
                print(f"-> {msg}", file=sys.stderr)
            if kind == "security":
                print(f"[security] {msg}", file=sys.stderr)

        agent.on_event = on_event
        print(agent.ask(args.query))
        return 0

    run_repl(agent, profile, cfg, llm_status)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
