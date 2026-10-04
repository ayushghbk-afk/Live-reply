# Build notes - what was and was not executed in this environment

## Executed here

| Check | Command | Result |
|---|---|---|
| Core compile (Kotlin 2.0.21, JVM target 17) | `tools/jvm-verify/run-tests.sh` | 34 core sources + 21 test sources compile clean |
| Unit tests | `tools/jvm-verify/run-tests.sh` | **191 run, 191 passed, 0 failed** |
| Resource XML well-formedness | `xml.etree` over `res/**` + manifest | 17 files, 0 malformed |
| Resource reference resolution (`@string`, `@drawable`, `R.string.*`, …) | script in the session | 19 references, 0 missing |

The tests are the real `app/src/test` sources executed against the real
`app/src/main/java` production classes - no re-implementation, no stubbing of the logic
under test. Only the JUnit 4 API surface is stubbed, in `tools/jvm-verify/junit-stub`,
which is outside every Gradle source set.

Code paths actually executed by that run include `ConversationDetector.process`,
`DuplicateGuard.markSelfReply`, `ReplyEngine.handleSnapshot/handleAction/performSend`,
`AutomationGuard.decide`, `ReplyPipeline.generate`, `OpenAiCompatibleProvider.complete`,
`PromptBuilder.build`, `ReplyValidator.validate`, `ReplySanitizer.sanitize/limitEmojis`,
`BubbleExtractor.extractBubbles`, `GenericChatAdapter.locateComposer/locateSendTarget`,
`SensitiveScreenPolicy.evaluate`, `LogRedactor.redact`, `Debouncer.*`,
`Json.parse/stringify` and `AppSettings`.

Bugs the tests caught and that were fixed:
- `AppSettings.DEFAULT` was declared before `DEFAULT_ENABLED_PACKAGES`, so the companion
  initialiser passed `null` into a non-null parameter (`ExceptionInInitializerError` on
  first use).
- `GenericChatAdapter.supports()` returned `true` unconditionally and was inherited by
  every specialised adapter, so Instagram claimed every package.
- `ConversationDetector` accepted `DetectionPolicy.conversationPaused` but never checked
  it, so a paused chat would still be answered.
- `DuplicateGuard.markSelfReply` stored a content hash while the detector compared
  direction-aware hashes, weakening loop prevention.
- `ReplySanitizer.limitEmojis` walked UTF-16 chars and split surrogate pairs, so emoji
  limiting never worked.

## Build automation added (runs outside this sandbox)

`./gradlew assembleDebug` cannot run in this sandbox (no JDK, no Android SDK, every
Google/Maven host blocked - see below), so the build is delegated to GitHub Actions,
which does have the toolchain:

- `.github/workflows/build-apk.yml` - tests + `assembleDebug` + `assembleRelease` on every
  push and pull request, uploads the APKs as a downloadable artifact.
- `.github/workflows/release-apk.yml` - on a `v*` tag, signs the release APK with the
  keystore from repository secrets (if configured) and attaches everything to a GitHub
  Release.

The YAML was validated locally (`yaml.safe_load`) and every `run:` block passes
`bash -n`. The Gradle build itself is only executed on the runner - see
"Not executed here, and why".

## Not executed here, and why

**`./gradlew assembleDebug` was never run.** This sandbox has no Android toolchain and no
way to obtain one:

- `java`, `javac`, `gradle`, `ANDROID_HOME`: absent.
- Network egress is restricted to `registry.npmjs.org`, `pypi.org`, `api.github.com` and
  `codeload.github.com`. Every host the Android build needs is blocked at the TLS layer
  (verified: connection reset, `curl` exit 35 / HTTP 000):
  - `dl.google.com` (Android SDK, platform, build-tools)
  - `maven.google.com` (AGP, AndroidX, Compose, ML Kit)
  - `repo1.maven.org` (OkHttp, JUnit, coroutines)
  - `services.gradle.org` (the Gradle distribution itself)
  - `api.adoptium.net` (JDKs)
- `apt-get update` fails: `deb.debian.org` is blocked and `/var/lib/apt/lists` is not
  writable without root package access.

Workaround used to get a compiler at all: a JDK-less JRE 17 from the `jdk4py` PyPI wheel
and the Kotlin 2.0.21 compiler from the `kotlin-compiler` npm package. That is enough to
run `kotlinc` and the JVM tests, but it cannot produce an APK - `aapt2`, `d8`, the
platform `android.jar` and every AndroidX/Compose/OkHttp/ML Kit artifact are unreachable.

The real `gradle/wrapper/gradle-wrapper.jar` (43,504 bytes, Gradle v8.9.0) and the
`gradlew`/`gradlew.bat` scripts **were** fetched from the official `gradle/gradle`
repository via the GitHub API and are committed, so `./gradlew` works as soon as the
machine has network access to `services.gradle.org`.

Therefore: **the Android-specific layers are unverified by a compiler.** They are
`accessibility/`, `ocr/`, `overlay/`, `notifications/`, `di/`, `storage/AppDataStore`,
`security/SecureCredentialStore`, `settings/SettingsRepository`, `personas/DataStorePersonaRepository`,
`ai/openai/OkHttpTransport`, `engine/AssistantService`, `ui/` and `MainActivity`. Expect
to fix straightforward signature/import issues on the first Gradle build; the logic they
call is the tested core.
