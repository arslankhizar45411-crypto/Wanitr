#!/usr/bin/env bash
# Arm64 proot binaries ko jniLibs mein laata hai (agar pehle se maujood na hon).
# Env: PROOT_URL, PROOT_LOADER_URL (optional), STRICT=true to fail when libproot.so is missing.
set -euo pipefail

DIR="app/src/main/jniLibs/arm64-v8a"
STRICT="${STRICT:-false}"
mkdir -p "$DIR"

fetch() {
  local url="$1" dest="$2"
  echo "Downloading $(basename "$dest") ..."
  curl -fsSL --retry 3 --retry-delay 5 "$url" -o "$dest.tmp"
  mv "$dest.tmp" "$dest"
}

if [ ! -f "$DIR/libproot.so" ] && [ -n "${PROOT_URL:-}" ]; then
  fetch "$PROOT_URL" "$DIR/libproot.so"
fi
if [ ! -f "$DIR/libprootloader.so" ] && [ -n "${PROOT_LOADER_URL:-}" ]; then
  fetch "$PROOT_LOADER_URL" "$DIR/libprootloader.so"
fi

# Galat architecture ki binary APK mein jaye to terminal kabhi nahi chalega: pehle hi pakad lo
for bin in "$DIR/libproot.so" "$DIR/libprootloader.so"; do
  [ -f "$bin" ] || continue
  desc="$(file -b "$bin")"
  if ! grep -q "ARM aarch64" <<<"$desc"; then
    echo "::error file=$bin::Not an arm64 ELF binary: $desc"
    exit 1
  fi
  echo "OK: $(basename "$bin") -> $desc"
done

if [ ! -f "$DIR/libproot.so" ]; then
  msg="libproot.so is missing. The APK will build, but the terminal cannot start. Set PROOT_URL (repo variable) or add the file. See README."
  if [ "$STRICT" = "true" ]; then
    echo "::error::$msg"
    exit 1
  fi
  echo "::warning::$msg"
fi
