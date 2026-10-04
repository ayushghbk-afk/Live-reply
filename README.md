# Live AI Reply

A real Android app that acts as a live AI assistant for conversations in **other** chat
apps (WhatsApp, Telegram, Instagram, Discord, browser chats, Character.AI, …).

It watches the chat you have open, detects a new incoming message, sends the recent
context to an OpenAI-compatible model you configure, and either **suggests** the reply in
a floating bubble or — if you explicitly allow it — types and sends it for you.

There is a large, always-visible **STOP AI** control in the app, on the overlay and in
the persistent notification.

---

## 1. What it does

| Mode | Behaviour |
|---|---|
| **Suggest** | Detect → generate → show in overlay. Never types or sends. You can Send / Edit / Regenerate / Copy / Reject. |
| **Approve** | Detect → generate → **stage the reply in the chat box** for review. Nothing is sent until you tap **Send**. |
| **Auto** | Detect → generate → type → send automatically, after your configured delay. Requires a separate "Auto reply" switch **and** a risk acknowledgement. |

Detection is accessibility-first. Screenshots/OCR are a **fallback only**, run on-device,
and the bitmap is discarded after recognition.

## 2. Features

- Accessibility-based detection with debounce, duplicate suppression and scroll immunity
- Incoming vs. outgoing classification (view ids → content descriptions → bubble geometry)
- **Loop prevention**: the assistant's own reply is hashed and can never trigger a new reply
- Configurable context window (5 / 10 / 20 / 30 / 50 messages)
- OpenAI-compatible providers: **OpenRouter** (default base URL `https://openrouter.ai/api/v1`), OpenAI, or any custom endpoint
- Primary + ordered **fallback models**; bounded retries with backoff; no retry on a bad API key; immediate stop when offline
- Personas, including full **roleplay** characters (name, personality, background, relationship, style, rules); multiple saved personas
- Reply length presets + custom max characters, emoji cap, naturalness rules
- Language policy (Auto / English / Hindi / Hinglish / Spanish / French / German / custom) and a translation mode
- Reply delay (Instant…10 s / custom) and optional human-like typing
- Draggable floating overlay with size/opacity controls and live status (`● Monitoring`, `⏳ Thinking…`, `✓ Reply ready`, `⚠ Error`, `⏸ Paused`)
- Per-chat pause, global pause, emergency stop
- Sensitive-screen protection (password/PIN/OTP/card fields, banking & authenticator packages, user exclusions)
- API key stored encrypted with an **Android Keystore** AES/GCM key; never in code, resources or logs
- Local diagnostics log with redaction and an opt-in debug mode
- Built-in **Test mode** that runs the full pipeline without touching another app
- Material 3 UI, light/dark/system theme, first-run setup wizard

## 3. Requirements

- Android **7.0 (API 24)** or newer; built against SDK 35
- An API key for OpenRouter, OpenAI or any OpenAI-compatible endpoint
- Permissions granted by you: Accessibility service, "Display over other apps", Notifications
  (Android 13+), optionally Screen capture for the OCR fallback

## 4. Build

```bash
./gradlew assembleDebug          # debug APK
./gradlew test                   # unit tests (191 tests, no emulator needed)
./gradlew assembleRelease        # unsigned release APK/AAB (sign it yourself)
```

Needs JDK 17+ and an Android SDK. First run downloads Gradle 8.9 and the dependencies
(AGP 8.7.3, Compose BOM 2024.10.01, OkHttp 4.12, ML Kit text recognition 16.0.1) from
`google()` and `mavenCentral()`, so the build machine needs internet access.

Set the SDK location either in `local.properties` (`sdk.dir=/path/to/Android/sdk`) or via
`ANDROID_HOME`.

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Build the APK on GitHub (nothing to install locally)

Two GitHub Actions workflows do the whole build on a GitHub runner (JDK 17 + Android
SDK 35 come preinstalled on `ubuntu-latest`):

| Workflow | File | Runs on | Produces |
|---|---|---|---|
| **Build APK** | `.github/workflows/build-apk.yml` | every push, every pull request, manual *Run workflow* | `dist/LiveReply-<version>-debug.apk` + `-release-unsigned.apk` as the **live-ai-reply-apk-…** artifact on the run page |
| **Release APK** | `.github/workflows/release-apk.yml` | pushing a `v*` tag, or manual *Run workflow* with a tag | a GitHub **Release** with the APKs + `SHA256SUMS.txt` attached |

A build of `main` is already published — grab it from the
[Releases page](https://github.com/ayushghbk-afk/Live-reply/releases/latest)
(`LiveReply-1.0.0-debug.apk`, signed and installable) and skip the build entirely.

Getting a runnable APK without any local Android SDK:

1. Push your branch, or open **Actions → Build APK → Run workflow**.
2. Open the finished run and download the **live-ai-reply-apk-\<sha\>** artifact (a zip).
3. Unzip it and copy `LiveReply-<version>-debug.apk` to the phone, then tap it to install.
   The debug APK is signed, so it installs directly. `adb install -r` works too.

The job runs `./gradlew testDebugUnitTest assembleDebug assembleRelease --continue`, so the
APKs are uploaded even when a unit test fails — but the job is still marked red in that
case. The run page summary lists each APK with its size and SHA-256.

Publishing a versioned release:

```bash
git tag v1.0.0
git push origin v1.0.0     # -> Release APK workflow -> GitHub Release with the APKs
```

#### Optional repository secrets

Set these under **Settings → Secrets and variables → Actions** (none are required to get
a debug APK):

| Secret | Effect |
|---|---|
| `ANDROID_DEBUG_KEYSTORE_BASE64` | Reuses one debug signing key across runs, so a new debug APK updates the installed app in place instead of failing with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Generate with `base64 -w0 ~/.android/debug.keystore`. |
| `ANDROID_KEYSTORE_BASE64` | Your own release keystore (`base64 -w0 my-release.keystore`). When present, the release APK is `zipalign`ed and signed with `apksigner` and published as `LiveReply-<version>-release.apk`. |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password. |
| `ANDROID_KEY_ALIAS` | Key alias inside the keystore. |
| `ANDROID_KEY_PASSWORD` | Key password (defaults to the keystore password). |

Without `ANDROID_KEYSTORE_BASE64` the release APK is attached **unsigned** (the Gradle
release build type has no `signingConfig`, by design) and the debug APK is the
installable one. No API key or model id is ever baked into a CI build.

### Offline verification (no Android SDK required)

The platform-independent core (detection, adapters, prompt building, validation,
pipeline, automation guard, engine) has **no Android imports**, so it can be compiled and
tested with only a JDK and a Kotlin compiler:

```bash
export KOTLIN_HOME=/path/to/kotlinc        # or KOTLIN_COMPILER_JAR=.../kotlin-compiler.jar
./tools/jvm-verify/run-tests.sh
```

This compiles `app/src/main/java` (core packages) together with the **real** JUnit test
sources in `app/src/test` and runs them reflectively. It is the same production code and
the same tests Gradle runs - only the JUnit API is stubbed (`tools/jvm-verify/junit-stub`,
outside every Gradle source set).

## 5. Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. First run

1. Open **Live AI Reply** → the setup wizard starts.
2. **Accessibility**: tap *Open Accessibility settings* → *Downloaded services* →
   *Live AI Reply* → enable it. Read the description; it states exactly what is read.
3. **Overlay**: grant *Display over other apps* when prompted.
4. **Notifications** (Android 13+): allow them so the monitoring notification appears.
5. **AI provider**: keep `https://openrouter.ai/api/v1` (or set your own base URL),
   paste your API key, tap **Save key**. The key is encrypted; it is never displayed again.
6. **Model**: type any model id (e.g. `openai/gpt-4o-mini`) or tap **Fetch models**.
   Add fallback models comma-separated.
7. **Persona**: pick one, or create your own later in *Manage personas*.
8. **Mode**: choose Suggest / Approve / Auto.
9. **Test**: tap **Test AI**, or use **Test mode** to generate a reply for a typed message.
10. Turn **Monitoring** on. The floating `●` appears and the notification shows the mode.

## 7. Permissions

| Permission | Why |
|---|---|
| Accessibility service | Read the visible chat, find the composer, type and tap Send |
| `SYSTEM_ALERT_WINDOW` | The floating control and suggestion card |
| `POST_NOTIFICATIONS` | The persistent notification with Pause/Stop |
| `FOREGROUND_SERVICE` + `specialUse` | Keeps the assistant alive while monitoring |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | On-device OCR fallback, only while armed |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Your AI endpoint only; detect offline |

The API key is encrypted with a non-exportable Android Keystore key. Nothing is backed up
(`data_extraction_rules.xml` excludes everything).

## 8. Configuring OpenRouter

1. AI settings → **Base URL** = `https://openrouter.ai/api/v1`
2. Paste `OPENROUTER_API_KEY` → **Save key**
3. **Primary model** = any OpenRouter id, e.g. `anthropic/claude-3.5-sonnet`
4. **Fallback models** = `openai/gpt-4o-mini, google/gemini-flash-1.5`
5. **Test connection** then **Fetch models**

OpenRouter attribution headers (`HTTP-Referer`, `X-Title`) are sent automatically.
No model id and no key is hard-coded anywhere in the app.

## 9. Personas

*Manage personas* → edit any field → **Save persona**.

| Field | Example |
|---|---|
| Character name | Kaelen |
| Personality | quiet, mysterious, emotionally guarded, poetic |
| Background | A night-cartographer who maps places that only exist after dark |
| Relationship | slow-burn romance |
| Speaking style | short poetic dialogue |
| Rules | Never break character. / Do not mention AI. / Do not explain the roleplay. / Respond naturally. |

Tick **Roleplay persona** for character play. Custom system prompts (AI settings) replace
the built-in prompt entirely; persona, length and language rules are still applied.

## 10. Switching modes

Home screen → **Mode** → Suggest / Approve / Auto.

Auto mode needs three things, all explicit: mode = Auto, the **Auto reply** switch on
(which records your acknowledgement), and monitoring running. If any is missing, the app
falls back to showing a suggestion.

## 11. Safety rules that are always enforced

Automatic sending is **refused** (and a suggestion is shown instead) when: the recipient
is unclear; the composer cannot be identified with confidence; no reliable Send control
was found; the reply is empty or failed validation; you are typing; the active app
changed; the screen is sensitive or excluded; detection confidence is low; OCR confidence
is low; the chat is paused; or the assistant is stopped. A coordinate tap is only ever
used if you configured that exact point for that app.

## 12. Supported apps & limitations

- **Generic adapter** works with any chat app that exposes its bubbles and composer
  through accessibility.
- Specialised hints ship for **WhatsApp, Telegram, Instagram, Discord, browsers**.
- Chat apps rename internal view ids between releases. When one changes, open
  *Supported apps* and set the composer/send view id yourself; the generic heuristics
  take over otherwise.
- **Browser chats** (web WhatsApp, Character.AI) expose unstable trees; the app
  deliberately lowers its confidence there and will suggest rather than auto-send.
- "Typing simulation" is progressive `ACTION_SET_TEXT`. Android offers no public API to
  inject key events into another app, so true keystroke-level typing is not possible
  without an IME.
- On some devices the accessibility service is killed under aggressive battery
  management. Use *Ignore battery optimisation* from the Permissions screen.
- The app cannot read a chat that does not render text into the accessibility tree; that
  is when the OCR fallback (opt-in) is used.
- MediaProjection cannot capture DRM-protected surfaces.

## 13. Privacy

**Sent to your AI provider:** the system prompt you configured, plus the last N chat
lines (N = your context setting, each clipped to 400 characters), labelled `Them:` / `Me:`.

**Never sent anywhere:** screenshots, your API key, passwords/PINs/OTPs/card numbers,
anything on a screen the policy marks sensitive, anything from an excluded package.

Screenshots are decoded and recognised on-device and then recycled. The log redacts
tokens, keys and card numbers in all modes, and only contains message text when you turn
on debug mode.

## 14. Troubleshooting

| Symptom | Fix |
|---|---|
| Nothing is detected | Accessibility service enabled? Monitoring on? The app is in *Supported apps*? |
| "Could not find the chat input field" | Set the composer view id in *Supported apps*, or use Suggest mode / enable OCR |
| Reply generated but not sent | Read the reason under the reply - the guard explains every refusal |
| `Rate limit reached` | Add fallback models, or lower the reply delay / context size |
| `No internet connection` | Monitoring stays on; nothing is retried until you are back online |
| Assistant replies to itself | Should not happen - self-replies are hashed and ignored. Report it with a log export |
| Overlay missing | Grant "Display over other apps", and check the overlay toggle in Privacy |

## 15. Project layout

```
app/src/main/java/com/liveaireply/app/
├── accessibility/   LiveReplyAccessibilityService, NodeMapper, AutomationController
├── adapters/        ChatAdapter + WhatsApp/Telegram/Instagram/Discord/Browser/Generic
├── ai/              AiProvider, OpenAI-compatible + OpenRouter clients, PromptBuilder,
│                    ReplyValidator, ReplySanitizer, ReplyPipeline (retry + fallback)
├── automation/      AutomationGuard (every auto-send rule), AutomationController
├── conversation/    NodeView, ChatTurn, BubbleExtractor, DirectionClassifier,
│                    DuplicateGuard, ConversationDetector, LanguageDetector, NoiseFilter
├── di/              AppContainer (hand written DI)
├── engine/          ReplyEngine, AssistantService, AssistantRuntime, EngineHost
├── notifications/   MonitoringNotifier, AssistantActionReceiver
├── ocr/             ScreenCaptureService, ScreenCaptureController, OcrTextExtractor
├── overlay/         OverlayController, OverlayContent (Compose)
├── personas/        Persona, presets, repositories
├── security/        Keystore credential store, SensitiveScreenPolicy, LogRedactor
├── settings/        AppSettings + DataStore repository
├── storage/         MiniJson (dependency free codec), AppDataStore
├── ui/              Compose screens, theme, AppViewModel
└── util/            Debouncer, EventLog, MillisClock
```

## 16. Tests

`app/src/test` covers: JSON codec, hashing, duplicate/TTL guard, noise filtering,
language detection, direction classification, bubble extraction, the conversation
detector (new / repeated / own / scrolling / self-echo / chat switch / policy gates),
adapters (composer + send detection, no invented coordinates, overrides), prompt
building, sanitising, validation, the pipeline (success, timeout, rate limit → fallback,
auth → no fallback, offline → no retry, malformed), the automation guard, the sensitive
screen policy, the log redactor, the debouncer, personas, and the engine end-to-end
(suggest / approve / auto, loop prevention, emergency stop, error paths, overlay actions).

```
Tests run: 191, passed: 191
```

## 17. Known limitations

- The Gradle build cannot run in the development sandbox (no Android SDK), so it is
  executed on GitHub Actions instead — see `BUILD_NOTES.md` and section 4. The CI build
  compiles clean and passes all 191 unit tests; the APK has not been run on a device.
- No conversation database: history is opt-in and kept in memory by default.
- OCR bubble grouping is heuristic; low-confidence scans never trigger Auto mode.
- Multi-window / split-screen layouts are not specifically handled.
- The assistant reads only what is visible on screen.
