# Live AI Reply

Live AI Reply is an Android conversation assistant for selected chat apps. It reads visible
conversation UI through an openly declared AccessibilityService, sends a redacted recent context
to an OpenAI-compatible endpoint chosen by the user, and generates a reply.

**Suggest is the default.** Optional reply insertion, a floating assistant, Auto mode, and
one-shot OCR are separately controlled and fully disclosed. A global **STOP AI** action ends
monitoring, overlay, capture, and automation immediately.

> Accessibility, overlay, and screen capture are high-risk Android capabilities. A valid,
> properly signed APK can still receive Google Play Protect warnings, especially when
> sideloaded. This project does not bypass or evade Play Protect, hide permissions, claim a
> false accessibility-tool purpose, or recommend disabling Play Protect. Public distribution
> requires transparent Google Play review, and approval is not guaranteed.

## Operating modes

| Mode | Behavior |
|---|---|
| **Suggest (default)** | Detect → redact sensitive values → generate → show. It never types or sends automatically. |
| **Approve** | Generate and stage the suggestion in the identified composer. It does not activate Send until the user does. |
| **Auto** | After a separate detailed warning and checkbox opt-in, insert and activate the identified labelled Send control after the configured delay, subject to all safety checks. |

Auto is not silently enabled by selecting a generic setting. The dialog explains that it may
read selected visible chat text, send a redacted excerpt to the selected AI endpoint, insert the
generated reply, and send without another tap. STOP or leaving Auto clears the opt-in.

## First-run disclosure and consent

Before the Accessibility Settings or MediaProjection controls are enabled, the app displays:

> Live AI Reply can read visible text from the apps you choose so it can understand
> conversations and generate replies. Text may be sent to your selected AI provider. The app
> does not need passwords, OTPs, PINs, banking information, or payment information.

The user must select **“I understand and agree.”** Merely enabling the service in Android does
not start monitoring. Settings, service, Accessibility, capture, and engine boundaries all gate
sensitive functionality on the stored acceptance.

## User-visible security controls

The **Security & Settings** screen shows:

- Accessibility status;
- overlay system-grant and in-app status;
- optional OCR / one-shot screen-capture status;
- selected AI provider and API endpoint;
- a plain-language data-processing explanation; and
- the global emergency STOP switch/button.

It also links directly to selected-app, AI-provider, privacy, notification, overlay, and Android
Accessibility controls.

## Accessibility implementation

Accessibility is the core feature and remains fully declared:

- service permission: `android.permission.BIND_ACCESSIBILITY_SERVICE`;
- `android:isAccessibilityTool="false"` (this is not primarily a disability-support tool);
- event types: window state, window content, and view text changes;
- retrieves visible window content and view IDs to find chat bubbles/composer/labelled Send;
- package delivery dynamically restricted to the user's enabled package names;
- disabled/excluded apps rejected before reading the active root;
- password, credential/payment, banking/wallet/authenticator, and excluded screens skipped;
- no key-event filtering, touch exploration, interactive-window enumeration, or coordinate
  gesture dispatch; and
- reply insertion/click uses identified node actions only.

The default selected packages are WhatsApp, Telegram, and Instagram. The user can turn each off,
enable other listed apps, or add an exact custom chat package name. An empty selection is never
converted to an unrestricted Accessibility package scope.

## Sensitive-data filtering

Every completion request passes through `PrivacyFilteringAiProvider` at the final outbound
provider boundary. It redacts detected:

- OTPs and one-time codes;
- passwords/passphrases;
- PINs/passcodes;
- credit/debit card number candidates;
- CVV/CVC values;
- bank-account and IBAN values; and
- authentication, verification, login, security, 2FA, and recovery codes.

Standalone 4–8 digit values are conservatively treated as possible authentication codes. The
filter is defense in depth, not a reason to process secrets: the app says it does not need those
values and sensitive screens are skipped before prompt construction.

## Optional OCR / MediaProjection

OCR is off by default and is only an accessibility fallback:

1. Accept the disclosure.
2. Enable optional OCR.
3. Tap **Authorize one OCR fallback**.
4. Approve Android's normal MediaProjection dialog.
5. If the next enabled chat exposes no conversation text through Accessibility, the app
   captures one cropped frame, recognizes it on-device with ML Kit, recycles the bitmap, releases
   the virtual display/projection, and stops the capture foreground service.

A fresh fallback requires fresh Android confirmation. Pixels and recognized text are never
written to disk or uploaded by OCR. Android excludes `FLAG_SECURE` content from MediaProjection;
no workaround or alternate capture path exists. The app's own floating overlay is also marked
`FLAG_SECURE`.

Google states that ML Kit does not send OCR input or resulting output to its servers. The SDK
does send operational metrics (device/app information, a per-installation identifier,
performance, API configuration, feature input/output size, and feature version) to Google for
diagnostics and usage analytics. See the [privacy policy](docs/PRIVACY_POLICY.md) and [Data Safety
working sheet](docs/DATA_SAFETY.md).

## Optional overlay

The overlay is off by default. It appears only when all are true:

- disclosure accepted;
- Android's “Display over other apps” grant exists;
- the user enabled **Show floating assistant** in the app;
- Monitoring is on; and
- global STOP is not active.

The overlay is removed when any gate becomes false. Its collapsed control opens to a suggestion
card with review actions and STOP.

## Global STOP

STOP is available from the app, optional overlay, and persistent foreground notification. It:

- sets an immediate process-wide stop latch;
- clears pending engine work and prevents post-network-call sending;
- stops the foreground assistant;
- removes and disables the overlay;
- releases and disables OCR/MediaProjection;
- disables Monitoring and Auto; and
- prevents further automated replies.

Restarting requires a deliberate Monitoring action. Auto requires a fresh opt-in.

## Permissions

The source manifest declares these seven `<uses-permission>` entries:

| Permission | Why retained |
|---|---|
| `INTERNET` | User-selected AI API request |
| `ACCESS_NETWORK_STATE` | Explain offline state before requesting |
| `POST_NOTIFICATIONS` | Visible foreground status and Pause/Stop on Android 13+ |
| `FOREGROUND_SERVICE` | Base assistant/capture foreground-service permission |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Accessibility-driven assistant session on Android 14+ |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | User-confirmed one-shot OCR session on Android 14+ |
| `SYSTEM_ALERT_WINDOW` | Optional user-controlled floating assistant |

`BIND_ACCESSIBILITY_SERVICE` is specified on the accessibility service so only Android can bind
it; it is not a requested app permission. The AndroidX manifest merger additionally generates
`com.liveaireply.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and requests that app-signature
permission to protect AndroidX runtime receivers on older platform versions. It grants no access
to device data and is present in the inspected merged APK.

Not declared: `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `REQUEST_INSTALL_PACKAGES`, SMS,
contacts, phone, location, storage, camera, microphone, notification-listener, device-admin,
VPN, exact-alarm, or ignore-battery-optimization access. The obsolete `<queries>` package list
was removed because this implementation never enumerates installed packages.

## Dependencies

Direct runtime dependencies are limited to libraries actively used by the app:

- AndroidX Core, Activity Compose, Lifecycle, Compose UI/graphics/Material 3;
- AndroidX DataStore;
- Kotlin coroutines;
- OkHttp; and
- on-device Google ML Kit text recognition.

JUnit is test-only. Unused Navigation Compose, Material Icons, Compose tooling/preview,
coroutines-test, AndroidX instrumented-test dependencies, and the unused manifest receiver were
removed. See [`AUDIT_REPORT.md`](AUDIT_REPORT.md) for the dependency and component audit.

## Build

Requirements: JDK 17, Android SDK platform 35, and build-tools 35.0.0.

```bash
./gradlew clean
./gradlew test
./gradlew assembleDebug
./gradlew assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk` when production signing is configured, or
  `app-release-unsigned.apk` when it is not.

### Release signing

Production keys are never committed. Configure `keystore.properties` (gitignored) or:

- `LIVEREPLY_KEYSTORE_FILE`
- `LIVEREPLY_KEYSTORE_PASSWORD`
- `LIVEREPLY_KEY_ALIAS`
- `LIVEREPLY_KEY_PASSWORD`

See [`signing/README.md`](signing/README.md). Without a configured key, Gradle intentionally
produces an **unsigned** release APK rather than disguising a debug key as production signing.
A release intended for Google Play should use a stable protected upload/app-signing key.

Verify every produced APK from the latest Android SDK build-tools:

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

`tools/ci/inspect-apks.sh` additionally records package/version/SDK, complete merged permission
list, components/exported state, signer-certificate SHA-256, APK SHA-256, and byte size.

## Tests

```bash
./gradlew test
```

The JVM suite covers extraction, direction classification, duplicate/loop prevention, prompt
construction, provider errors/retries, reply validation, sensitive-data redaction, automation
gates, safe defaults, emergency stop behavior, personas, storage codecs, and engine modes.

For constrained environments without an Android SDK, `tools/jvm-verify/run-tests.sh` compiles
and executes the same platform-neutral source/tests with a JDK and Kotlin compiler. That check is
useful but does not replace the requested Gradle Android build.

## Public distribution preparation

- [Privacy policy](docs/PRIVACY_POLICY.md)
- [Google Play listing draft](docs/PLAY_STORE_LISTING.md)
- [Accessibility declaration and reviewer-video checklist](docs/ACCESSIBILITY_DECLARATION.md)
- [Data Safety working sheet](docs/DATA_SAFETY.md)
- [Full source/artifact audit](AUDIT_REPORT.md)

The publisher must host the policy at a public URL, add legal/support contacts, complete the
current Play Console forms, provide an accurate Accessibility demonstration video, and disclose
Auto exactly as implemented. Google Play's Accessibility policy may restrict autonomous action
execution. Do not hide Auto during review, use remote configuration to evade review, or falsely
claim disability-tool status.

## Play Protect and installation expectations

A debug certificate, valid v2/v3 signature, target SDK 35, minimal permission list, disclosure,
and privacy safeguards establish technical legitimacy; they do **not** guarantee Play Protect
will allow installation. AccessibilityService, overlay, and screen capture are intentionally
high-risk capabilities. Internet-sideloaded builds can still be blocked or warned about, and a
Google Play submission can still be rejected after policy/security review.

The solution is not to disable Play Protect. For public distribution, submit the honestly
described production build through legitimate Google Play review and respond to any specific
policy or malware-classification feedback with the package, version, signing-certificate digest,
artifact hash, privacy policy, permission justification, and demonstration materials.

## Important limitations

- Chat app UI structures change; unidentified composer/Send controls fall back to suggestions.
- AI output can be inaccurate or inappropriate; review before sending.
- Pattern redaction is conservative but cannot recognize every secret format.
- Provider retention and training settings are controlled by the selected provider.
- MediaProjection cannot capture protected `FLAG_SECURE` content, by design.
- This repository's automated build does not constitute a physical-device installation or
  runtime test. Device testing must be reported only when it actually occurs.
