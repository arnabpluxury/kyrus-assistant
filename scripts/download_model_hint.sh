#!/data/data/com.termux/files/usr/bin/bash
# Prints Hugging Face download tips — does not auto-download huge files.
cat <<'EOF'
Kyrus model download tips
=========================

Use a browser or `curl`/`wget` to fetch a small GGUF, e.g. from Hugging Face:

  Qwen2.5-0.5B-Instruct GGUF (search "Qwen2.5-0.5B-Instruct-GGUF")
  Prefer Q4_K_M or IQ4 for phones.

Save as:
  <repo>/models/default.gguf

Then:
  pip install llama-cpp-python
  kyrus --status

Heuristic mode needs no download and already does research/tools.
EOF
