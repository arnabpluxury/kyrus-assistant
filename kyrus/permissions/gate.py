"""Interactive permission gate — network/shell never silent."""

from __future__ import annotations

import os
import sys
from typing import Callable


AskFn = Callable[[str], str]


class PermissionGate:
    """Ask the user before risky actions (internet, shell)."""

    NETWORK = "network"
    SHELL = "shell"
    FILES_WRITE = "files_write"

    def __init__(
        self,
        ask_network: bool = True,
        ask_shell: bool = True,
        remember_session: bool = True,
        ask_fn: AskFn | None = None,
        assume_yes: bool = False,
    ) -> None:
        self.ask_network = ask_network
        self.ask_shell = ask_shell
        self.remember_session = remember_session
        env_yes = os.environ.get("KYRUS_ASSUME_YES", "").strip().lower() in ("1", "true", "yes")
        self.assume_yes = assume_yes or env_yes
        self._ask = ask_fn or (lambda prompt: input(prompt).strip().lower())
        # session grants: kind -> True/False (None = not decided)
        self._session: dict[str, bool | None] = {}

    def reset_session(self) -> None:
        self._session.clear()

    def _prompt(self, kind: str, detail: str) -> bool:
        if self.remember_session and kind in self._session and self._session[kind] is not None:
            return bool(self._session[kind])

        if self.assume_yes:
            if self.remember_session:
                self._session[kind] = True
            print(f"[!] Auto-allowed ({kind}): {detail}", file=sys.stderr)
            return True

        labels = {
            self.NETWORK: "INTERNET",
            self.SHELL: "SHELL / COMMAND",
            self.FILES_WRITE: "WRITE FILES",
        }
        label = labels.get(kind, kind.upper())
        print()
        print(f"[!] Permission required: {label}")
        print(f"   {detail}")
        print("   [y] allow once  [s] allow this session  [n] deny")
        try:
            ans = self._ask("   Allow? ").strip().lower()
        except EOFError:
            print("   Denied (non-interactive; pass --yes or KYRUS_ASSUME_YES=1).")
            return False

        if ans in ("s", "session", "always"):
            if self.remember_session:
                self._session[kind] = True
            return True
        if ans in ("y", "yes", "1", "ok"):
            return True
        if ans in ("n", "no", "0", "deny"):
            if self.remember_session and ans in ("deny",):
                self._session[kind] = False
            return False
        # Default deny on garbage input
        print("   Denied (unclear answer).")
        return False

    def allow_network(self, reason: str) -> bool:
        if not self.ask_network:
            return True
        return self._prompt(self.NETWORK, reason)

    def allow_shell(self, command: str) -> bool:
        if not self.ask_shell:
            return True
        return self._prompt(self.SHELL, f"Run: {command}")

    def allow_write(self, path: str) -> bool:
        return self._prompt(self.FILES_WRITE, f"Write/modify: {path}")
