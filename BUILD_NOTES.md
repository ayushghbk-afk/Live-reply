# Build notes — what was actually executed, and what was not

This file records the build environment honestly. It exists because "the build should work"
is not evidence; every claim below was produced by a command whose output is quoted.

---

## 1. Provenance of the APK that Play Protect blocked

| Question | Answer |
|---|---|
| Which file? | `LiveReply-1.0.0-debug.apk`, 55,703,461 bytes |
| Produced by this repository? | **Yes.** Name and size match the `Collect the APKs` step of `.github/workflows/release-apk.yml` (`app-debug.apk` → `LiveReply-1.0.0-debug.apk`). |
| Which run? | GitHub Actions run [`37205108074`](https://github.com/ayushghbk-afk/Live-reply/actions/runs/37205108074) — workflow **Release APK**, event `push`, tag `v1.0.0`, commit `7514cd6b1e82cc45c950a9c0024a3b6666714be`, conclusion `success`. |
| Where from? | GitHub Release [`v1.0.0`](https://github.com/ayushghbk-afk/Live-reply/releases/tag/v1.0.0), asset `LiveReply-1.0.0-debug.apk` (uploaded 2026-10-04T13:23:15Z) together with `LiveReply-1.0.0-release-unsigned.apk` (44,751,730 bytes) and `SHA256SUMS.txt`. |
| Was it a debug build? | **Yes.** It is the `debug` build type: `android:debuggable="true"`, `isMinifyEnabled = false`, and signed with a debug keystore that the workflow *generated inside that run* (`keytool -genkeypair … -dname "CN=Android Debug,O=Android,C=US"`), because the `ANDROID_DEBUG_KEYSTORE_BASE64` secret is not configured. |
| Was it installable as a normal release? | No. The published "release" asset in that release is **unsigned** (`app-release-unsigned.apk`) because the Gradle release build type had no `signingConfig` and no release key was configured. |

Two consequences that the user hit:

1. **"App not installed" (screenshot 1).** A debug APK signed by a key generated per CI run
   cannot replace an APK signed by a different key; Android reports
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and the on-device installer shows only "App not
   installed". Installing a *second* CI debug build on top of the first therefore always
   fails.
2. **"App blocked to protect your device" (screenshot 2).** This one is *not* about debug vs
   release. It is Google's automatic block for apps installed from internet-sideloading
   sources that declare accessibility (and/or SMS / notification-listener) access — quoted
   from [Google's developer guidance](https://developers.google.com/android/play-protect/warning-dev-guidance)
   in README section 5.1. No permission change removes it, because accessibility *is* the
   product.

---

## 2. Attempted in this environment, with the real output

### `./gradlew clean`, `./gradlew test`, `./gradlew assembleDebug`

All three were run (JDK 17 supplied, see below). All three fail before Gradle starts, in the
wrapper itself:

```text
Exception in thread "main" javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
    at java.base/sun.net.www.protocol.https.HttpsClient.afterConnect(Unknown Source)
    at org.gradle.wrapper.Install.forceFetch(SourceFile:2)
    at org.gradle.wrapper.Install$1.call(SourceFile:8)
    at org.gradle.wrapper.GradleWrapperMain.main(SourceFile:67)
Caused by: java.io.EOFException: SSL peer shut down incorrectly
```

The wrapper cannot download `gradle-8.9-bin.zip` from `services.gradle.org`. This is a
**network-allowlist restriction of the development sandbox**, not a defect in the project.
Hosts reachable from here: `pypi.org`, `files.pythonhosted.org`, `registry.npmjs.org`,
`api.github.com`, `codeload.github.com`, `github.com`. Everything else the Android build needs
is refused at TLS level (HTTP 000 / `SSL_ERROR_SYSCALL`):

| Host | Needed for |
|---|---|
| `services.gradle.org` | the Gradle distribution itself |
| `maven.google.com` | AGP, AndroidX, Compose, ML Kit |
| `repo1.maven.org` / `repo.maven.apache.org` | OkHttp, coroutines, JUnit |
| `dl.google.com` | Android SDK, platform 35, build-tools |
| `api.adoptium.net`, `cdn.azul.com` | a full JDK with `javac` |

So **no Android build can run here**, with or without a JDK. The project therefore builds on
GitHub Actions (section 4), which is also where the APK comes from.

### What *was* executed here

| Check | Command | Result |
|---|---|---|
| Compile the platform-independent core + run the real unit tests | `tools/jvm-verify/run-tests.sh` (JDK 17.0.9 + Kotlin 2.0.21 compiler) | **191 run, 191 passed, 0 failed** |
| XML well-formedness (all `res/**/*.xml` + the manifest) | `xml.etree` parse over 17 files | 17 parsed, 0 malformed |
| Resource references (`@string/…`, `R.string.…`) | script over `res/` + `MainActivity.kt` | 0 missing |
| Kotlin syntax of the Android-only files edited (`MainActivity.kt`, `AppDataStore.kt`) | `kotlinc` parse (no `android.jar` available, so unresolved references are expected) | 0 parse errors |
| Workflow validity | `yaml.safe_load` + `bash -n` on every `run:` block of both workflows | YAML OK; all run blocks parse |
| Release keystore | `keytool -genkeypair` (RSA 4096, PKCS12, valid to 2056) | created, gitignored, never committed |

Toolchain used for the above (both reachable from this sandbox):

```bash
python3 -m venv /tmp/venv && /tmp/venv/bin/pip install jdk4py==17.0.9.2   # JDK 17 + keytool
npm install --prefix /tmp/kt kotlin-compiler@2.0.21                        # kotlinc 2.0.21
export JAVA_HOME=/tmp/venv/lib/python3.11/site-packages/jdk4py/java-runtime
export KOTLIN_COMPILER_JAR=/tmp/kt/node_modules/kotlin-compiler/lib/kotlin-compiler.jar
./tools/jvm-verify/run-tests.sh
```

`jdk4py` ships a **JRE** (no `javac`), which is enough for `kotlinc` and `keytool` but not for
`apksigner`/Gradle.

---

## 3. What has not been verified, and why

* **No APK was built locally.** No Android SDK, no Maven access, no Gradle distribution.
* **No APK was installed on a device or emulator.** This sandbox has no device, no emulator
  and no Android SDK, so `adb install` and app launch could not be exercised. Nothing in this
  repository claims otherwise.
* **The APK binaries could not be downloaded into this sandbox** either: release assets and
  Actions artifacts are served from `release-assets.githubusercontent.com` /
  `pipelines.actions.githubusercontent.com`, which are blocked here (only the GitHub JSON API
  and git protocol work). The APK is therefore inspected by CI, on the runner, where the
  artifact exists — see section 4 — and the user downloads it from the run page or the
  release.

---

## 4. Where the APK facts come from now

Both workflows contain a `Verify the APKs and publish the inspection report` step. For every
APK it writes `dist/BUILD-REPORT.txt` containing:

* the exact path, byte size and **SHA-256**;
* `aapt2 dump badging`: package name, `versionCode`, `versionName`, `minSdkVersion`,
  `targetSdkVersion`, application label, launchable activity, **every requested
  permission**;
* `apkanalyzer manifest print` (fallback: `aapt2 dump xmltree`): the **merged manifest** —
  every activity/service/receiver/provider, its `android:exported`, service permissions and
  `foregroundServiceType`;
* `apksigner verify --verbose --print-certs`: which signature schemes verify (v1/v2/v3) and
  the signing certificate (subject, issuer, SHA-256).

The same text is published as a check-run annotation (so it is readable from the API without
downloading anything) and in the run's step summary, and the file is uploaded with the
artifacts and attached to GitHub Releases.

Practical result: the release workflow **fails** if a signing key is configured but Gradle
produced `app-release-unsigned.apk`, so an unsigned release can no longer be published by
accident, and the debug APK now reports whether it carries the release certificate or a
throwaway CI debug certificate.

---

## 5. Build history

| Run | Conclusion | Notes |
|---|---|---|
| `37203761613` | failure | first real compile: 10 Kotlin errors in the Android-only layers |
| `37204047302`, `37204240461` | failure | workflow learned to republish Gradle errors as annotations |
| `37204716488` | **success** | compile clean, 191/191 unit tests, debug 55.7 MB + unsigned release 44.8 MB |
| `37205108074` | **success** | tag `v1.0.0` → Release with `LiveReply-1.0.0-debug.apk`, `-release-unsigned.apk`, `SHA256SUMS.txt` |
| this branch | see the run page | permission audit (`QUERY_ALL_PACKAGES` removed), first-run disclosure, release signing, CI APK inspection |

The APK has never been *run*: no device or emulator has been involved in any run, so runtime
behaviour of the accessibility, overlay and capture layers remains unproven on hardware. The
logic they call is the tested core (191 tests).
