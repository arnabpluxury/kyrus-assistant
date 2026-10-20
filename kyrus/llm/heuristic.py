"""
Heuristic tool-router LLM.

Runs on any phone with almost zero RAM. Routes user intent to tools via
patterns, then formats tool results into readable answers. When a local
GGUF model is available, LocalLLM takes over — this is the safety net.
"""

from __future__ import annotations

import json
import re
from typing import Any

from .base import BaseLLM, LLMResponse, Message


def _last_user(messages: list[Message]) -> str:
    for m in reversed(messages):
        if m.role == "user":
            return m.content.strip()
    return ""


def _tool_results(messages: list[Message]) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for m in messages:
        if m.role == "tool" and m.name:
            out.append((m.name, m.content))
    return out


class HeuristicLLM(BaseLLM):
    name = "heuristic"

    def complete(self, messages: list[Message], tools_schema: str = "") -> LLMResponse:
        results = _tool_results(messages)
        if results:
            return LLMResponse(text=self._synthesize(results), tool_calls=[])

        user = _last_user(messages)
        if not user:
            return LLMResponse(text="Say something and I'll help.", tool_calls=[])

        calls = self._route(user)
        if calls:
            return LLMResponse(text="", tool_calls=calls)

        return LLMResponse(
            text=self._chatty(user),
            tool_calls=[],
        )

    def _route(self, text: str) -> list[dict[str, Any]]:
        t = text.lower().strip()
        calls: list[dict[str, Any]] = []

        # Device / status
        if re.search(r"\b(device|phone|ram|battery|tier|capability|hardware)\b", t):
            calls.append({"name": "device_info", "arguments": {}})
            return calls

        # Time / date
        if re.search(r"\b(time|date|today|clock|timezone)\b", t):
            calls.append({"name": "datetime", "arguments": {}})
            return calls

        # Calculator
        calc_match = re.search(
            r"(?:calc(?:ulate)?|compute|what(?:'s| is)|math)\s+(.+)$",
            t,
            re.I,
        )
        expr = None
        if calc_match:
            expr = calc_match.group(1)
        elif re.fullmatch(r"[\d\.\s\+\-\*\/\(\)%^]+", t.replace("x", "*")):
            expr = t.replace("x", "*")
        if expr:
            calls.append({"name": "calculator", "arguments": {"expression": expr}})
            return calls

        # Notes
        note_save = re.search(r"(?:note|remember this|save note)[:\s]+(.+)", t, re.I)
        if note_save or t.startswith("note "):
            body = note_save.group(1) if note_save else text[5:].strip()
            calls.append({"name": "notes", "arguments": {"action": "add", "text": body}})
            return calls
        if re.search(r"\b(list notes|show notes|my notes)\b", t):
            calls.append({"name": "notes", "arguments": {"action": "list"}})
            return calls

        # Memory
        rem = re.search(r"(?:remember that|remember:)\s+(.+)", t, re.I)
        if rem:
            calls.append({"name": "remember", "arguments": {"fact": rem.group(1)}})
            return calls
        if re.search(r"\b(what do you (know|remember)|recall|my memory)\b", t):
            q = re.sub(r".*?(recall|remember)\s*", "", t, count=1).strip() or ""
            calls.append({"name": "recall", "arguments": {"query": q}})
            return calls

        # Files
        list_m = re.search(r"(?:list files?|ls|dir)\s*(.*)$", t, re.I)
        if list_m and ("list" in t or t.startswith("ls") or "dir" in t):
            path = list_m.group(1).strip() or "."
            calls.append({"name": "file_list", "arguments": {"path": path}})
            return calls
        read_m = re.search(r"(?:read file|cat|open file)\s+(.+)$", t, re.I)
        if read_m:
            calls.append({"name": "file_read", "arguments": {"path": read_m.group(1).strip()}})
            return calls

        # Shell (explicit)
        sh = re.search(r"^(?:run|shell|exec|\$)\s+(.+)$", t, re.I)
        if sh:
            calls.append({"name": "shell", "arguments": {"command": sh.group(1).strip()}})
            return calls

        # Coding / Claude-Code style
        if re.search(r"\b(workspace|coding workspace|project root)\b", t):
            calls.append({"name": "workspace_info", "arguments": {}})
            return calls
        init_m = re.search(
            r"(?:create|scaffold|init|new)\s+(?:a\s+)?(python|web|node|js)?\s*project\s*(?:named\s+|called\s+)?([a-zA-Z0-9_\-]+)?",
            t,
            re.I,
        )
        if init_m or re.search(r"\b(project_init|scaffold)\b", t):
            kind = (init_m.group(1) if init_m and init_m.group(1) else "python") or "python"
            name = (init_m.group(2) if init_m and init_m.group(2) else "app") or "app"
            if kind.lower() in ("js",):
                kind = "node"
            calls.append({"name": "project_init", "arguments": {"name": name, "kind": kind.lower()}})
            return calls
        mkdir_m = re.search(r"(?:mkdir|create (?:folder|directory))\s+(.+)$", t, re.I)
        if mkdir_m:
            calls.append({"name": "mkdir", "arguments": {"path": mkdir_m.group(1).strip()}})
            return calls
        write_m = re.search(r"(?:write file|create file)\s+(\S+)\s+(?:with|containing|:)\s*(.+)$", t, re.I | re.S)
        if write_m:
            calls.append(
                {
                    "name": "write_file",
                    "arguments": {"path": write_m.group(1).strip(), "content": write_m.group(2)},
                }
            )
            return calls
        code_search = re.search(r"(?:search code|grep|find in (?:code|project))\s+(.+)$", t, re.I)
        if code_search:
            calls.append({"name": "search_code", "arguments": {"query": code_search.group(1).strip()}})
            return calls

        # Phone helpers
        if re.search(r"\b(battery|charge level)\b", t):
            calls.append({"name": "battery", "arguments": {}})
            return calls
        if re.search(r"\b(wifi|wi-fi)\b", t) and re.search(r"\b(info|status|ssid)\b", t):
            calls.append({"name": "wifi_info", "arguments": {}})
            return calls
        if re.search(r"\b(clipboard|pasteboard)\b", t) and re.search(r"\b(get|read|show)\b", t):
            calls.append({"name": "clipboard_get", "arguments": {}})
            return calls
        news_m = re.search(r"(?:news(?: about)?|headlines)\s+(.+)$", t, re.I)
        if news_m or t.strip().startswith("news "):
            q = news_m.group(1) if news_m else text[5:].strip()
            calls.append({"name": "web_news", "arguments": {"query": q}})
            return calls

        # Summarize pasted text
        sum_m = re.search(r"(?:summarize|tldr|summary)[:\s]+(.+)", t, re.I | re.S)
        if sum_m and len(sum_m.group(1)) > 40:
            calls.append({"name": "summarize", "arguments": {"text": sum_m.group(1).strip()}})
            return calls

        # Fetch URL
        url_m = re.search(r"https?://\S+", text)
        if url_m and re.search(r"\b(fetch|open|read|summarize)\b", t):
            calls.append({"name": "fetch_url", "arguments": {"url": url_m.group(0)}})
            return calls

        # Web research — questions that need live info
        research_triggers = (
            r"\b(search|research|look up|google|find out|news|who is|what is|"
            r"latest|current|weather|price|how to|explain|tell me about)\b"
        )
        if re.search(research_triggers, t) or text.strip().endswith("?"):
            # Avoid researching pure chat
            if not re.search(r"\b(hi|hello|hey|thanks|thank you|bye)\b", t):
                calls.append({"name": "web_research", "arguments": {"query": text.strip()}})
                return calls

        return calls

    def _synthesize(self, results: list[tuple[str, str]]) -> str:
        parts: list[str] = []
        for name, content in results:
            try:
                data = json.loads(content)
                if isinstance(data, dict) and "error" in data:
                    parts.append(f"**{name}** failed: {data['error']}")
                    continue
                if name == "web_research" and isinstance(data, dict):
                    answer = data.get("answer") or data.get("summary") or ""
                    sources = data.get("sources") or []
                    block = answer.strip() or "No clear answer found."
                    if sources:
                        block += "\n\nSources:"
                        for s in sources[:5]:
                            title = s.get("title") or s.get("url") or "link"
                            url = s.get("url") or ""
                            block += f"\n- {title}" + (f" — {url}" if url else "")
                    parts.append(block)
                    continue
                if name == "calculator" and isinstance(data, dict):
                    parts.append(f"{data.get('expression', '?')} = **{data.get('result')}**")
                    continue
                if name == "datetime" and isinstance(data, dict):
                    parts.append(f"{data.get('local')} ({data.get('timezone', 'local')})")
                    continue
                # Generic pretty dump
                parts.append(self._pretty(data) if not isinstance(data, str) else data)
            except json.JSONDecodeError:
                parts.append(content)
        return "\n\n".join(parts) if parts else "Done."

    def _pretty(self, data: Any) -> str:
        if isinstance(data, dict):
            if "message" in data:
                return str(data["message"])
            if "text" in data:
                return str(data["text"])
            lines = []
            for k, v in data.items():
                if k in ("raw",):
                    continue
                lines.append(f"{k}: {v}")
            return "\n".join(lines)
        if isinstance(data, list):
            return "\n".join(f"- {x}" for x in data[:30])
        return str(data)

    def _chatty(self, user: str) -> str:
        t = user.lower()
        if re.search(r"\b(hi|hello|hey)\b", t):
            return (
                "Hey — I'm Kyrus, your on-device agent.\n"
                "Ask me to research something, calculate, take notes, "
                "check the device, or run a command (I'll ask permission first)."
            )
        if re.search(r"\b(help|commands|what can you do)\b", t):
            return (
                "I lean on tools, not a huge model:\n"
                "- Research / search the web (asks internet permission)\n"
                "- Fetch & summarize URLs\n"
                "- Calculator, notes, memory\n"
                "- Files & shell (permission gated)\n"
                "- Device info / tier features\n"
                "- Voice on high-end + Termux:API\n"
                "- Coding: project_init / write_file / edit_file (sandboxed, Claude Code style)\n"
                "Try: research latest python release  |  create python project named demo"
            )
        return (
            "I couldn't map that to a tool yet. Try being more specific — "
            "e.g. 'research …', 'calculate …', 'note …', or 'device info'."
        )
