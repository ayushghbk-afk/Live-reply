# Build and verification notes

**Audit date:** 4 October 2026
**Branch:** `arena/01a107c2-live-reply`

This file records what was actually executed for the current hardening change. A source check,
an Android Gradle build, APK signature verification, and physical-device testing are different
levels of evidence and are not presented as interchangeable.

## Local sandbox

A temporary JDK 17.0.9 runtime was provisioned from `jdk4py`, and Kotlin compiler 2.0.21 was
provisioned under `/tmp`. The sandbox has no Android SDK, `aapt2`, `apkanalyzer`, or `apksigner`.

The four requested Gradle commands were each executed literally:

```text
./gradlew clean           -> exit 1
./gradlew test            -> exit 1
./gradlew assembleDebug   -> exit 1
./gradlew assembleRelease -> exit 1
```

Each failed in the Gradle wrapper before project configuration because the sandbox could not
download Gradle 8.9 from `services.gradle.org`:

```text
javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
Caused by: java.io.EOFException: SSL peer shut down incorrectly
```

This is a local environment/network limitation, not a successful or failed Android compile.
The workflow therefore runs the same four commands on a provisioned GitHub Actions Android
runner; its result is recorded below after completion.

## Source/JVM checks executed locally

| Check | Result |
|---|---|
| Platform-neutral production/test compilation and suite (`tools/jvm-verify/run-tests.sh`) | **202 run, 202 passed, 0 failed** |
| XML parsing (`AndroidManifest.xml` and resources) | **17 parsed, 0 malformed** |
| Patch whitespace (`git diff --check`) | **passed** |
| Shell syntax (`tools/ci/inspect-apks.sh`, `tools/jvm-verify/run-tests.sh`) | **passed** |
| Standalone Kotlin syntax scan across all source files | **no parser/token errors**; unresolved Android/Compose symbols were expected without SDK dependencies |

The offline harness excludes 11 Android/platform-integrated sources and does not replace an
Android Gradle build. Its purpose is to execute all platform-neutral logic and tests when the
SDK repositories are unreachable.

## Android CI build

GitHub Actions run [`37224652485`](https://github.com/ayushghbk-afk/Live-reply/actions/runs/37224652485)
on application-source commit `8e16da4` passed. `.github/workflows/build-apk.yml` ran these as
four independent commands, and every command succeeded:

```text
./gradlew clean           -> success
./gradlew test            -> success
./gradlew assembleDebug   -> success
./gradlew assembleRelease -> success
```

It then ran `tools/ci/inspect-apks.sh` on every generated APK. The inspection records:

- package, version code/name, minimum SDK, target SDK, and launchable activity;
- every merged requested permission;
- complete merged manifest and activities/services/receivers/providers with exported state,
  binding permission, and foreground-service type;
- exact APK byte size and SHA-256;
- `apksigner verify --verbose --print-certs`, signature-scheme results, and signing certificate
  subject/issuer/SHA-256.

## Signing expectations

No private key is committed. If release-signing secrets are absent, the expected outputs are:

- a debug APK signed with the CI debug key; and
- an **unsigned** release APK, which is build output but is not installable/distributable until
  signed with a stable protected production/upload key.

If signing secrets are configured, both variants use that configured key and CI checks that the
release output is not named unsigned. A valid signature does not guarantee Play Protect or
Google Play approval.

In run `37224652485`, no release key was configured:

- debug: 54,593,693 bytes; APK SHA-256
  `645497000542632695deb534728bd0f0bdd22cddb9899cf4027521b2a7f0b92b`; v2 signature verified;
  ephemeral debug-certificate SHA-256
  `e4b71bfd292bf263cf39211d89d8ccef906880e1982ff00ee76ba1fa4f4e7740`;
- release output: 44,792,881 bytes; APK SHA-256
  `0b9b15120f76ac572e875404d828d3539c56ad2a760b89de1bf62b1a5335faf8`; unsigned;
  `apksigner` exit 1 (`DOES NOT VERIFY`, missing `META-INF/MANIFEST.MF`).

See [`AUDIT_REPORT.md`](AUDIT_REPORT.md) for the complete permissions and component inventory.

## Device testing

No APK from this change has been installed on a physical Android device in this audit. No
emulator runtime test has been performed either. Accessibility event delivery, third-party chat
UI matching, Android's MediaProjection confirmation/`FLAG_SECURE` behavior, overlay behavior,
foreground-service lifecycle, and STOP teardown therefore still require instrumented/manual
validation on supported Android versions and representative chat apps.
