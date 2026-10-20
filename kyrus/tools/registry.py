"""Tool registry — register, describe, and run tools."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Callable

from ..device.capabilities import DeviceProfile
from ..permissions.gate import PermissionGate


@dataclass
class ToolContext:
    cfg: dict[str, Any]
    profile: DeviceProfile
    gate: PermissionGate
    memory: Any = None
    extras: dict[str, Any] = field(default_factory=dict)


ToolFn = Callable[[dict[str, Any], ToolContext], dict[str, Any]]


@dataclass
class Tool:
    name: str
    description: str
    parameters: dict[str, Any]
    fn: ToolFn
    requires: list[str] = field(default_factory=list)  # feature flags
    network: bool = False
    shell: bool = False


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        self._tools[tool.name] = tool

    def get(self, name: str) -> Tool | None:
        return self._tools.get(name)

    def names(self) -> list[str]:
        return sorted(self._tools.keys())

    def schema_text(self, profile: DeviceProfile | None = None) -> str:
        lines = []
        for name, tool in sorted(self._tools.items()):
            if profile and tool.requires and not all(profile.has(r) for r in tool.requires):
                continue
            params = json.dumps(tool.parameters, ensure_ascii=False)
            flags = []
            if tool.network:
                flags.append("network")
            if tool.shell:
                flags.append("shell")
            flag_s = f" [{', '.join(flags)}]" if flags else ""
            lines.append(f"- {name}{flag_s}: {tool.description}\n  params: {params}")
        return "\n".join(lines)

    def run(self, name: str, arguments: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
        tool = self._tools.get(name)
        if not tool:
            return {"error": f"Unknown tool: {name}"}

        if tool.requires and not all(ctx.profile.has(r) for r in tool.requires):
            return {
                "error": (
                    f"Tool '{name}' needs features {tool.requires}; "
                    f"device tier={ctx.profile.tier} has {ctx.profile.features}"
                )
            }

        if tool.network:
            ok = ctx.gate.allow_network(f"Tool '{name}' wants internet: {arguments}")
            if not ok:
                return {"error": "Internet permission denied by user."}

        if tool.shell:
            cmd = str(arguments.get("command") or arguments)
            ok = ctx.gate.allow_shell(cmd)
            if not ok:
                return {"error": "Shell permission denied by user."}

        try:
            return tool.fn(arguments or {}, ctx)
        except Exception as e:
            return {"error": f"{type(e).__name__}: {e}"}


def build_default_registry() -> ToolRegistry:
    from . import builtin

    reg = ToolRegistry()
    for tool in builtin.all_tools():
        reg.register(tool)
    return reg
