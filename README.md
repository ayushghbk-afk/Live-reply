# Live AI Reply

A real Android app that acts as a live AI assistant for conversations in **other** chat
apps (WhatsApp, Telegram, Instagram, Discord, browser chats, Character.AI, …).

It watches the chat you have open, detects a new incoming message, sends the recent
context to an OpenAI-compatible model you configure, and either **suggests** the reply in
a floating bubble or — if you explicitly allow it — types and sends it for you.

There is a large, always-visible **STOP AI** control in the app, on the overlay and in
the persistent notification.

> **Before you install:** Google Play Protect **blocks** internet-sideloaded installs of
> any app that declares Android accessibility access (that is a platform policy, not a bug
> in this app — see [Google Play Protect](#51-google-play-protect--why-the-install-is-blocked)).
> Install developer builds with `adb install`, and read that section before you try to
> sideload an APK from a browser or file manager.

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
- A way to install that Play Protect allows: `adb install` (developer/testing), a Google
  Play listing, or an approved Play Protect appeal — see section 5.1

## 4. Build

```bash
./gradlew test                   # unit tests (191 tests, no emulator needed)
./gradlew assembleDebug          # debug APK; signed with your release key when one is configured
./gradlew assembleRelease        # release APK; signed when a key is configured, otherwise unsigned
```

**Signing.** `app/build.gradle.kts` reads a release key from `keystore.properties` in the
repository root (gitignored) or from `LIVEREPLY_KEYSTORE_FILE` /
`LIVEREPLY_KEYSTORE_PASSWORD` / `LIVEREPLY_KEY_ALIAS` / `LIVEREPLY_KEY_PASSWORD` in the
environment. In this workspace a release key has already been generated into
`signing/live-reply-release.keystore` together with a root `keystore.properties` (both
gitignored, so they never reach Git). On a fresh clone, create your own:

```bash
tools/signing/make-release-keystore.sh          # writes signing/…keystore + keystore.properties
./gradlew clean assembleDebug assembleRelease
```

When a key is configured, **both** build types are signed with it (so a debug build can
replace a release build and vice-versa — the "App not installed" signature mismatch
disappears). With no key, `assembleRelease` produces `app-release-unsigned.apk` and says so
in the build log. A private key is never committed: see `signing/README.md`.

Needs JDK 17+ and an Android SDK. First run downloads Gradle 8.9 and the dependencies
(AGP 8.7.3, Compose BOM 2024.10.01, OkHttp 4.12, ML Kit text recognition 16.0.1) from
`google()` and `mavenCentral()`, so the build machine needs internet access.

Set the SDK location either in `local.properties` (`sdk.dir=/path/to/Android/sdk`) or via
`ANDROID_HOME`.

APK output:
`app/build/outputs/apk/debug/app-debug.apk` (debuggable) and
`app/build/outputs/apk/release/app-release.apk` (release, minified, not debuggable).

### Build the APK on GitHub (nothing to install locally)

Two GitHub Actions workflows do the whole build on a GitHub runner (JDK 17 + Android
SDK 35 come preinstalled on `ubuntu-latest`):

| Workflow | File | Runs on | Produces |
|---|---|---|---|
| **Build APK** | `.github/workflows/build-apk.yml` | every push, every pull request, manual *Run workflow* | `dist/LiveReply-<version>-debug.apk` + `-release-unsigned.apk` as the **live-ai-reply-apk-…** artifact on the run page |
| **Release APK** | `.github/workflows/release-apk.yml` | pushing a `v*` tag, or manual *Run workflow* with a tag | a GitHub **Release** with the APKs + `SHA256SUMS.txt` attached |

The [Releases page](https://github.com/ayushghbk-afk/Live-reply/releases/latest) carries
the published builds. A release APK that is *properly signed* (your key, no `debuggable`
flag) is produced as soon as the signing secrets below are set; without them the release
asset is explicitly named `…-release-unsigned.apk` so its state is never ambiguous, and the
debug APK is signed with a throwaway CI debug key.

Getting a runnable APK without any local Android SDK:

1. Push your branch, or open **Actions → Build APK → Run workflow**.
2. Open the finished run and download the **live-ai-reply-apk-\<sha\>** artifact (a zip).
3. Install it with `adb install -r LiveReply-<version>-release.apk` (or `-debug.apk`).
   Do **not** copy the APK to the phone and tap it: Play Protect blocks internet-sideloaded
   installs of apps with accessibility access (section 5.1), which is exactly what this app
   needs. `adb install` is not an internet-sideloading flow and is the supported developer
   route.
4. The run page also carries `dist/BUILD-REPORT.txt`: the exact package name, versionCode,
   versionName, minSdk/targetSdk, every requested permission, every component with its
   `exported` state, the signing certificate and the SHA-256 of each APK — the same facts
   the app's own CI verifies before publishing.

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
| `ANDROID_KEYSTORE_BASE64` | Your release keystore (`base64 -w0 signing/live-reply-release.keystore`). When present, Gradle signs **both** build types with it, the release APK is published as `LiveReply-<version>-release.apk`, and the workflow fails instead of publishing if the signature is missing. |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password. |
| `ANDROID_KEY_ALIAS` | Key alias inside the keystore. |
| `ANDROID_KEY_PASSWORD` | Key password (defaults to the keystore password). |

Without `ANDROID_KEYSTORE_BASE64` the release APK is attached **unsigned** and named
`…-release-unsigned.apk`; the debug APK is signed with a CI-generated debug key that differs
on every run (so a new debug APK cannot replace an older debug install — Android reports a
signature mismatch). Neither the keystore nor `keystore.properties` is ever uploaded, and no
API key or model id is ever baked into a CI build.

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
# release build (signed, not debuggable) - what you want on a real phone
adb install -r app/build/outputs/apk/release/app-release.apk

# debug build, for development
adb install -r app/build/outputs/apk/debug/app-debug.apk

# if an older copy signed with a different key is installed, remove it first
adb uninstall com.liveaireply.app
```

`adb install` prints the real error when an install fails (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`
means a different signing key; `INSTALL_FAILED_VERSION_DOWNGRADE` means the installed
versionCode is higher). A bare "App not installed" from the on-device installer hides that.

### 5.1 Google Play Protect — why the install is blocked

If you install this app from a browser, a messaging app or a file manager, Play Protect shows
**"App blocked to protect your device — This app can request access to sensitive data. This
can increase the risk of identity theft or financial fraud."** and refuses the install.

This is not caused by the debug build, by the app's label, by `QUERY_ALL_PACKAGES`, or by the
wording of the permission descriptions. Google's own developer guidance describes it exactly:

> Applications that are downloaded directly from online sources like web browsers, messaging
> apps, or file managers … If these applications also use sensitive permissions — `RECEIVE_SMS`,
> `READ_SMS`, `NOTIFICATION_LISTENER`, and `ACCESSIBILITY` — they're considered high-risk
> applications … When a user attempts to install an application from these sources and any of
> these sensitive permissions is declared, Google Play Protect will automatically block the
> installation.
>
> — [Developer Guidance for Google Play Protect Warnings](https://developers.google.com/android/play-protect/warning-dev-guidance)

It is a heuristic aimed at fraud malware (the permissions that steal OTPs and read banking
screens), it keys on the *declaration* of accessibility access, and it applies to every build
of this app — debug, release, signed or not.

What that means in practice:

| Route | Status |
|---|---|
| **`adb install`** (developer/testing) | Works. It is not an internet-sideloading install flow. |
| **Copy the APK to the phone and tap it** | Blocked by Play Protect while the app declares accessibility access. Not fixable by changing permissions or signing. |
| **Google Play** | The only route that removes the block for normal users. It requires a Play Console account, the accessibility declaration form, a privacy policy, and a prominent-disclosure flow. Note that Play policy currently restricts autonomy ("Don't use the API to autonomously initiate, plan, and execute actions or decisions"), so **Auto** mode may need to stay a sideload/ADB feature. |
| **Play Protect appeal** | If Play Protect flags the exact build as harmful (rather than applying the blanket sideload block), you can request a review: [appeals](https://developers.google.com/android/play-protect/warning-dev-guidance#request-appeal). Have the package name, versionCode, signing certificate SHA-256, the APK, and an explanation of the accessibility use ready. |
| **Turning Play Protect off** | Works, and is explicitly not recommended. It removes protection for every other app on the phone. |

What this repository does about it, honestly:

- It **removes the permissions that are not needed** (see section 7), so the app declares the
  minimum it can: accessibility, overlay, notifications, foreground service, media projection
  and network. It does not declare SMS or notification-listener access, which are the other
  permissions the same Play Protect rule keys on.
- It **discloses the accessibility capability** in the first-run screen, in the accessibility
  service description Android shows in Settings, and here.
- It **does not** claim `isAccessibilityTool="true"`, which would be the deceptive way to look
  eligible for fewer warnings — Google warns users specifically about apps that do that, and
  it is exactly the kind of evasion this project will not do.
- It **does not** obfuscate, rename or hide the accessibility service, and it ships a properly
  signed, non-debuggable release build so that the app has a stable, verifiable identity.
- What it **cannot** do is make Play Protect accept an internet-sideloaded accessibility app.
  That is a platform trust/reputation decision, not a code change.

## 6. First run

1. Open **Live AI Reply** → the setup wizard starts.
2. **Disclosure (step 1 of the wizard, cannot be skipped):** it states in plain language:
   *"This app uses Android Accessibility Service to read visible chat text and, when enabled,
   enter/send replies in supported chat applications."* — followed by what the service can
   technically see, what the overlay and the foreground service do, what the optional
   screen-capture/OCR fallback does with a captured frame, and where messages are sent.
   **Finish setup** stays disabled until the acknowledgement switch is on; the acknowledgement
   is stored (`acknowledged_capabilities`) and is not asked again.
3. **Accessibility**: tap *Open Accessibility settings* → *Downloaded services* →
   *Live AI Reply* → enable it. Read the description; it states exactly what is read. Android
   may ask you to confirm, because the app deliberately does not claim to be a
   disability-accessibility tool (see section 5.1).
4. **Overlay**: grant *Display over other apps* when prompted.
5. **Notifications** (Android 13+): allow them so the monitoring notification appears.
6. **AI provider**: keep `https://openrouter.ai/api/v1` (or set your own base URL),
   paste your API key, tap **Save key**. The key is encrypted; it is never displayed again.
7. **Model**: type any model id (e.g. `openai/gpt-4o-mini`) or tap **Fetch models**.
   Add fallback models comma-separated.
8. **Persona**: pick one, or create your own later in *Manage personas*.
9. **Mode**: choose Suggest / Approve / Auto.
10. **Test**: tap **Test AI**, or use **Test mode** to generate a reply for a typed message.
11. Turn **Monitoring** on. The floating `●` appears and the notification shows the mode.

## 7. Permissions

Everything the APK declares, and nothing else:

| Declared | Required? | Why |
|---|---|---|
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE` on the service) | **Yes — core** | Read the visible chat, find the composer, type and tap Send |
| `SYSTEM_ALERT_WINDOW` | Yes for the overlay | The floating control and suggestion card (granted by the user in Settings) |
| `POST_NOTIFICATIONS` | Yes on Android 13+ | The persistent notification with Pause/Stop |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Yes | Keeps the assistant alive while monitoring; the `specialUse` subtype property states what it is for |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Optional feature | Only while the opt-in OCR fallback holds a MediaProjection session |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Yes | Your AI endpoint only, plus detecting that the device is offline |
| `<queries>` for 7 chat packages | Not a permission | Package visibility for the apps this build has adapter hints for |

Deliberately **not** declared (and why), verified by searching the source for each API:

| Not declared | Reason |
|---|---|
| `QUERY_ALL_PACKAGES` | No code path enumerates installed apps; the apps the assistant may act on come from a fixed list matched against accessibility events |
| `RECEIVE_SMS`, `READ_SMS` | The app never reads or sends messages; it reads text already rendered on screen |
| Notification-listener access | Never used — no notification reading, no notification dismissal |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | The Permissions screen opens the system battery-optimisation **list** instead (no permission needed). The restricted one-tap exempt dialog is not used |
| `CAMERA`, `RECORD_AUDIO`, location, contacts, storage | Not used anywhere |
| `SYSTEM_ALERT_WINDOW`-style silent grants, device admin, install packages | Not used |

The API key is encrypted with a non-exportable Android Keystore key. Nothing is backed up
(`data_extraction_rules.xml` excludes everything). The app also skips password/PIN/OTP/card
fields and banking/authenticator screens, and honours the per-app exclusion list.

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
- **Play Protect blocks internet-sideloaded installs of this app by policy** (section 5.1).
  There is no permission change or signing change that fixes it; use `adb install`, publish on
  Google Play, or appeal.
- Play policy restricts accessibility-driven autonomy, so **Auto mode may not be acceptable
  for a Play Store release** even though it is fully functional in a sideloaded build.
- Android 14+ shows an extra confirmation when enabling a non-accessibility-tool service, and
  some OEM builds additionally require "Allow restricted settings" for sideloaded apps
  (App info → ⋮ → *Allow restricted settings*) before the accessibility toggle becomes active.
- This build was verified by compiling and unit-testing in CI; it has **not** been installed on
  a physical device or emulator in the development environment (no Android SDK or device was
  available), so on-device behaviour of the accessibility, overlay and capture layers is
  verified only as far as CI's static inspection goes - see `BUILD_NOTES.md`.
