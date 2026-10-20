"""Hugging Face Transformers backend — desktop GPU when llama-cpp isn't available."""

from __future__ import annotations

import json
import re
from typing import Any

from .base import BaseLLM, LLMResponse, Message
from .heuristic import HeuristicLLM

TOOL_CALL_RE = re.compile(
    r"```(?:json)?\s*(\{.*?\})\s*```|(\{[^{}]*\"name\"[^{}]*\"arguments\"[^{}]*\})",
    re.S,
)


class HfLocalLLM(BaseLLM):
    name = "hf_local"

    def __init__(
        self,
        model_id: str = "Qwen/Qwen2.5-0.5B-Instruct",
        max_tokens: int = 512,
        temperature: float = 0.25,
    ) -> None:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer

        self.max_tokens = max_tokens
        self.temperature = temperature
        self._fallback = HeuristicLLM()
        self.tok = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)
        dtype = torch.float16 if torch.cuda.is_available() else torch.float32
        self.model = AutoModelForCausalLM.from_pretrained(
            model_id,
            torch_dtype=dtype,
            device_map="auto" if torch.cuda.is_available() else None,
            trust_remote_code=True,
        )
        self.device = next(self.model.parameters()).device

    def complete(self, messages: list[Message], tools_schema: str = "") -> LLMResponse:
        chat = []
        system_extra = ""
        if tools_schema:
            system_extra = (
                "\n\nWhen you need a tool, reply ONLY with JSON: "
                '{"name":"tool","arguments":{...}}\n'
                f"Tools:\n{tools_schema}\n"
                "After tool results, answer briefly."
            )
        for m in messages:
            role = m.role if m.role in ("system", "user", "assistant") else "user"
            content = m.content
            if m.role == "tool":
                role, content = "user", f"[tool:{m.name}] {m.content}"
            if role == "system" and system_extra:
                content = content + system_extra
                system_extra = ""
            chat.append({"role": role, "content": content})
        try:
            prompt = self.tok.apply_chat_template(chat, tokenize=False, add_generation_prompt=True)
            inputs = self.tok(prompt, return_tensors="pt").to(self.device)
            out = self.model.generate(
                **inputs,
                max_new_tokens=self.max_tokens,
                do_sample=self.temperature > 0,
                temperature=max(self.temperature, 1e-5),
                pad_token_id=self.tok.eos_token_id,
            )
            gen = out[0][inputs["input_ids"].shape[-1] :]
            text = self.tok.decode(gen, skip_special_tokens=True).strip()
        except Exception:
            return self._fallback.complete(messages, tools_schema)

        calls = self._parse_tool_calls(text)
        if calls:
            return LLMResponse(text="", tool_calls=calls)
        return LLMResponse(text=text, tool_calls=[])

    def _parse_tool_calls(self, text: str) -> list[dict[str, Any]]:
        text = text.strip()
        try:
            return self._norm(json.loads(text))
        except json.JSONDecodeError:
            pass
        calls: list[dict[str, Any]] = []
        for m in TOOL_CALL_RE.finditer(text):
            blob = m.group(1) or m.group(2)
            try:
                calls.extend(self._norm(json.loads(blob)))
            except json.JSONDecodeError:
                continue
        return calls

    def _norm(self, data: Any) -> list[dict[str, Any]]:
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
                out.extend(self._norm(item))
            return out
        return []
