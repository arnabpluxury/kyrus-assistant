"""Local GGUF via llama-cpp-python — tuned for fast phone replies."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from .base import BaseLLM, LLMResponse, Message
from .heuristic import HeuristicLLM


TOOL_CALL_RE = re.compile(
    r"```(?:json)?\s*(\{.*?\})\s*```|(\{[^{}]*\"name\"[^{}]*\"arguments\"[^{}]*\})",
    re.S,
)


class LocalLLM(BaseLLM):
    name = "local"

    def __init__(
        self,
        model_path: str,
        n_ctx: int = 2048,
        n_threads: int = 2,
        n_gpu_layers: int = 0,
        max_tokens: int = 512,
        temperature: float = 0.25,
        top_p: float = 0.9,
        repeat_penalty: float = 1.08,
    ) -> None:
        self.model_path = model_path
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.top_p = top_p
        self.repeat_penalty = repeat_penalty
        self._fallback = HeuristicLLM()
        self._llm = None

        path = Path(model_path)
        if not path.exists():
            raise FileNotFoundError(f"Model not found: {model_path}")

        try:
            from llama_cpp import Llama  # type: ignore
        except ImportError as e:
            raise ImportError(
                "llama-cpp-python not installed. "
                "pip install llama-cpp-python"
            ) from e

        self._llm = Llama(
            model_path=str(path),
            n_ctx=n_ctx,
            n_threads=n_threads,
            n_gpu_layers=n_gpu_layers,
            verbose=False,
        )

    def complete(self, messages: list[Message], tools_schema: str = "") -> LLMResponse:
        assert self._llm is not None
        chat: list[dict[str, str]] = []
        system_extra = ""
        if tools_schema:
            system_extra = (
                "\n\nYou can call tools. When you need a tool, reply ONLY with JSON:\n"
                '{"name": "tool_name", "arguments": {...}}\n'
                "You may emit multiple calls as a JSON array.\n"
                f"Available tools:\n{tools_schema}\n"
                "After tool results appear, give a clear final answer. Be brief."
            )

        for m in messages:
            role = m.role if m.role in ("system", "user", "assistant") else "user"
            content = m.content
            if m.role == "tool":
                content = f"[tool:{m.name}] {m.content}"
                role = "user"
            if role == "system" and system_extra:
                content = content + system_extra
                system_extra = ""  # only once
            chat.append({"role": role, "content": content})

        if system_extra and not any(c["role"] == "system" for c in chat):
            chat.insert(0, {"role": "system", "content": "You are Kyrus." + system_extra})

        try:
            out = self._llm.create_chat_completion(
                messages=chat,
                max_tokens=self.max_tokens,
                temperature=self.temperature,
                top_p=self.top_p,
                repeat_penalty=self.repeat_penalty,
            )
            text = out["choices"][0]["message"]["content"] or ""
        except Exception:
            return self._fallback.complete(messages, tools_schema)

        tool_calls = self._parse_tool_calls(text)
        if tool_calls:
            return LLMResponse(text="", tool_calls=tool_calls, raw=out)
        return LLMResponse(text=text.strip(), tool_calls=[], raw=out)

    def _parse_tool_calls(self, text: str) -> list[dict[str, Any]]:
        text = text.strip()
        try:
            data = json.loads(text)
            return self._normalize_calls(data)
        except json.JSONDecodeError:
            pass

        calls: list[dict[str, Any]] = []
        for m in TOOL_CALL_RE.finditer(text):
            blob = m.group(1) or m.group(2)
            try:
                data = json.loads(blob)
                calls.extend(self._normalize_calls(data))
            except json.JSONDecodeError:
                continue
        return calls

    def _normalize_calls(self, data: Any) -> list[dict[str, Any]]:
        if isinstance(data, dict) and "name" in data:
            args = data.get("arguments") or data.get("args") or {}
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except json.JSONDecodeError:
                    args = {"input": args}
            return [{"name": data["name"], "arguments": args}]
        if isinstance(data, list):
            out: list[dict[str, Any]] = []
            for item in data:
                out.extend(self._normalize_calls(item))
            return out
        return []
