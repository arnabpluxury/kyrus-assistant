<p align="center">
  <img src="https://img.shields.io/badge/Kyrus-Assistant-176c5b?style=for-the-badge" alt="Kyrus Assistant" />
</p>

<h1 align="center">Kyrus Assistant</h1>

<p align="center">
  Secure <b>on-device LLM agent</b> for Android (Termux) and local CLIs.<br />
  Tiny GGUF brain + powerful tools — coding like Claude Code, live web research, phone APIs.<br />
  Built for <b>4GB phones</b>; unlocks more on <b>8GB+</b>.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Open%20Contribution-292f2d?style=flat-square" alt="license" /></a>
  <img src="https://img.shields.io/badge/platform-Android%20Termux%20%7C%20CLI-b48736?style=flat-square" alt="platform" />
  <img src="https://img.shields.io/badge/LLM-GGUF%20tiered-366f9d?style=flat-square" alt="llm" />
  <img src="https://img.shields.io/badge/security-sandbox%20%2B%20permissions-b54a4a?style=flat-square" alt="security" />
</p>

---

## Why Kyrus

| Need | How |
| --- | --- |
| Real local LLM | Tiered GGUF (`kyrus-low/mid/high.gguf`) — not cloud-only |
| 4GB RAM | 0.5B Q4 + short context + tools |
| 8GB+ / flagships | 1.5B–3B Q4, voice, longer coding |
| Claude Code style | Sandboxed `project_init` / `write_file` / `edit_file` / `run_in_workspace` |
| Live knowledge | `web_research`, `web_news`, `docs_search`, `fetch_url` (asks permission) |
| Phone native | Termux:API — battery, clipboard, notify, wifi, vibrate… |
| Safety | Workspace sandbox, shell deny-list, no secret exfil, jailbreak refusals |
| Speed | Tier presets: low tokens/ctx on weak devices |

Same **Open Contribution** license family as [Kyrus Manager](https://github.com/arnabpluxury/kyrus-manager): personal use free; shared/commercial mods must be open-sourced. See [LICENSE](LICENSE) · [TERMS.md](TERMS.md).

## Quick start (phone)

```bash
# Termux
pkg install python git
cd ~
git clone https://github.com/arnabpluxury/kyrus-assistant.git
cd kyrus-assistant
chmod +x install.sh kyrus.sh
./install.sh

# Download the on-device brain (required for LLM mode)
pip install huggingface_hub
python scripts/download_models.py --tier low   # 4GB phones
# python scripts/download_models.py --tier high # 8GB+

pip install llama-cpp-python   # or see Termux build tips in models/README.md
kyrus
```

```bash
kyrus --status
kyrus -q "create python project named demo"
kyrus -q "research latest Android security bulletin"
```

Internet / shell always prompt: `[y]` once · `[s]` session · `[n]` deny.

## Desktop / GPU training

You have a GPU? Fine-tune the tiny Instruct model on phone+coding+security data:

```bash
pip install -r training/requirements-train.txt
python training/expand_dataset.py
python training/train_lora.py --epochs 2 --merge
```

Details: [training/README.md](training/README.md).

## Features

- **GGUF-first** agent loop with tool JSON calling  
- **Sandbox** `~/.kyrus/workspace` — path escape blocked  
- **Coding tools** — mkdir, write/edit/read, search, patch, run in workspace  
- **Web** — research, news, docs, Stack Overflow, fetch URL  
- **Phone** — battery, clipboard, notify, toast, share, wifi, volume, vibrate  
- **Voice** — Termux STT/TTS on high tier  
- **Memory / notes / calc / device tier detection**

## License

**Kyrus Assistant Source License (Open Contribution)** — non-commercial personal use OK; if you distribute modifications (or use commercially), publish those sources under this license. Full text: [LICENSE](LICENSE).

---

<p align="center">
  <a href="https://github.com/arnabpluxury/kyrus-assistant/issues">Issues</a>
  ·
  <a href="TERMS.md">Terms</a>
  ·
  <a href="https://ko-fi.com/arnabimperiale">Ko-fi</a>
</p>
