#!/usr/bin/env bash
set -e

DEST="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"

echo "Downloading official arm64 proot binaries..."
# Termux official packages mirror se working so binaries download karein
curl -L -o "$DEST/libproot.so" "https://raw.githubusercontent.com/termux/proot-distro/master/app/src/main/jniLibs/arm64-v8a/libproot.so" || true

# Agar file size bohot choti hai (yani 404 error page hai), toh fallback link use karein
if [ ! -s "$DEST/libproot.so" ] || grep -q "404" "$DEST/libproot.so"; then
  echo "Using fallback source for libproot.so..."
  curl -L -o "$DEST/libproot.so" "https://github.com/termux/proot/raw/master/src/libproot.so"
fi

ls -la "$DEST"
