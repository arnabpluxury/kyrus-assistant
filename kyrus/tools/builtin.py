"""Built-in tools — the 'smartness' layer for a tiny local brain."""

from __future__ import annotations

import ast
import json
import operator
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .registry import Tool, ToolContext


# ── Safe calculator ──────────────────────────────────────────────────────────

_OPS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos,
}


def _eval_ast(node: ast.AST) -> float:
    if isinstance(node, ast.Expression):
        return _eval_ast(node.body)
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return float(node.value)
    if isinstance(node, ast.Num):  # py<3.8
        return float(node.n)
    if isinstance(node, ast.BinOp) and type(node.op) in _OPS:
        return _OPS[type(node.op)](_eval_ast(node.left), _eval_ast(node.right))
    if isinstance(node, ast.UnaryOp) and type(node.op) in _OPS:
        return _OPS[type(node.op)](_eval_ast(node.operand))
    raise ValueError("Unsupported expression")


def tool_calculator(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    expr = str(args.get("expression") or "").strip().replace("^", "**").replace("x", "*")
    if not expr:
        return {"error": "Missing expression"}
    try:
        tree = ast.parse(expr, mode="eval")
        result = _eval_ast(tree)
        return {"expression": expr, "result": result}
    except Exception as e:
        return {"error": f"Invalid math: {e}"}


# ── Date/time ────────────────────────────────────────────────────────────────

def tool_datetime(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    now = datetime.now().astimezone()
    utc = datetime.now(timezone.utc)
    return {
        "local": now.strftime("%Y-%m-%d %H:%M:%S %Z"),
        "iso": now.isoformat(),
        "utc": utc.strftime("%Y-%m-%d %H:%M:%S UTC"),
        "timezone": str(now.tzinfo),
    }


# ── Device info ──────────────────────────────────────────────────────────────

def tool_device_info(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    p = ctx.profile
    battery = None
    if p.has_termux_api:
        try:
            raw = subprocess.run(
                ["termux-battery-status"],
                capture_output=True,
                text=True,
                timeout=5,
            )
            if raw.returncode == 0 and raw.stdout.strip():
                battery = json.loads(raw.stdout)
        except Exception:
            battery = None
    return {
        "tier": p.tier,
        "ram_mb": p.ram_mb,
        "cpu_cores": p.cpu_cores,
        "free_disk_mb": p.free_disk_mb,
        "is_termux": p.is_termux,
        "has_termux_api": p.has_termux_api,
        "features": p.features,
        "notes": p.notes,
        "battery": battery,
        "message": p.summary(),
    }


# ── Notes ────────────────────────────────────────────────────────────────────

def _notes_dir(ctx: ToolContext) -> Path:
    d = Path(ctx.cfg.get("paths", {}).get("notes_dir", os.path.expanduser("~/.kyrus/notes")))
    d.mkdir(parents=True, exist_ok=True)
    return d


def tool_notes(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    action = (args.get("action") or "add").lower()
    text = str(args.get("text") or "").strip()
    nd = _notes_dir(ctx)

    if action == "list":
        files = sorted(nd.glob("*.txt"), key=lambda p: p.stat().st_mtime, reverse=True)
        items = []
        for f in files[:20]:
            preview = f.read_text(encoding="utf-8", errors="replace")[:120].replace("\n", " ")
            items.append({"file": f.name, "preview": preview})
        return {"count": len(items), "notes": items, "message": f"{len(items)} note(s)"}

    if action in ("add", "save", "write"):
        if not text:
            return {"error": "Empty note"}
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        path = nd / f"note_{stamp}.txt"
        path.write_text(text + "\n", encoding="utf-8")
        return {"saved": str(path), "message": f"Saved note → {path.name}"}

    if action == "search":
        q = text.lower()
        hits = []
        for f in nd.glob("*.txt"):
            body = f.read_text(encoding="utf-8", errors="replace")
            if q in body.lower():
                hits.append({"file": f.name, "snippet": body[:200]})
        return {"hits": hits, "message": f"{len(hits)} match(es)"}

    return {"error": f"Unknown notes action: {action}"}


# ── Memory ───────────────────────────────────────────────────────────────────

def tool_remember(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    fact = str(args.get("fact") or "").strip()
    if not fact:
        return {"error": "Nothing to remember"}
    if ctx.memory is None:
        return {"error": "Memory store unavailable"}
    ctx.memory.add(fact)
    return {"message": f"Remembered: {fact}"}


def tool_recall(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    if ctx.memory is None:
        return {"error": "Memory store unavailable"}
    query = str(args.get("query") or "").strip()
    items = ctx.memory.search(query) if query else ctx.memory.all()
    return {"facts": items, "message": "\n".join(f"- {x}" for x in items) or "Nothing stored yet."}


# ── Files ────────────────────────────────────────────────────────────────────

def _safe_path(raw: str) -> Path:
    p = Path(os.path.expanduser(raw)).resolve()
    return p


def tool_file_list(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    path = _safe_path(str(args.get("path") or "."))
    if not path.exists():
        return {"error": f"Not found: {path}"}
    if not path.is_dir():
        return {"error": f"Not a directory: {path}"}
    entries = []
    for child in sorted(path.iterdir())[:100]:
        kind = "dir" if child.is_dir() else "file"
        size = child.stat().st_size if child.is_file() else 0
        entries.append({"name": child.name, "type": kind, "size": size})
    return {"path": str(path), "entries": entries}


def tool_file_read(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    path = _safe_path(str(args.get("path") or ""))
    if not path.exists() or not path.is_file():
        return {"error": f"File not found: {path}"}
    if path.stat().st_size > 512_000:
        return {"error": "File too large (>512KB) for low-RAM read"}
    text = path.read_text(encoding="utf-8", errors="replace")
    return {"path": str(path), "text": text[:20000], "chars": len(text)}


def tool_file_write(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    path = _safe_path(str(args.get("path") or ""))
    content = str(args.get("content") or "")
    if not ctx.gate.allow_write(str(path)):
        return {"error": "Write permission denied"}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return {"path": str(path), "bytes": len(content.encode("utf-8")), "message": f"Wrote {path}"}


# ── Shell ────────────────────────────────────────────────────────────────────

def tool_shell(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    if not ctx.profile.has("shell"):
        return {"error": "Shell disabled on this device tier"}
    cmd = str(args.get("command") or "").strip()
    if not cmd:
        return {"error": "Empty command"}
    sb = ctx.extras.get("sandbox")
    if sb is not None:
        try:
            sb.check_shell(cmd)
        except Exception as e:
            return {"error": str(e)}
    else:
        blocked = ("rm -rf /", "mkfs", ":(){", "dd if=", ">/dev/sd")
        low = cmd.lower()
        if any(b in low for b in blocked):
            return {"error": "Command blocked as potentially destructive"}
    try:
        r = subprocess.run(
            cmd,
            shell=True,
            capture_output=True,
            text=True,
            timeout=30,
            cwd=os.path.expanduser("~"),
        )
        out = (r.stdout or "")[-4000:]
        err = (r.stderr or "")[-2000:]
        return {
            "exit_code": r.returncode,
            "stdout": out,
            "stderr": err,
            "message": out or err or f"exit {r.returncode}",
        }
    except subprocess.TimeoutExpired:
        return {"error": "Command timed out (30s)"}


# ── Summarize / translate (local extractive, no cloud) ───────────────────────

def tool_summarize(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    text = str(args.get("text") or "").strip()
    if not text:
        return {"error": "Missing text"}
    # Sentence split + score by length/position (ultra light)
    sentences = re.split(r"(?<=[.!?])\s+", text)
    sentences = [s.strip() for s in sentences if len(s.strip()) > 20]
    if not sentences:
        return {"summary": text[:400], "message": text[:400]}
    scored = []
    for i, s in enumerate(sentences):
        score = min(len(s), 280) / 280.0
        score += 0.3 if i < 3 else 0
        scored.append((score, s))
    scored.sort(key=lambda x: x[0], reverse=True)
    pick = [s for _, s in scored[: min(5, len(scored))]]
    # Restore rough original order
    pick.sort(key=lambda s: sentences.index(s))
    summary = " ".join(pick)
    return {"summary": summary, "message": summary}


def tool_translate_simple(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    """Offline stub — points user to research/tooling; keeps agent honest."""
    text = str(args.get("text") or "").strip()
    target = str(args.get("target") or "en").strip()
    if not text:
        return {"error": "Missing text"}
    return {
        "message": (
            f"On-device neural translation isn't bundled (keeps install tiny). "
            f"Use web_research for “translate to {target}: {text[:80]}…” "
            f"or install a dedicated Termux translate package."
        ),
        "text": text,
        "target": target,
    }


def all_tools() -> list[Tool]:
    from .coding import coding_tools
    from .phone import phone_tools
    from .web import web_tools

    core = [
        Tool(
            name="calculator",
            description="Evaluate a math expression safely.",
            parameters={"expression": "string"},
            fn=tool_calculator,
            requires=["calc"],
        ),
        Tool(
            name="datetime",
            description="Current local and UTC date/time.",
            parameters={},
            fn=tool_datetime,
        ),
        Tool(
            name="device_info",
            description="Phone tier, RAM, features, optional battery.",
            parameters={},
            fn=tool_device_info,
        ),
        Tool(
            name="notes",
            description="Add/list/search local notes.",
            parameters={"action": "add|list|search", "text": "string"},
            fn=tool_notes,
            requires=["notes"],
        ),
        Tool(
            name="remember",
            description="Store a lasting fact in local memory.",
            parameters={"fact": "string"},
            fn=tool_remember,
            requires=["memory"],
        ),
        Tool(
            name="recall",
            description="Recall stored facts; optional query filter.",
            parameters={"query": "string"},
            fn=tool_recall,
            requires=["memory"],
        ),
        Tool(
            name="file_list",
            description="List files in a directory (legacy home paths).",
            parameters={"path": "string"},
            fn=tool_file_list,
            requires=["files"],
        ),
        Tool(
            name="file_read",
            description="Read a text file (legacy; prefer read_file in sandbox).",
            parameters={"path": "string"},
            fn=tool_file_read,
            requires=["files"],
        ),
        Tool(
            name="file_write",
            description="Write text to a file (asks permission).",
            parameters={"path": "string", "content": "string"},
            fn=tool_file_write,
            requires=["files"],
        ),
        Tool(
            name="shell",
            description="Run a shell command (asks permission + security deny-list).",
            parameters={"command": "string"},
            fn=tool_shell,
            requires=["shell"],
            shell=True,
        ),
        Tool(
            name="summarize",
            description="Extractive summary of pasted text (offline).",
            parameters={"text": "string"},
            fn=tool_summarize,
        ),
        Tool(
            name="translate_simple",
            description="Guidance for translation without heavy offline models.",
            parameters={"text": "string", "target": "string"},
            fn=tool_translate_simple,
        ),
    ]
    return web_tools() + coding_tools() + phone_tools() + core
