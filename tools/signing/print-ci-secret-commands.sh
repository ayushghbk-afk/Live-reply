#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Prints the exact `gh secret set` commands that let GitHub Actions sign the
# release APK with the key in this checkout.
#
#   tools/signing/print-ci-secret-commands.sh
#
# The output contains *commands*, not secret values: the password is read from
# keystore.properties by the shell when you run them, so it never appears in
# this terminal, in the chat, or in your shell history.
#
# Run the commands yourself, or pipe the output straight into a shell:
#
#   tools/signing/print-ci-secret-commands.sh | bash
#
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [ ! -f keystore.properties ]; then
  echo "keystore.properties not found in $ROOT." >&2
  echo "Create a key first: tools/signing/make-release-keystore.sh" >&2
  exit 1
fi

STORE_FILE="$(sed -n 's/^storeFile=//p' keystore.properties | head -n1)"
ALIAS="$(sed -n 's/^keyAlias=//p' keystore.properties | head -n1)"
[ -n "$STORE_FILE" ] && [ -n "$ALIAS" ] || { echo "keystore.properties is incomplete." >&2; exit 1; }
[ -f "$STORE_FILE" ] || { echo "Keystore file '$STORE_FILE' does not exist." >&2; exit 1; }

cat <<EOF
# Sets the four repository secrets used by .github/workflows/*.yml.
# The values are read from keystore.properties / $STORE_FILE and are never echoed.
base64 -w0 "$STORE_FILE" | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD --body "\$(sed -n 's/^storePassword=//p' keystore.properties | head -n1)"
gh secret set ANDROID_KEY_ALIAS --body "$ALIAS"
gh secret set ANDROID_KEY_PASSWORD --body "\$(sed -n 's/^keyPassword=//p' keystore.properties | head -n1)"
EOF
