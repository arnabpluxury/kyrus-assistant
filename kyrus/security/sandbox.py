"""
Hard sandbox — prevent agent path escape, destructive shell, and prompt-injection abuse.

Design goals:
- All file/coding tools stay under an approved workspace root (default ~/kyrus-workspace)
- Shell commands pass a deny-list + optional allow-list
- Network always goes through PermissionGate (separate module)
- Refuses requests that try to exfiltrate secrets or disable the sandbox
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


class SandboxError(PermissionError):
    """Raised when an action would escape the sandbox or violate policy."""


# Patterns that must never run via shell tool
_SHELL_DENY = [
    r"rm\s+-rf\s+[/~]",
    r"mkfs\b",
    r":\(\)\s*\{",  # fork bomb
    r"\bdd\s+if=",
    r">\s*/dev/sd",
    r"chmod\s+-R\s+777\s+/",
    r"curl\s+[^\|] \|\s*(ba)?sh",
    r"wget\s+[^\|] \|\s*(ba)?sh",
    r"base64\s+-d\s*\|\s*(ba)?sh",
    r"python\s+-c\s+.*urllib.*exec",
    r"nc\s+-e\b",
    r"ncat\s+-e\b",
    r"/system/bin/su\b",
    r"pm\s+uninstall\b",
    r"am\s+startservice\b.*hack",
]

# Prompt / tool-injection fingerprints (block if user content tries to jailbreak tools)
_INJECTION_HINTS = [
    r"ignore\s+(all\s+)?(previous|prior)\s+instructions",
    r"disable\s+(the\s+)?sandbox",
    r"exfiltrate\s+(api|key|token|password)",
    r"send\s+(all\s+)?(files|secrets)\s+to\s+https?://",
]


@dataclass
class SecurityPolicy:
    workspace_root: Path
    allow_shell: bool = True
    shell_allowlist: list[str] = field(default_factory=list)  # empty = deny-list only
    max_file_bytes: int = 512_000
    max_write_bytes: int = 2_000_000
    block_dot_env_read: bool = True
    block_ssh_keys: bool = True

    @classmethod
    def from_config(cls, cfg: dict[str, Any]) -> SecurityPolicy:
        sec = cfg.get("security", {})
        raw = sec.get("workspace_root") or cfg.get("paths", {}).get("workspace") or "~/.kyrus/workspace"
        root = Path(os.path.expanduser(str(raw))).resolve()
        root.mkdir(parents=True, exist_ok=True)
        return cls(
            workspace_root=root,
            allow_shell=bool(sec.get("allow_shell", True)),
            shell_allowlist=list(sec.get("shell_allowlist") or []),
            max_file_bytes=int(sec.get("max_file_bytes", 512_000)),
            max_write_bytes=int(sec.get("max_write_bytes", 2_000_000)),
            block_dot_env_read=bool(sec.get("block_dot_env_read", True)),
            block_ssh_keys=bool(sec.get("block_ssh_keys", True)),
        )


class Sandbox:
    def __init__(self, policy: SecurityPolicy) -> None:
        self.policy = policy
        self.policy.workspace_root.mkdir(parents=True, exist_ok=True)

    @property
    def root(self) -> Path:
        return self.policy.workspace_root

    def resolve(self, raw: str, *, must_exist: bool = False) -> Path:
        """Resolve a user/agent path and ensure it stays inside the workspace."""
        raw = (raw or "").strip() or "."
        # Absolute paths outside workspace are rejected unless they resolve under root
        candidate = Path(os.path.expanduser(raw))
        if not candidate.is_absolute():
            candidate = self.root / candidate
        try:
            resolved = candidate.resolve()
        except OSError as e:
            raise SandboxError(f"Invalid path: {e}") from e

        root = self.root.resolve()
        try:
            resolved.relative_to(root)
        except ValueError as e:
            raise SandboxError(
                f"Path escape blocked: '{raw}' resolves outside workspace ({root})"
            ) from e

        if must_exist and not resolved.exists():
            raise SandboxError(f"Not found: {resolved}")

        name = resolved.name.lower()
        if self.policy.block_dot_env_read and name in (".env", ".env.local", ".env.production"):
            raise SandboxError("Reading .env secret files is blocked")
        if self.policy.block_ssh_keys and (
            ".ssh" in resolved.parts or name in ("id_rsa", "id_ed25519", "authorized_keys")
        ):
            raise SandboxError("SSH key paths are blocked")

        return resolved

    def check_shell(self, command: str) -> None:
        if not self.policy.allow_shell:
            raise SandboxError("Shell is disabled by security policy")
        cmd = (command or "").strip()
        if not cmd:
            raise SandboxError("Empty command")
        low = cmd.lower()
        for pat in _SHELL_DENY:
            if re.search(pat, low):
                raise SandboxError(f"Command blocked by security deny-list: matched /{pat}/")
        allow = self.policy.shell_allowlist
        if allow:
            # First token must match allowlist prefix
            first = cmd.split()[0]
            if not any(first == a or cmd.startswith(a) for a in allow):
                raise SandboxError(f"Command not in shell allowlist: {first}")

    def scan_user_text(self, text: str) -> list[str]:
        """Return warnings for likely jailbreak / exfil attempts (does not block chat)."""
        warnings: list[str] = []
        low = (text or "").lower()
        for pat in _INJECTION_HINTS:
            if re.search(pat, low):
                warnings.append(f"Suspicious pattern: /{pat}/")
        return warnings

    def assert_write_size(self, content: str) -> None:
        n = len(content.encode("utf-8"))
        if n > self.policy.max_write_bytes:
            raise SandboxError(f"Write too large ({n} > {self.policy.max_write_bytes} bytes)")
