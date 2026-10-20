#!/data/data/com.termux/files/usr/bin/bash
# One-shot Termux install for Kyrus Assistant (GGUF LLM agent)
set -euo pipefail

echo "=== Kyrus Assistant install (Termux) ==="

pkg update -y
pkg install -y python git openssl libxml2 libxslt clang cmake make rust binutils
pkg install -y termux-api || true

cd "$(dirname "$0")"
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python -m pip install huggingface_hub || true
python -m pip install -e . || true

mkdir -p "$HOME/.kyrus/notes" "$HOME/.kyrus/sessions" "$HOME/.kyrus/workspace" models

chmod +x kyrus.sh scripts/*.sh 2>/dev/null || true
BIN="${PREFIX:-/data/data/com.termux/files/usr}/bin"
ln -sf "$(pwd)/kyrus.sh" "$BIN/kyrus" 2>/dev/null || {
  mkdir -p "$HOME/bin"
  ln -sf "$(pwd)/kyrus.sh" "$HOME/bin/kyrus"
  grep -q 'HOME/bin' "$HOME/.bashrc" 2>/dev/null || echo 'export PATH="$HOME/bin:$PATH"' >> "$HOME/.bashrc"
}

echo
echo "Installed runtime."
echo "Download on-device LLM (pick one):"
echo "  python scripts/download_models.py --tier low    # 4GB phones"
echo "  python scripts/download_models.py --tier high   # 8GB+"
echo
echo "Then:  pip install llama-cpp-python && kyrus --status"
echo "Internet/shell tools always ask permission first."
