#!/usr/bin/env bash
set -e

DEST="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"

# Official proot arm64 binaries fetch karne ke liye standard source
PROOT_URL="${PROOT_URL:-https://github.com/termux/proot/raw/master/src/libproot.so}"

echo "Downloading official libproot.so..."
curl -L -o "$DEST/libproot.so" "$PROOT_URL" || true

# Check if file is downloaded properly
if [ ! -f "$DEST/libproot.so" ]; then
    echo "Error: libproot.so download nahi ho saki."
    exit 1
fi
