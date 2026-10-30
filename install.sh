#!/data/data/com.termux/files/usr/bin/bash
# One-shot Termux install for Kyrus Assistant (GGUF LLM agent)
# Continues past individual pkg/pip failures; retries + compatibility fixes via install_deps.py
set -uo pipefail

echo "=== Kyrus Assistant install (Termux) ==="

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

pkg_ok() {
  pkg install -y "$@" && return 0
  echo "[warn] batch pkg install failed — installing one-by-one…"
  local failed=()
  local p
  for p in "$@"; do
    if ! pkg install -y "$p"; then
      echo "[warn] pkg failed: $p — will retry after fix"
      failed+=("$p")
    fi
  done
  if ((${#failed[@]})); then
    pkg update -y || true
    for p in "${failed[@]}"; do
      pkg install -y "$p" || echo "[warn] still failed: $p (continuing)"
    done
  fi
  return 0
}

pkg update -y || true
pkg_ok python git openssl libxml2 libxslt clang cmake make rust binutils
pkg install -y termux-api || true

# Prefer system python3 if `python` is missing
if ! command -v python >/dev/null 2>&1; then
  if command -v python3 >/dev/null 2>&1; then
    alias python=python3
  else
    echo "ERROR: python not found after pkg install"
    exit 1
  fi
fi

# Resilient pip: install what works, fix failures, continue with leftovers
python scripts/install_deps.py \
  -r requirements.txt \
  --extra huggingface_hub \
  --editable \
  --retries 3 || true

# Optional GGUF runtime — best effort with OpenMP flags common on Termux
echo "[optional] llama-cpp-python (best-effort)…"
CMAKE_ARGS="${CMAKE_ARGS:--DGGML_OPENMP=ON}" \
  python scripts/install_deps.py --extra "llama-cpp-python>=0.2.90" --retries 2 || true

mkdir -p "$HOME/.kyrus/notes" "$HOME/.kyrus/sessions" "$HOME/.kyrus/workspace" models

chmod +x kyrus.sh scripts/*.sh scripts/*.py 2>/dev/null || true
BIN="${PREFIX:-/data/data/com.termux/files/usr}/bin"
ln -sf "$(pwd)/kyrus.sh" "$BIN/kyrus" 2>/dev/null || {
  mkdir -p "$HOME/bin"
  ln -sf "$(pwd)/kyrus.sh" "$HOME/bin/kyrus"
  grep -q 'HOME/bin' "$HOME/.bashrc" 2>/dev/null || echo 'export PATH="$HOME/bin:$PATH"' >> "$HOME/.bashrc"
}

echo
echo "Installed runtime (partial OK if some optional packages failed)."
echo "Re-run anytime to finish leftovers:"
echo "  python scripts/install_deps.py -r requirements.txt --editable"
echo
echo "Download on-device LLM (pick one):"
echo "  python scripts/download_models.py --tier low    # 4GB phones"
echo "  python scripts/download_models.py --tier high   # 8GB+"
echo
echo "Then:  kyrus --status"
echo "Internet/shell tools always ask permission first."
