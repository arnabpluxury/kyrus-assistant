"""ReAct-style agent loop — local GGUF + sandboxed tools."""

from __future__ import annotations

import json
from typing import Any, Callable

from ..device.capabilities import DeviceProfile
from ..llm.base import BaseLLM, Message
from ..permissions.gate import PermissionGate
from ..security.sandbox import Sandbox
from ..tools.registry import ToolContext, ToolRegistry
from .memory import MemoryStore


class Agent:
    def __init__(
        self,
        cfg: dict[str, Any],
        profile: DeviceProfile,
        llm: BaseLLM,
        tools: ToolRegistry,
        gate: PermissionGate,
        memory: MemoryStore,
        sandbox: Sandbox | None = None,
        on_event: Callable[[str, str], None] | None = None,
    ) -> None:
        self.cfg = cfg
        self.profile = profile
        self.llm = llm
        self.tools = tools
        self.gate = gate
        self.memory = memory
        self.sandbox = sandbox
        self.on_event = on_event or (lambda kind, msg: None)
        self.max_rounds = int(cfg.get("agent", {}).get("max_tool_rounds", 12))
        self.system_prompt = cfg.get("agent", {}).get("system_prompt", "You are Kyrus.")
        if sandbox is not None:
            self.system_prompt += (
                f"\n\nSandbox workspace root: {sandbox.root}\n"
                "All coding file tools must stay inside this root."
            )
        self.history: list[Message] = [
            Message(role="system", content=self.system_prompt),
        ]

    def reset(self) -> None:
        self.history = [Message(role="system", content=self.system_prompt)]
        self.gate.reset_session()

    def ask(self, user_text: str) -> str:
        user_text = user_text.strip()
        if not user_text:
            return ""

        if self.sandbox is not None:
            warns = self.sandbox.scan_user_text(user_text)
            for w in warns:
                self.on_event("security", w)

        self.history.append(Message(role="user", content=user_text))
        schema = self.tools.schema_text(self.profile)
        ctx = ToolContext(
            cfg=self.cfg,
            profile=self.profile,
            gate=self.gate,
            memory=self.memory,
            extras={"sandbox": self.sandbox} if self.sandbox else {},
        )

        for round_i in range(self.max_rounds):
            self.on_event("think", f"round {round_i + 1}/{self.max_rounds}")
            resp = self.llm.complete(self.history, tools_schema=schema)

            if resp.tool_calls:
                for call in resp.tool_calls:
                    name = call.get("name") or ""
                    args = call.get("arguments") or {}
                    self.on_event("tool", f"{name}({json.dumps(args, ensure_ascii=False)[:120]})")
                    result = self.tools.run(name, args, ctx)
                    payload = json.dumps(result, ensure_ascii=False)
                    # Cap tool payload size for low-RAM phones
                    if len(payload) > 12000:
                        payload = payload[:12000] + '..."}'
                    self.history.append(
                        Message(role="assistant", content=json.dumps(call, ensure_ascii=False))
                    )
                    self.history.append(Message(role="tool", content=payload, name=name))
                continue

            answer = (resp.text or "").strip()
            if not answer:
                self.history.append(
                    Message(
                        role="user",
                        content="(system) Produce a final answer now from tool results or ask a clarifying question.",
                    )
                )
                continue

            self.history.append(Message(role="assistant", content=answer))
            self._trim_history()
            return answer

        return "I hit the tool-round limit. Try a narrower request."

    def _trim_history(self) -> None:
        limit = 16 if self.profile.tier == "low" else 24
        if len(self.history) <= limit:
            return
        system = self.history[0]
        keep = limit - 1
        self.history = [system] + self.history[-keep:]
