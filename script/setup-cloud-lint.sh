#!/usr/bin/env bash
# Setup standalone clj-kondo for cloud/remote agentic environments.
# Designed for Ubuntu 24+ (amd64/aarch64). No JVM required.
#
# Usage:
#   ./script/setup-cloud-lint.sh              # install latest to ~/.local/bin
#   ./script/setup-cloud-lint.sh /usr/local/bin  # install to custom dir
set -euo pipefail

INSTALL_DIR="${1:-$HOME/.local/bin}"

mkdir -p "$INSTALL_DIR"

# Use the official clj-kondo install script
curl -sLO https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo
chmod +x install-clj-kondo
./install-clj-kondo --dir "$INSTALL_DIR"
rm -f install-clj-kondo

# Ensure install dir is on PATH
if ! echo "$PATH" | tr ':' '\n' | grep -qx "$INSTALL_DIR"; then
  echo ""
  echo "Add to your PATH if not already present:"
  echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
fi

echo ""
echo "Installed: $("$INSTALL_DIR/clj-kondo" --version)"
echo "Run 'just lint-fast' to verify."
