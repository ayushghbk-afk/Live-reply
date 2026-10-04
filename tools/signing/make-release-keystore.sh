#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Creates a release signing key for Live AI Reply and writes keystore.properties.
#
#   tools/signing/make-release-keystore.sh [keystore-path] [alias]
#
# Defaults: signing/live-reply-release.keystore and alias "livereply".
#
# The password is read from $LIVEREPLY_KEYSTORE_PASSWORD when set (useful for
# automation), otherwise you are prompted twice, without echo.
#
# Nothing is committed: the keystore and keystore.properties are gitignored.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

KEYSTORE="${1:-signing/live-reply-release.keystore}"
ALIAS="${2:-livereply}"
DNAME="${LIVEREPLY_KEY_DNAME:-CN=Live AI Reply Release, OU=Android, O=Live AI Reply, C=IN}"

if [ -e "$KEYSTORE" ]; then
  echo "Refusing to overwrite the existing keystore: $KEYSTORE" >&2
  echo "Move it away first if you really want a new key - the old one can never be" >&2
  echo "recovered and installations signed with it can never be updated in place." >&2
  exit 1
fi

if [ -n "${LIVEREPLY_KEYSTORE_PASSWORD:-}" ]; then
  PASSWORD="$LIVEREPLY_KEYSTORE_PASSWORD"
else
  read -r -s -p "Keystore password: " PASSWORD; echo
  read -r -s -p "Repeat password:  " CONFIRM; echo
  [ "$PASSWORD" = "$CONFIRM" ] || { echo "Passwords do not match." >&2; exit 1; }
fi
[ ${#PASSWORD} -ge 12 ] || { echo "Use a password of at least 12 characters." >&2; exit 1; }

mkdir -p "$(dirname "$KEYSTORE")"
keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "$DNAME"

printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
  "$KEYSTORE" "$PASSWORD" "$ALIAS" "$PASSWORD" > keystore.properties
chmod 600 keystore.properties "$KEYSTORE"

echo
echo "Wrote $KEYSTORE (alias: $ALIAS) and keystore.properties."
echo "Both are gitignored - keep a backup somewhere safe; if you lose the key you can"
echo "never update an installed build in place again."
echo
echo "Certificate fingerprint:"
keytool -list -v -keystore "$KEYSTORE" -storepass "$PASSWORD" -alias "$ALIAS" \
  | grep -E "SHA256:|Owner:|Valid from" || true
echo
echo "For GitHub Actions, set these four repository secrets:"
echo "  ANDROID_KEYSTORE_BASE64     ->  base64 -w0 $KEYSTORE"
echo "  ANDROID_KEYSTORE_PASSWORD   ->  the password you just typed"
echo "  ANDROID_KEY_ALIAS           ->  $ALIAS"
echo "  ANDROID_KEY_PASSWORD        ->  the same password"
