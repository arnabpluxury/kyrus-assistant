"""Voice I/O via Termux:API — only on high-end capable devices."""

from __future__ import annotations

import json
import shutil
import subprocess
from typing import Any


def voice_available(cfg: dict[str, Any], features: list[str]) -> bool:
    mode = (cfg.get("voice", {}).get("enabled") or "auto").lower()
    if mode in ("off", "false", "0", "no"):
        return False
    if "voice" not in features and mode != "on":
        return False
    stt = cfg.get("voice", {}).get("stt_cmd", "termux-speech-to-text")
    tts = cfg.get("voice", {}).get("tts_cmd", "termux-tts-speak")
    return shutil.which(stt) is not None and shutil.which(tts) is not None


class VoiceSession:
    def __init__(self, cfg: dict[str, Any]) -> None:
        v = cfg.get("voice", {})
        self.stt_cmd = v.get("stt_cmd", "termux-speech-to-text")
        self.tts_cmd = v.get("tts_cmd", "termux-tts-speak")

    def listen(self) -> str:
        try:
            r = subprocess.run(
                [self.stt_cmd],
                capture_output=True,
                text=True,
                timeout=60,
            )
            text = (r.stdout or "").strip()
            # Some termux-api builds return JSON
            if text.startswith("{"):
                try:
                    data = json.loads(text)
                    text = str(data.get("transcript") or data.get("text") or text)
                except json.JSONDecodeError:
                    pass
            return text
        except (OSError, subprocess.SubprocessError) as e:
            raise RuntimeError(f"STT failed: {e}") from e

    def speak(self, text: str) -> None:
        text = (text or "").strip()
        if not text:
            return
        # Keep TTS short for responsiveness
        clip = text[:500]
        try:
            subprocess.run([self.tts_cmd, clip], timeout=60, check=False)
        except (OSError, subprocess.SubprocessError):
            pass
