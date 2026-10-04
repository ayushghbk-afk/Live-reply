# Signing material — do not commit anything in this folder

This directory holds the **release signing key** for Live AI Reply.

```
signing/
├── README.md                      <- this file (committed)
└── live-reply-release.keystore    <- the private key (NEVER committed, gitignored)
```

`.gitignore` excludes `signing/*.keystore`, `signing/*.jks`, `signing/*.p12`,
`signing/*.properties` and the root `keystore.properties`. If `git status` ever shows a
keystore as untracked-but-visible, **do not** `git add` it.

`app/build.gradle.kts` reads the key from, in order:

1. `keystore.properties` in the repository root (local builds; gitignored), or
2. the environment variables below (CI):

| Variable | Meaning |
|---|---|
| `LIVEREPLY_KEYSTORE_FILE` | path to the `.keystore` / `.jks` / `.p12` file |
| `LIVEREPLY_KEYSTORE_PASSWORD` | keystore password |
| `LIVEREPLY_KEY_ALIAS` | key alias (`livereply` for the generated key) |
| `LIVEREPLY_KEY_PASSWORD` | key password (defaults to the keystore password) |

When a key is configured, `assembleRelease` produces a **signed** `app-release.apk` and
`assembleDebug` is signed with the **same** key (so debug and release builds can update
each other in place). With no key configured, `assembleRelease` still succeeds but writes
`app-release-unsigned.apk`, and the build log says so.

## Building locally with this key

```bash
./gradlew clean assembleDebug assembleRelease
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk        <- signed
```

## Letting GitHub Actions sign the release

The workflows sign the release APK when these **repository secrets** are set
(Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 signing/live-reply-release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | the `storePassword` from `keystore.properties` |
| `ANDROID_KEY_ALIAS` | `livereply` |
| `ANDROID_KEY_PASSWORD` | the `keyPassword` from `keystore.properties` |

The secrets are encrypted by GitHub and are not readable by anyone (including the build
logs); the keystore is written to a temporary file on the runner and is deleted with the
runner. Base64 of the keystore is *not* the same as committing the key: only the encrypted
secret store and your own machine hold it.

## If you lose this key

* You cannot publish an update that installs over an existing install (Android rejects a
  different signing certificate), so users have to uninstall first.
* For Google Play, use Play App Signing so Google holds the distribution key — then only
  the upload key must be kept.
* Generate a new key with `tools/signing/make-release-keystore.sh` (or `keytool`
  directly) and keep it somewhere durable (password manager, offline backup).

## Note on the "debug" keystore

Android's debug builds are signed with a throwaway key generated per machine
(`~/.android/debug.keystore`). Two debug APKs built on different machines therefore have
different signatures and cannot replace each other — one of the reasons a debug APK is a
bad distribution artefact. Because a release key is configured here, the debug APK is
signed with the release key instead, which removes that problem.
