#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Inspects built APKs on a machine that has the Android SDK, and publishes what
# it finds in three places:
#
#   dist/BUILD-REPORT.txt       full detail (artifact + GitHub Release asset)
#   dist/apk-identity.txt       package/version/sdk/size/sha256/signature
#   dist/apk-permissions.txt    every permission declared in the built APK
#   dist/apk-components.txt     merged-manifest components and their exposure
#   $GITHUB_STEP_SUMMARY        the whole report, for the run page
#   stdout                      ::notice annotations, so the facts are readable
#                               from the API without downloading the artifact
#
# Usage: tools/ci/inspect-apks.sh app/build/outputs/apk/*/*.apk
#
# Requires: aapt2 + apksigner (ANDROID_HOME / ANDROID_SDK_ROOT build-tools) and
# python3 for tools/ci/manifest-summary.py. Missing tools are reported, not
# silently skipped.
# ---------------------------------------------------------------------------
set -uo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <apk> [apk ...]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
build_tools="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -n1)"
aapt2="${build_tools}aapt2"
apksigner="${build_tools}apksigner"
manifest_summary="$ROOT/tools/ci/manifest-summary.py"

for tool in "$aapt2" "$apksigner"; do
  [ -x "$tool" ] || { echo "warning: $tool not found (ANDROID_HOME=$sdk)" >&2; }
done

mkdir -p dist
report=dist/BUILD-REPORT.txt
identity=dist/apk-identity.txt
permissions=dist/apk-permissions.txt
components=dist/apk-components.txt
components_summary=dist/apk-components-summary.txt
: > "$report"; : > "$identity"; : > "$permissions"; : > "$components"; : > "$components_summary"

manifest_dump="$(mktemp)"
trap 'rm -f "$manifest_dump"' EXIT

for apk in "$@"; do
  [ -f "$apk" ] || { echo "skipping missing $apk" >&2; continue; }
  name="$(basename "$apk")"
  size_bytes="$(stat -c%s "$apk" 2>/dev/null || stat -f%z "$apk")"
  sha="$(sha256sum "$apk" | cut -d' ' -f1)"

  badging="$("$aapt2" dump badging "$apk" 2>&1 || true)"
  "$aapt2" dump xmltree --file AndroidManifest.xml "$apk" > "$manifest_dump" 2>&1 || true
  signature="$("$apksigner" verify --verbose --print-certs "$apk" 2>&1)"
  signature_exit=$?

  identity_lines="$(printf '%s\n' "$badging" | grep -E "^(package:|minSdkVersion|sdkVersion|targetSdkVersion|application-label:)" || true)"
  permission_lines="$(printf '%s\n' "$badging" | grep -E "^uses-(permission|implied-permission):" || true)"
  component_lines="$(python3 "$manifest_summary" "$manifest_dump" 2>/dev/null || true)"
  if [ -z "$component_lines" ]; then
    component_lines="$(grep -E '^ *E: |^ *A: android:(name|exported|permission|foregroundServiceType)' "$manifest_dump" | head -n 200 || true)"
  fi
  # Keep every verification/certificate line across build-tools output variants. In
  # particular, record DOES NOT VERIFY and the command exit code for unsigned releases;
  # absence of a signature must never look like an empty successful result.
  signature_lines="$(printf '%s\n' "$signature" | grep -Ei "(DOES NOT VERIFY|Verifies|Verified using|Number of signers|Signer|certificate|WARNING|ERROR)" || true)"

  {
    echo "===================================================================="
    echo "APK            : $name"
    echo "built path     : $apk"
    echo "size           : $size_bytes bytes"
    echo "sha256         : $sha"
    echo "--- aapt2 dump badging (identity, sdk levels) ---"
    printf '%s\n' "$identity_lines"
    echo "--- permissions declared in the built APK ---"
    printf '%s\n' "$permission_lines"
    echo "--- merged manifest: components and their exposure ---"
    printf '%s\n' "$component_lines"
    echo "--- apksigner verify (exit $signature_exit) ---"
    printf '%s\n' "$signature"
    echo "--- merged manifest (full dump) ---"
    head -c 20000 "$manifest_dump"
    echo ""
  } >> "$report"

  {
    echo "$name  ($size_bytes bytes)"
    printf '%s\n' "$identity_lines" | sed 's/^/  /'
    echo "  sha256 (APK): $sha"
    echo "  apksigner verify exit: $signature_exit"
    printf '%s\n' "$signature_lines" | sed 's/^/  /'
    echo ""
  } >> "$identity"

  {
    echo "$name"
    printf '%s\n' "$permission_lines" | sed 's/^/  /'
    echo ""
  } >> "$permissions"

  {
    echo "$name"
    printf '%s\n' "$component_lines" | sed 's/^/  /'
    echo ""
  } >> "$components"

  # The annotation version keeps what a reviewer must check - the app's own components,
  # anything exported, anything permission-protected - and drops library meta-data and
  # non-exported plumbing, so both APKs fit in the 4 KB annotation budget.
  {
    echo "$name"
    printf '%s\n' "$component_lines" | awk '
      # permissions and <queries> have their own annotation; meta-data and properties are
      # library plumbing and stay in dist/BUILD-REPORT.txt.
      /^  (meta-data|property|uses-permission|uses-sdk|queries|package)/ { next }
      /^  (activity|activity-alias|service|receiver|provider)/ {
        if ($0 ~ /com\.liveaireply\.app/ || $0 ~ /exported=true/ || $0 ~ /permission=/) print "  " $0
        next
      }
      /^  manifest/ { next }
      { print "  " $0 }
    '
    echo ""
  } >> "$components_summary"
done

cat "$report"

# One annotation per topic: the check-run API truncates each annotation message at
# 4096 characters, so the escaped text is capped just below that.
publish() {
  title="$1"; file="$2"
  [ -s "$file" ] || return 0
  message="$(tr -d '\r' < "$file" | sed 's/%/%25/g' | sed -z 's/\n/%0A/g' | head -c 4000)"
  printf '::notice title=%s::%s\n' "$title" "$message"
}
publish "APK identity, signing certificate and SHA-256" "$identity"
publish "APK permissions (built artifact)" "$permissions"

# One annotation per APK for the complete component list: a single 4 KB message cannot hold
# both variants, and filtering out non-exported library plumbing would make the report
# incomplete. The source file remains in the artifact even if GitHub truncates an annotation.
awk -v dir=dist '
  /^[^ ].*\.apk$/ { if (out) close(out); out = dir "/annotation-components-" ++n ".txt" }
  out { print >> out }
' "$components"
for f in dist/annotation-components-*.txt; do
  [ -s "$f" ] || continue
  publish "APK components: $(head -n1 "$f")" "$f"
done

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo '### APK inspection report'
    echo ''
    echo '```text'
    head -c 60000 "$report"
    echo '```'
  } >> "$GITHUB_STEP_SUMMARY"
fi
