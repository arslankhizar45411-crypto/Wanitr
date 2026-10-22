#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
javac -d "$OUT" \
  $(find "$ROOT/app/src/main/java/com/collabcode/app/core" -name '*.java') \
  $(find "$ROOT/tests/core" -name '*.java')
java -cp "$OUT" com.collabcode.app.core.CoreTests
