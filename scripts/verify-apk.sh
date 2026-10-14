#!/usr/bin/env bash
# APK ke andar zaroori cheezein check karta hai aur summary likhta hai.
# Usage: scripts/verify-apk.sh <apk> [strict=true|false] [output-dir]
set -euo pipefail

APK="${1:?usage: verify-apk.sh <apk> [strict] [outdir]}"
STRICT="${2:-false}"
OUTDIR="${3:-dist}"

fail() {
  echo "::error::$1"
  exit 1
}
warn_or_fail() {
  if [ "$STRICT" = "true" ]; then fail "$1"; fi
  echo "::warning::$1"
}

[ -s "$APK" ] || fail "APK not found or empty: $APK"

LIST="$(unzip -Z1 "$APK")"
grep -qx "classes.dex" <<<"$LIST" || fail "classes.dex APK mein nahi hai (compile/dex step fail hua)"
grep -qx "AndroidManifest.xml" <<<"$LIST" || fail "AndroidManifest.xml APK mein nahi hai"

if grep -qx "lib/arm64-v8a/libproot.so" <<<"$LIST"; then
  echo "libproot.so: included"
else
  warn_or_fail "libproot.so APK mein nahi hai, terminal nahi chalega"
fi
if grep -qx "lib/arm64-v8a/libprootloader.so" <<<"$LIST"; then
  echo "libprootloader.so: included"
else
  echo "::notice::libprootloader.so nahi hai (optional, proot bina loader ke bhi chal sakta hai)"
fi

# Placeholder rootfs settings release/tag builds mein nahi jaani chahiye
if [ "$STRICT" = "true" ]; then
  if unzip -p "$APK" classes.dex | grep -aq "example.com/rootfs"; then
    fail "ROOTFS_URL placeholder hai. GitHub repo variable ROOTFS_URL set karein"
  fi
  if unzip -p "$APK" classes.dex | grep -aq "0000000000000000000000000000000000000000000000000000000000000000"; then
    fail "ROOTFS_SHA256 placeholder hai. GitHub repo variable ROOTFS_SHA256 set karein"
  fi
fi

mkdir -p "$OUTDIR"
DEST="$OUTDIR/$(basename "$APK")"
if [ "$(readlink -f "$APK")" != "$(readlink -f "$DEST")" ]; then
  cp "$APK" "$DEST"
fi
SHA="$(sha256sum "$DEST" | cut -d' ' -f1)"
SIZE="$(du -h "$DEST" | cut -f1)"
echo "$SHA  $(basename "$DEST")" > "$DEST.sha256"
echo "APK OK: $DEST ($SIZE)"
echo "SHA-256: $SHA"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### APK build summary"
    echo "- File: \`$(basename "$DEST")\`"
    echo "- Size: $SIZE"
    echo "- SHA-256: \`$SHA\`"
    echo "- Strict checks: $STRICT"
    echo "- Included: libproot.so=$(grep -qx 'lib/arm64-v8a/libproot.so' <<<"$LIST" && echo yes || echo no)"
  } >> "$GITHUB_STEP_SUMMARY"
fi
