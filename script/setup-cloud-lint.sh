#!/usr/bin/env bash
# Setup standalone clj-kondo for cloud/remote agentic environments.
# Designed for Ubuntu 24+ (amd64/aarch64). No JVM required.
#
# Usage:
#   ./script/setup-cloud-lint.sh              # install to ~/.local/bin
#   ./script/setup-cloud-lint.sh /usr/local/bin  # install to custom dir
set -euo pipefail

# Same version CI pins (.github/workflows/validate.yml CLJ_KONDO_VERSION).
# When bumping, update the version and both hashes from the release's
# <asset>.sha256 files.
VERSION=2026.07.24
SHA256_AMD64=759f5483d224c42e2444fb88adb3718682182a266de0e180a79df451bb243e9c   # linux-static-amd64.zip
SHA256_AARCH64=75358703531a09fec7ec7feeafddaa7df780ddba69e4a6451e513cd258c18328 # linux-aarch64.zip

INSTALL_DIR="${1:-$HOME/.local/bin}"

case "$(uname -m)" in
  x86_64)        asset="clj-kondo-${VERSION}-linux-static-amd64.zip"; sha="$SHA256_AMD64" ;;
  aarch64|arm64) asset="clj-kondo-${VERSION}-linux-aarch64.zip";      sha="$SHA256_AARCH64" ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL -o "$tmp/kondo.zip" \
  "https://github.com/clj-kondo/clj-kondo/releases/download/v${VERSION}/${asset}"
echo "${sha}  $tmp/kondo.zip" | sha256sum -c -
unzip -oq "$tmp/kondo.zip" -d "$tmp"

mkdir -p "$INSTALL_DIR"
install "$tmp/clj-kondo" "$INSTALL_DIR/clj-kondo"

# Ensure install dir is on PATH
if ! echo "$PATH" | tr ':' '\n' | grep -qx "$INSTALL_DIR"; then
  echo ""
  echo "Add to your PATH if not already present:"
  echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
fi

echo ""
echo "Installed: $("$INSTALL_DIR/clj-kondo" --version)"
echo "Run 'just lint-fast' to verify."
