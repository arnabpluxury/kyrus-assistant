"""
Claude Code–style workspace tools — sandboxed file/project ops on the phone.

All paths resolve under SecurityPolicy.workspace_root (default ~/.kyrus/workspace).
"""

from __future__ import annotations

import os
import re
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any

from ..security.sandbox import Sandbox, SandboxError
from .registry import Tool, ToolContext


def _sandbox(ctx: ToolContext) -> Sandbox:
    sb = ctx.extras.get("sandbox")
    if sb is None:
        raise RuntimeError("Sandbox not attached to ToolContext")
    return sb


def tool_workspace_info(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    sb = _sandbox(ctx)
    root = sb.root
    files = 0
    dirs = 0
    for p in root.rglob("*"):
        if p.is_file():
            files += 1
        elif p.is_dir():
            dirs += 1
        if files + dirs > 5000:
            break
    return {
        "workspace": str(root),
        "files_seen": files,
        "dirs_seen": dirs,
        "message": f"Workspace: {root} ({files} files, {dirs} dirs scanned)",
    }


def tool_mkdir(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    sb = _sandbox(ctx)
    path = sb.resolve(str(args.get("path") or ""))
    path.mkdir(parents=True, exist_ok=True)
    return {"path": str(path), "message": f"Created directory {path}"}


def tool_write_file(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    sb = _sandbox(ctx)
    path = sb.resolve(str(args.get("path") or ""))
    content = str(args.get("content") or "")
    sb.assert_write_size(content)
    if not ctx.gate.allow_write(str(path)):
        return {"error": "Write permission denied"}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return {
        "path": str(path),
        "bytes": len(content.encode("utf-8")),
        "message": f"Wrote {path}",
    }


def tool_read_file(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    sb = _sandbox(ctx)
    try:
        path = sb.resolve(str(args.get("path") or ""), must_exist=True)
    except SandboxError as e:
        return {"error": str(e)}
    if not path.is_file():
        return {"error": f"Not a file: {path}"}
    if path.stat().st_size > sb.policy.max_file_bytes:
        return {"error": f"File too large (>{sb.policy.max_file_bytes} bytes)"}
    text = path.read_text(encoding="utf-8", errors="replace")
    start = int(args.get("start_line") or 1)
    limit = int(args.get("limit") or 0)
    lines = text.splitlines()
    if start < 1:
        start = 1
    chunk = lines[start - 1 :] if not limit else lines[start - 1 : start - 1 + limit]
    numbered = "\n".join(f"{i + start}| {ln}" for i, ln in enumerate(chunk))
    return {
        "path": str(path),
        "total_lines": len(lines),
        "text": numbered if (start > 1 or limit) else text[: sb.policy.max_file_bytes],
        "message": f"Read {path} ({len(lines)} lines)",
    }


def tool_list_dir(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    sb = _sandbox(ctx)
    try:
        path = sb.resolve(str(args.get("path") or "."), must_exist=True)
    except SandboxError as e:
        return {"error": str(e)}
    if not path.is_dir():
        return {"error": f"Not a directory: {path}"}
    entries = []
    for child in sorted(path.iterdir())[:200]:
        entries.append(
            {
                "name": child.name,
                "type": "dir" if child.is_dir() else "file",
                "size": child.stat().st_size if child.is_file() else 0,
            }
        )
    return {"path": str(path), "entries": entries}


def tool_edit_file(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    """Search/replace edit inside a sandboxed file (Claude Code style)."""
    sb = _sandbox(ctx)
    try:
        path = sb.resolve(str(args.get("path") or ""), must_exist=True)
    except SandboxError as e:
        return {"error": str(e)}
    old = str(args.get("old_string") or "")
    new = str(args.get("new_string") if args.get("new_string") is not None else "")
    replace_all = bool(args.get("replace_all", False))
    if not old:
        return {"error": "old_string required"}
    text = path.read_text(encoding="utf-8", errors="replace")
    count = text.count(old)
    if count == 0:
        return {"error": "old_string not found", "path": str(path)}
    if count > 1 and not replace_all:
        return {"error": f"old_string found {count} times — set replace_all=true or make it unique"}
    if not ctx.gate.allow_write(str(path)):
        return {"error": "Write permission denied"}
    updated = text.replace(old, new) if replace_all else text.replace(old, new, 1)
    sb.assert_write_size(updated)
    path.write_text(updated, encoding="utf-8")
    return {"path": str(path), "replacements": count if replace_all else 1, "message": f"Edited {path}"}


def tool_delete_path(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    sb = _sandbox(ctx)
    try:
        path = sb.resolve(str(args.get("path") or ""), must_exist=True)
    except SandboxError as e:
        return {"error": str(e)}
    if not ctx.gate.allow_write(str(path)):
        return {"error": "Delete permission denied"}
    if path.is_dir():
        # Only empty dirs or require recursive flag
        if any(path.iterdir()) and not bool(args.get("recursive")):
            return {"error": "Directory not empty — pass recursive=true"}
        if bool(args.get("recursive")):
            import shutil

            shutil.rmtree(path)
        else:
            path.rmdir()
    else:
        path.unlink()
    return {"path": str(path), "message": f"Deleted {path}"}


def tool_apply_patch(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    """
    Apply a simple unified-diff-like patch to one file.
    Format: lines starting with ' ' keep, '-' remove, '+' add (relative to old block).
    For reliability we prefer edit_file; this handles multi-hunk content replacement.
    """
    sb = _sandbox(ctx)
    try:
        path = sb.resolve(str(args.get("path") or ""), must_exist=True)
    except SandboxError as e:
        return {"error": str(e)}
    content = str(args.get("content") or "")
    if not content:
        return {"error": "Missing content (full new file body)"}
    sb.assert_write_size(content)
    if not ctx.gate.allow_write(str(path)):
        return {"error": "Write permission denied"}
    path.write_text(content, encoding="utf-8")
    return {"path": str(path), "message": f"Patched (full rewrite) {path}"}


def tool_project_init(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    """Scaffold a small project inside the workspace (like starting a Claude Code session)."""
    sb = _sandbox(ctx)
    name = re.sub(r"[^a-zA-Z0-9_\-]", "_", str(args.get("name") or "app").strip()) or "app"
    kind = (args.get("kind") or "python").lower()
    root = sb.resolve(name)
    if root.exists() and any(root.iterdir()):
        return {"error": f"Project folder already exists and is not empty: {root}"}
    if not ctx.gate.allow_write(str(root)):
        return {"error": "Write permission denied"}

    created: list[str] = []

    def w(rel: str, body: str) -> None:
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")
        created.append(str(p))

    if kind in ("python", "py"):
        w("README.md", f"# {name}\n\nScaffolded by Kyrus.\n")
        w("main.py", 'def main():\n    print("hello from Kyrus")\n\n\nif __name__ == "__main__":\n    main()\n')
        w("requirements.txt", "# add deps here\n")
    elif kind in ("web", "html"):
        w("README.md", f"# {name}\n\nStatic web scaffold.\n")
        w(
            "index.html",
            f"<!doctype html>\n<html><head><meta charset=utf-8><title>{name}</title>"
            f"<link rel=stylesheet href=style.css></head>"
            f"<body><h1>{name}</h1><script src=app.js></script></body></html>\n",
        )
        w("style.css", "body{font-family:system-ui;margin:2rem;}\n")
        w("app.js", "console.log('kyrus');\n")
    elif kind in ("node", "js"):
        w("README.md", f"# {name}\n")
        w("package.json", '{\n  "name": "%s",\n  "version": "0.1.0",\n  "main": "index.js"\n}\n' % name)
        w("index.js", "console.log('hello from Kyrus');\n")
    else:
        w("README.md", f"# {name}\n\nkind={kind}\n")

    stamp = datetime.now().isoformat(timespec="seconds")
    w(".kyrus-project.json", f'{{"name":"{name}","kind":"{kind}","created":"{stamp}"}}\n')
    return {"project": str(root), "files": created, "message": f"Initialized {kind} project at {root}"}


def tool_run_in_workspace(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    """Run a command with cwd forced to workspace (still permission + deny-list gated)."""
    sb = _sandbox(ctx)
    cmd = str(args.get("command") or "").strip()
    try:
        sb.check_shell(cmd)
    except SandboxError as e:
        return {"error": str(e)}
    if not ctx.gate.allow_shell(cmd):
        return {"error": "Shell permission denied"}
    cwd_arg = str(args.get("cwd") or ".")
    try:
        cwd = sb.resolve(cwd_arg, must_exist=True)
    except SandboxError as e:
        return {"error": str(e)}
    try:
        r = subprocess.run(
            cmd,
            shell=True,
            capture_output=True,
            text=True,
            timeout=60,
            cwd=str(cwd),
            env={**os.environ, "KYRUS_WORKSPACE": str(sb.root)},
        )
        return {
            "exit_code": r.returncode,
            "stdout": (r.stdout or "")[-6000:],
            "stderr": (r.stderr or "")[-3000:],
            "cwd": str(cwd),
            "message": (r.stdout or r.stderr or f"exit {r.returncode}")[-2000:],
        }
    except subprocess.TimeoutExpired:
        return {"error": "Timed out (60s)"}


def tool_search_code(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    sb = _sandbox(ctx)
    query = str(args.get("query") or "").strip()
    if not query:
        return {"error": "Missing query"}
    root = sb.resolve(str(args.get("path") or "."))
    if not root.exists():
        return {"error": f"Not found: {root}"}
    hits: list[dict[str, Any]] = []
    patterns = ("*.py", "*.js", "*.ts", "*.tsx", "*.java", "*.kt", "*.md", "*.json", "*.html", "*.css", "*.sh")
    files: list[Path] = []
    if root.is_file():
        files = [root]
    else:
        for pat in patterns:
            files.extend(root.rglob(pat))
    for f in files[:400]:
        try:
            if f.stat().st_size > sb.policy.max_file_bytes:
                continue
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for i, line in enumerate(text.splitlines(), 1):
            if query.lower() in line.lower():
                hits.append({"file": str(f.relative_to(sb.root)), "line": i, "text": line.strip()[:200]})
                if len(hits) >= 40:
                    return {"hits": hits, "message": f"{len(hits)}+ matches (truncated)"}
    return {"hits": hits, "message": f"{len(hits)} match(es)"}


def coding_tools() -> list[Tool]:
    return [
        Tool(
            name="workspace_info",
            description="Show sandboxed coding workspace root and file counts.",
            parameters={},
            fn=tool_workspace_info,
            requires=["code"],
        ),
        Tool(
            name="project_init",
            description="Scaffold a new project (python|web|node) inside the sandbox.",
            parameters={"name": "string", "kind": "python|web|node"},
            fn=tool_project_init,
            requires=["code"],
        ),
        Tool(
            name="mkdir",
            description="Create a directory inside the workspace sandbox.",
            parameters={"path": "string"},
            fn=tool_mkdir,
            requires=["code"],
        ),
        Tool(
            name="write_file",
            description="Create/overwrite a file in the sandbox (asks write permission).",
            parameters={"path": "string", "content": "string"},
            fn=tool_write_file,
            requires=["code"],
        ),
        Tool(
            name="read_file",
            description="Read a sandboxed file; optional start_line/limit.",
            parameters={"path": "string", "start_line": "int", "limit": "int"},
            fn=tool_read_file,
            requires=["code"],
        ),
        Tool(
            name="list_dir",
            description="List a directory inside the sandbox.",
            parameters={"path": "string"},
            fn=tool_list_dir,
            requires=["code"],
        ),
        Tool(
            name="edit_file",
            description="Search/replace edit a sandboxed file (unique old_string or replace_all).",
            parameters={"path": "string", "old_string": "string", "new_string": "string", "replace_all": "bool"},
            fn=tool_edit_file,
            requires=["code"],
        ),
        Tool(
            name="delete_path",
            description="Delete a file or directory in the sandbox.",
            parameters={"path": "string", "recursive": "bool"},
            fn=tool_delete_path,
            requires=["code"],
        ),
        Tool(
            name="apply_patch",
            description="Rewrite a sandboxed file with full new content.",
            parameters={"path": "string", "content": "string"},
            fn=tool_apply_patch,
            requires=["code"],
        ),
        Tool(
            name="search_code",
            description="Search text across sandboxed source files.",
            parameters={"query": "string", "path": "string"},
            fn=tool_search_code,
            requires=["code"],
        ),
        Tool(
            name="run_in_workspace",
            description="Run a shell command with cwd inside the sandbox (permission + deny-list).",
            parameters={"command": "string", "cwd": "string"},
            fn=tool_run_in_workspace,
            requires=["code", "shell"],
            shell=True,
        ),
    ]
