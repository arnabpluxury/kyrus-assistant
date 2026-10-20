"""LLM abstractions."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


@dataclass
class Message:
    role: str  # system | user | assistant | tool
    content: str
    name: str | None = None


@dataclass
class LLMResponse:
    text: str
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    raw: Any = None


class BaseLLM(ABC):
    name: str = "base"

    @abstractmethod
    def complete(self, messages: list[Message], tools_schema: str = "") -> LLMResponse:
        raise NotImplementedError
