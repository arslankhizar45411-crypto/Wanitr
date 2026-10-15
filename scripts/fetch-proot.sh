#!/usr/bin/env bash
set -e

DEST="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"

echo "Downloading official compiled proot binary..."

# Termux ke official package repository se raw compiled binary fetch karein
curl -L -o "$DEST/libproot.so" "https://raw.githubusercontent.com/termux/proot/master/src/libproot.so"

# Check karein ke file text ya 404 page toh nahi ban gayi
if grep -q "html" "$DEST/libproot.so" || grep -q "404" "$DEST/libproot.so"; then
    echo "ERROR: Downloaded file is a text/HTML page, not a binary!"
    exit 1
fi

echo "Binary successfully downloaded and verified."
