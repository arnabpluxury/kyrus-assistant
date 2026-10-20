"""Interactive REPL — like chatting in a terminal on your phone."""

from __future__ import annotations

from typing import Any

from rich.console import Console
from rich.markdown import Markdown
from rich.panel import Panel

from .. import __app_name__, __version__
from ..agent.core import Agent
from ..device.capabilities import DeviceProfile, suggest_llm_settings
from ..voice.assistant import VoiceSession, voice_available

console = Console()


def _banner(profile: DeviceProfile, llm_status: str) -> None:
    console.print(
        Panel.fit(
            f"[bold]{__app_name__}[/bold] v{__version__}\n"
            f"{profile.summary()}\n"
            f"{llm_status}\n"
            "Type a request · [bold]/help[/bold] · [bold]/voice[/bold] · [bold]/reset[/bold] · [bold]/quit[/bold]",
            title="on-device agent",
            border_style="cyan",
        )
    )
    for n in profile.notes:
        console.print(f"[dim]• {n}[/dim]")


def run_repl(
    agent: Agent,
    profile: DeviceProfile,
    cfg: dict[str, Any],
    llm_status: str,
) -> None:
    _banner(profile, llm_status)
    hints = suggest_llm_settings(profile)
    console.print(f"[dim]LLM tip: {hints['hint']}[/dim]\n")

    voice: VoiceSession | None = None
    if voice_available(cfg, profile.features):
        voice = VoiceSession(cfg)
        console.print("[green]Voice ready[/green] — use /voice to talk.\n")

    def on_event(kind: str, msg: str) -> None:
        if kind == "tool":
            console.print(f"  [magenta]-> tool[/magenta] {msg}")
        elif kind == "think":
            console.print(f"  [dim]… {msg}[/dim]")

    agent.on_event = on_event

    while True:
        try:
            line = console.input("[bold cyan]you>[/bold cyan] ").strip()
        except (EOFError, KeyboardInterrupt):
            console.print("\nBye.")
            break

        if not line:
            continue

        low = line.lower()
        if low in ("/q", "/quit", "/exit", "quit", "exit"):
            console.print("Bye.")
            break
        if low in ("/help", "help"):
            console.print(
                Markdown(
                    """
### Commands
- `/help` — this text
- `/voice` — speak a request (high-end + Termux:API)
- `/reset` — clear chat + session permissions
- `/perms` — reset remembered allow/deny for this session
- `/status` — device + LLM status
- `/quit` — exit

### Examples
- `research latest Android security bulletin`
- `create python project named demo`
- `write file demo/hello.py with print('hi')`
- `news about Termux`
- `calculate 128 * 0.15`
- `device info`
- `run uname -a`  (asks shell permission)
Coding stays inside the sandbox workspace (`kyrus --workspace`).
"""
                )
            )
            continue
        if low == "/reset":
            agent.reset()
            console.print("[yellow]Session reset.[/yellow]")
            continue
        if low == "/perms":
            agent.gate.reset_session()
            console.print("[yellow]Permission session cleared.[/yellow]")
            continue
        if low == "/status":
            console.print(profile.summary())
            console.print(llm_status)
            console.print(f"features: {', '.join(profile.features)}")
            continue
        if low == "/voice":
            if not voice:
                console.print("[red]Voice not available on this device/setup.[/red]")
                continue
            console.print("[cyan]Listening…[/cyan]")
            try:
                line = voice.listen()
            except RuntimeError as e:
                console.print(f"[red]{e}[/red]")
                continue
            if not line:
                console.print("[dim]Heard nothing.[/dim]")
                continue
            console.print(f"[bold cyan]you>[/bold cyan] {line}")

        console.print("[dim]kyrus…[/dim]")
        answer = agent.ask(line)
        console.print()
        console.print(Panel(Markdown(answer or "_empty_"), title="kyrus", border_style="green"))
        console.print()

        if voice and low == "/voice":
            voice.speak(answer)
