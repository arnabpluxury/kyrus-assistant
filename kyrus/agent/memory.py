"""Tiny local memory (JSON) — no vector DB needed on 4GB phones."""

from __future__ import annotations

import json
from pathlib import Path


class MemoryStore:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._facts: list[str] = []
        self._load()

    def _load(self) -> None:
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                self._facts = list(data.get("facts") or [])
            except (json.JSONDecodeError, OSError):
                self._facts = []

    def _save(self) -> None:
        self.path.write_text(
            json.dumps({"facts": self._facts}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def add(self, fact: str) -> None:
        fact = fact.strip()
        if fact and fact not in self._facts:
            self._facts.append(fact)
            # Cap for low storage
            self._facts = self._facts[-200:]
            self._save()

    def all(self) -> list[str]:
        return list(self._facts)

    def search(self, query: str) -> list[str]:
        q = query.lower()
        return [f for f in self._facts if q in f.lower()]
