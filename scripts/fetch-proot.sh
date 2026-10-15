#!/usr/bin/env bash

DEST="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"

echo "Fetching proot binaries safely..."

# Try downloading from primary source
curl -L -o "$DEST/libproot.so" "https://raw.githubusercontent.com/termux/proot/master/src/libproot.so" || true

# Fallback agar pehla link fail ho jaye
if [ ! -s "$DEST/libproot.so" ] || grep -q -i "404" "$DEST/libproot.so" || grep -q -i "not found" "$DEST/libproot.so"; then
    echo "Trying fallback source..."
    curl -L -o "$DEST/libproot.so" "https://github.com/termux/proot-distro/raw/master/app/src/main/jniLibs/arm64-v8a/libproot.so" || true
fi

ls -la "$DEST"
echo "Fetch script completed successfully."
