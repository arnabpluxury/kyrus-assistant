"""Phone / Termux:API helpers — battery, clipboard, share, notify, wifi, location (gated)."""

from __future__ import annotations

import json
import shutil
import subprocess
from typing import Any

from .registry import Tool, ToolContext


def _api(cmd: str, *args: str, timeout: float = 15.0) -> dict[str, Any]:
    if shutil.which(cmd) is None:
        return {"error": f"{cmd} not found — install Termux:API app + pkg install termux-api"}
    try:
        r = subprocess.run([cmd, *args], capture_output=True, text=True, timeout=timeout)
        out = (r.stdout or "").strip()
        if not out:
            return {"ok": r.returncode == 0, "stderr": (r.stderr or "").strip()}
        try:
            return {"data": json.loads(out), "raw": out}
        except json.JSONDecodeError:
            return {"text": out}
    except subprocess.TimeoutExpired:
        return {"error": f"{cmd} timed out"}
    except OSError as e:
        return {"error": str(e)}


def tool_battery(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    return _api("termux-battery-status")


def tool_clipboard_get(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    return _api("termux-clipboard-get")


def tool_clipboard_set(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    text = str(args.get("text") or "")
    if shutil.which("termux-clipboard-set") is None:
        return {"error": "termux-clipboard-set missing"}
    try:
        subprocess.run(["termux-clipboard-set", text], timeout=10, check=False)
        return {"message": "Clipboard updated", "chars": len(text)}
    except OSError as e:
        return {"error": str(e)}


def tool_notify(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    title = str(args.get("title") or "Kyrus")
    content = str(args.get("content") or args.get("text") or "")
    if not content:
        return {"error": "Missing content"}
    return _api("termux-notification", "--title", title, "--content", content)


def tool_toast(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    text = str(args.get("text") or "")
    if not text:
        return {"error": "Missing text"}
    return _api("termux-toast", text)


def tool_share(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    text = str(args.get("text") or "")
    if not text:
        return {"error": "Missing text"}
    return _api("termux-share", "-a", "send", text)


def tool_wifi_info(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    return _api("termux-wifi-connectioninfo")


def tool_telephony(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    return _api("termux-telephony-deviceinfo")


def tool_brightness(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    # read-only info if available; setting brightness needs care
    val = args.get("value")
    if val is None:
        return _api("termux-brightness", "info") if shutil.which("termux-brightness") else {
            "error": "termux-brightness not available"
        }
    return _api("termux-brightness", str(int(val)))


def tool_volume(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    stream = str(args.get("stream") or "music")
    volume = args.get("volume")
    if volume is None:
        return {"error": "Provide volume 0-15 (typical)"}
    return _api("termux-volume", stream, str(int(volume)))


def tool_vibrate(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    ms = str(int(args.get("duration_ms") or 200))
    return _api("termux-vibrate", "-d", ms)


def phone_tools() -> list[Tool]:
    return [
        Tool(name="battery", description="Phone battery status via Termux:API.", parameters={}, fn=tool_battery, requires=["phone"]),
        Tool(name="clipboard_get", description="Read Android clipboard.", parameters={}, fn=tool_clipboard_get, requires=["phone"]),
        Tool(
            name="clipboard_set",
            description="Set Android clipboard text.",
            parameters={"text": "string"},
            fn=tool_clipboard_set,
            requires=["phone"],
        ),
        Tool(
            name="notify",
            description="Show an Android notification.",
            parameters={"title": "string", "content": "string"},
            fn=tool_notify,
            requires=["phone"],
        ),
        Tool(name="toast", description="Show a short toast message.", parameters={"text": "string"}, fn=tool_toast, requires=["phone"]),
        Tool(name="share_text", description="Open Android share sheet with text.", parameters={"text": "string"}, fn=tool_share, requires=["phone"]),
        Tool(name="wifi_info", description="Current Wi‑Fi connection info.", parameters={}, fn=tool_wifi_info, requires=["phone"]),
        Tool(name="telephony_info", description="Basic telephony/device radio info.", parameters={}, fn=tool_telephony, requires=["phone"]),
        Tool(
            name="brightness",
            description="Get/set screen brightness (0-255) via Termux:API.",
            parameters={"value": "int?"},
            fn=tool_brightness,
            requires=["phone"],
        ),
        Tool(
            name="volume",
            description="Set media/ring volume stream.",
            parameters={"stream": "music|ring|notification", "volume": "int"},
            fn=tool_volume,
            requires=["phone"],
        ),
        Tool(
            name="vibrate",
            description="Vibrate the phone for N milliseconds.",
            parameters={"duration_ms": "int"},
            fn=tool_vibrate,
            requires=["phone"],
        ),
    ]
