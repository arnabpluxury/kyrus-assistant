"""Expanded live web tools — research, news, docs lookup (all network-gated)."""

from __future__ import annotations

import re
from typing import Any
from urllib.parse import urlparse

from .registry import Tool, ToolContext


def _ddgs_text(query: str, max_results: int = 6) -> list[dict[str, str]]:
    from duckduckgo_search import DDGS

    out: list[dict[str, str]] = []
    with DDGS() as ddgs:
        for item in ddgs.text(query, max_results=max_results):
            out.append(
                {
                    "title": item.get("title") or "",
                    "url": item.get("href") or item.get("link") or "",
                    "snippet": item.get("body") or item.get("snippet") or "",
                }
            )
    return out


def _ddgs_news(query: str, max_results: int = 5) -> list[dict[str, str]]:
    from duckduckgo_search import DDGS

    out: list[dict[str, str]] = []
    with DDGS() as ddgs:
        for item in ddgs.news(query, max_results=max_results):
            out.append(
                {
                    "title": item.get("title") or "",
                    "url": item.get("url") or item.get("link") or "",
                    "snippet": item.get("body") or item.get("excerpt") or "",
                    "date": str(item.get("date") or ""),
                    "source": str(item.get("source") or ""),
                }
            )
    return out


def _extractive(query: str, results: list[dict[str, str]]) -> str:
    q_terms = {w for w in re.findall(r"[a-z0-9]+", query.lower()) if len(w) > 2}
    scored: list[tuple[float, str]] = []
    for r in results:
        blob = f"{r.get('title', '')}. {r.get('snippet', '')}".strip()
        if not blob:
            continue
        words = set(re.findall(r"[a-z0-9]+", blob.lower()))
        overlap = len(q_terms & words) / max(1, len(q_terms))
        scored.append((overlap, blob))
    scored.sort(key=lambda x: x[0], reverse=True)
    top = [s for _, s in scored[:4]]
    if not top:
        return "No readable snippets."
    return "Based on live web results:\n" + "\n".join(f"- {t}" for t in top)


def tool_web_research(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    query = str(args.get("query") or "").strip()
    if not query:
        return {"error": "Missing query"}
    try:
        results = _ddgs_text(query, max_results=int(args.get("max_results") or 6))
    except Exception as e:
        return {"error": f"Search failed: {e}"}
    if not results:
        return {"error": "No results", "query": query}
    answer = _extractive(query, results)
    return {"query": query, "answer": answer, "summary": answer, "sources": results}


def tool_web_news(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    query = str(args.get("query") or "").strip()
    if not query:
        return {"error": "Missing query"}
    try:
        results = _ddgs_news(query)
    except Exception as e:
        return {"error": f"News search failed: {e}"}
    lines = []
    for r in results:
        lines.append(f"- {r.get('title')} ({r.get('source') or 'source'}) {r.get('url')}")
    return {
        "query": query,
        "answer": "Latest headlines:\n" + "\n".join(lines) if lines else "No news hits.",
        "sources": results,
        "summary": "\n".join(lines),
    }


def tool_fetch_url(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    import requests
    from bs4 import BeautifulSoup

    url = str(args.get("url") or "").strip()
    if not url:
        return {"error": "Missing url"}
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        return {"error": "Only http/https allowed"}
    r = requests.get(url, timeout=20, headers={"User-Agent": "Kyrus/0.2 (on-device; user-permitted)"})
    r.raise_for_status()
    ctype = r.headers.get("content-type", "")
    if "html" in ctype or url.rstrip("/").endswith((".html", ".htm")):
        soup = BeautifulSoup(r.text, "html.parser")
        for tag in soup(["script", "style", "noscript"]):
            tag.decompose()
        text = " ".join(soup.get_text(" ").split())
    else:
        text = r.text
    text = text[:18000]
    return {
        "url": url,
        "status": r.status_code,
        "text": text,
        "message": text[:2000] + ("..." if len(text) > 2000 else ""),
    }


def tool_docs_search(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    """Search programming docs via web (site-biased query)."""
    topic = str(args.get("query") or args.get("topic") or "").strip()
    site = str(args.get("site") or "").strip()
    if not topic:
        return {"error": "Missing query"}
    q = f"site:{site} {topic}" if site else f"{topic} documentation official"
    try:
        results = _ddgs_text(q, max_results=5)
    except Exception as e:
        return {"error": str(e)}
    return {
        "query": q,
        "answer": _extractive(topic, results),
        "sources": results,
        "summary": _extractive(topic, results),
    }


def tool_stackoverflow_search(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
    q = str(args.get("query") or "").strip()
    if not q:
        return {"error": "Missing query"}
    return tool_docs_search({"query": q, "site": "stackoverflow.com"}, ctx)


def web_tools() -> list[Tool]:
    return [
        Tool(
            name="web_research",
            description="Live web search + extractive answer (asks internet permission).",
            parameters={"query": "string", "max_results": "int"},
            fn=tool_web_research,
            requires=["research"],
            network=True,
        ),
        Tool(
            name="web_news",
            description="Live news headlines for a topic.",
            parameters={"query": "string"},
            fn=tool_web_news,
            requires=["research"],
            network=True,
        ),
        Tool(
            name="fetch_url",
            description="Fetch URL text/HTML (readable extract).",
            parameters={"url": "string"},
            fn=tool_fetch_url,
            requires=["research"],
            network=True,
        ),
        Tool(
            name="docs_search",
            description="Search official docs / API references via the web.",
            parameters={"query": "string", "site": "string"},
            fn=tool_docs_search,
            requires=["research", "code"],
            network=True,
        ),
        Tool(
            name="stackoverflow_search",
            description="Search Stack Overflow for programming answers.",
            parameters={"query": "string"},
            fn=tool_stackoverflow_search,
            requires=["research", "code"],
            network=True,
        ),
    ]
