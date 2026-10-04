#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Offline test runner for Live AI Reply.
#
# Compiles the platform-independent core (everything that does not import
# android.*, androidx.*, okhttp3 or ML Kit) together with app/src/test and runs the
# JUnit test classes with a reflective runner.
#
# Why: this sandbox (and many CI containers) has a JDK but no Android SDK, so
# `./gradlew test` cannot run. This script executes the *same* test sources against
# the *same* production sources, with no re-implementation and no mocking of the
# logic under test.
#
# Usage:  ./tools/jvm-verify/run-tests.sh
# Needs:  java on PATH and a Kotlin compiler distribution (KOTLIN_HOME), or
#         KOTLIN_COMPILER_JAR pointing at kotlin-compiler.jar.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

OUT="${OUT_DIR:-build/jvm-verify/classes}"
rm -rf "$OUT" && mkdir -p "$OUT"

# Locate a Kotlin compiler.
if [[ -n "${KOTLIN_COMPILER_JAR:-}" && -f "$KOTLIN_COMPILER_JAR" ]]; then
  KLIB="$(dirname "$KOTLIN_COMPILER_JAR")"
elif [[ -n "${KOTLIN_HOME:-}" && -f "$KOTLIN_HOME/lib/kotlin-compiler.jar" ]]; then
  KLIB="$KOTLIN_HOME/lib"
else
  echo "Set KOTLIN_HOME (a kotlin-compiler distribution) or KOTLIN_COMPILER_JAR." >&2
  exit 2
fi

CP="$KLIB/kotlin-stdlib.jar:$KLIB/kotlin-annotations-jvm.jar:$KLIB/annotations-13.0.jar"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

# Platform-independent production packages (no android/androidx/third-party imports).
CORE_PKGS=(conversation adapters ai personas settings storage util security automation engine)
SRC="app/src/main/java"
# A source counts as platform independent when it imports nothing from the Android
# framework, AndroidX, OkHttp or ML Kit. That is the same rule the architecture relies
# on: anything platform specific lives behind an interface implemented elsewhere.
is_core() {
  ! grep -qE '^import (android|androidx|okhttp3|kotlinx\.|com\.google\.(android|mlkit)|javax\.crypto|java\.security\.KeyStore)' "$1"
}

CORE_SOURCES=()
SKIPPED=()
for pkg in "${CORE_PKGS[@]}"; do
  while IFS= read -r -d '' f; do
    if is_core "$f"; then CORE_SOURCES+=("$f"); else SKIPPED+=("$f"); fi
  done < <(find "$SRC/com/liveaireply/app/$pkg" -name '*.kt' -print0)
done
echo "Platform-specific sources excluded from the offline run: ${#SKIPPED[@]}"
printf '  %s\n' "${SKIPPED[@]#$SRC/}"

TEST_SOURCES=()
while IFS= read -r -d '' f; do TEST_SOURCES+=("$f"); done < <(find app/src/test -name '*.kt' -print0)

STUB_SOURCES=()
while IFS= read -r -d '' f; do STUB_SOURCES+=("$f"); done < <(find tools/jvm-verify/junit-stub tools/jvm-verify/TestRunner.kt -name '*.kt' -print0 2>/dev/null || true)

echo "Compiling ${#CORE_SOURCES[@]} core sources, ${#TEST_SOURCES[@]} test sources..."
"$JAVA_BIN" -Xmx1200m -cp "$KLIB/kotlin-compiler.jar:$CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -jvm-target 17 -d "$OUT" \
  "${CORE_SOURCES[@]}" "${TEST_SOURCES[@]}" "${STUB_SOURCES[@]}"

echo "Running tests..."
"$JAVA_BIN" -cp "$OUT:$CP" TestRunnerKt "$OUT" "$SRC"
