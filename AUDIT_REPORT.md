# Live AI Reply security, permission, and APK audit

**Audit date:** 4 October 2026
**Source branch:** `arena/01a107c2-live-reply`
**Package:** `com.liveaireply.app`
**Version:** `1.0.1` (`versionCode` 2)
**Compile / target / minimum SDK:** 35 / 35 / 24

> Artifact hashes, sizes, signing-certificate digests, and `apksigner` output below come from the
> identified CI build/inspection run after source validation. Source declarations alone are not
> treated as proof of merged-APK contents; the ephemeral debug key also means later debug builds
> will have a different signer and APK hash.

## 1. Permission audit

### Retained manifest permissions

| Permission | Current implementation that requires it |
|---|---|
| `android.permission.INTERNET` | HTTPS requests to the AI API endpoint selected by the user |
| `android.permission.ACCESS_NETWORK_STATE` | `OkHttpTransport.isOnline()` checks active network capability before an AI request |
| `android.permission.POST_NOTIFICATIONS` | Android 13+ user-visible foreground monitoring/capture notifications with Pause/Stop |
| `android.permission.FOREGROUND_SERVICE` | Base permission for `AssistantService` and one-shot `ScreenCaptureService` |
| `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ `AssistantService` foreground type; manifest property explains the accessibility-driven conversation assistant |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14+ foreground type while a user-confirmed MediaProjection token is armed/consumed for OCR |
| `android.permission.SYSTEM_ALERT_WINDOW` | Optional floating assistant; requires both Android's grant and an in-app user switch |

`android.permission.BIND_ACCESSIBILITY_SERVICE` is not requested as a `<uses-permission>`.
Android requires it on the exported accessibility service declaration so only the system can
bind that service.

The inspected merged APK has one additional generated declaration/use:
`com.liveaireply.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. AndroidX Core adds this
app-signature permission to protect dynamically registered, not-exported receivers on older
Android versions. It grants no device-data capability and cannot be held by an app signed with
a different certificate.

### Removed / deliberately absent

The prior manifest's seven package-visibility `<queries>` entries were removed because no code
queries installed packages. They were not permissions, but retaining unused visibility was not
minimal.

The app does not declare `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`,
`REQUEST_INSTALL_PACKAGES`, `READ_SMS`, `RECEIVE_SMS`, contacts, phone, location, storage,
camera, microphone, notification-listener, device-admin, VPN, exact-alarm, or battery-exemption
permissions/capabilities. Repository search and merged-APK inspection are both required checks.

No retained permission can be removed without removing the corresponding implemented feature:
network AI requests, user-visible foreground operation, optional MediaProjection OCR, or the
optional overlay.

## 2. Components and service declarations

### App-owned components

| Component | Type | Exported | Permission / type | Behavior |
|---|---|---:|---|---|
| `.MainActivity` | Activity | Yes | Launcher only | First-run disclosure, controls, Security & Settings |
| `.accessibility.LiveReplyAccessibilityService` | Accessibility service | Yes (system-bindable) | `BIND_ACCESSIBILITY_SERVICE` | Core selected-app visible text detection and guarded node insertion/click |
| `.engine.AssistantService` | Foreground service | No | `specialUse` | Runs only after disclosure + Monitoring + connected Accessibility; non-sticky |
| `.ocr.ScreenCaptureService` | Foreground service | No | `mediaProjection` | Arms only with `RESULT_OK` from Android's confirmation; consumes one frame and stops |

The source manifest has no provider, activity alias, or receiver. A previously declared but
unused internal notification receiver was removed; notification actions target the appropriate
unexported service directly.

### Dependency-added merged components

| Component | Type | Exported | Binding permission / purpose |
|---|---|---:|---|
| `com.google.mlkit.common.internal.MlKitComponentDiscoveryService` | Service | No | ML Kit registrar discovery |
| `com.google.mlkit.common.internal.MlKitInitProvider` | Provider | No | ML Kit initialization |
| `com.google.android.gms.common.api.GoogleApiActivity` | Activity | No | Google API result plumbing |
| `androidx.startup.InitializationProvider` | Provider | No | Emoji/lifecycle/profile initialization |
| `androidx.profileinstaller.ProfileInstallReceiver` | Receiver | Yes | Protected by system signature permission `android.permission.DUMP` |
| `com.google.android.datatransport.runtime.backends.TransportBackendDiscovery` | Service | No | ML Kit metrics transport backend discovery |
| `com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService` | Service | No | Protected by `android.permission.BIND_JOB_SERVICE` |
| `com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver` | Receiver | No | ML Kit metrics scheduling fallback |

The complete inspected merge therefore exports the launcher activity, the system-bindable
AccessibilityService, and AndroidX's `DUMP`-protected profile receiver. No unprotected service,
provider, or library receiver is exported.

## 3. Accessibility implementation

- Core capability retained; `isAccessibilityTool=false` is stated honestly.
- First-start metadata scopes event delivery to three default selected chat packages. At
  runtime, `AccessibilityServiceInfo.packageNames` is replaced with exactly the user's enabled
  list. Empty selection is scoped to this app, never unrestricted `null`.
- Disabled/excluded events are rejected before `rootInActiveWindow` is read.
- Password, credential, payment, banking/wallet/authenticator, and excluded screens are
  evaluated before conversation extraction or OCR.
- Required capabilities: visible window content, view IDs, not-important views, and
  window/content/text change events.
- Removed unused/high-risk capabilities: interactive-window enumeration and gesture dispatch.
  Key-event filtering remains explicitly false. Reply insertion uses identified-node
  `ACTION_SET_TEXT`; sending uses a labelled/clickable node's `ACTION_CLICK`.

## 4. Optional MediaProjection / OCR

OCR defaults off. The user must accept the disclosure, enable the OCR switch, tap **Authorize
one OCR fallback**, and approve Android's normal MediaProjection activity. The resulting token
starts an unexported mediaProjection foreground service from the visible activity. It is never
started by Accessibility, boot, notification parsing, polling, or app startup.

Accessibility text is tried first. If no conversation turns are exposed and one-shot capture is
armed, one cropped conversation frame is recognized by on-device ML Kit. The bitmap is never
written or uploaded, is recycled in `finally`, and the projection/virtual display/service are
released whether OCR succeeds or fails. A fresh fallback requires fresh system confirmation.

Android's compositor excludes `FLAG_SECURE` layers from MediaProjection. This implementation
uses the public MediaProjection output only, marks its own overlay `FLAG_SECURE`, and contains no
root, alternate screenshot, accessibility-screenshot, DRM, or secure-flag workaround.

## 5. Optional overlay

The in-app default is off. The overlay attaches only when disclosure is accepted, Monitoring is
on, Android's draw-over-other-apps grant exists, the user enabled the in-app overlay switch, and
STOP is not active. Turning the switch/Monitoring off or using STOP removes it. Its collapsed
control can be opened and dragged, and the expanded card provides suggestion actions and STOP.

## 6. Disclosure, data processing, and sensitive-data filter

Before Accessibility Settings or MediaProjection controls become available, first run shows:

> Live AI Reply can read visible text from the apps you choose so it can understand
> conversations and generate replies. Text may be sent to your selected AI provider. The app
> does not need passwords, OTPs, PINs, banking information, or payment information.

The required checkbox is **“I understand and agree”**. Safety invariants at settings, service,
Accessibility, capture, and engine boundaries keep sensitive features disabled before consent;
this is not only a disabled UI button.

Every app completion call is wrapped at the final `AiProvider` boundary. Before serialization,
all system/user/assistant message content is redacted for labelled passwords, OTPs, PINs,
CVV/CVC, bank accounts, IBANs and authentication codes; 13–19 digit card candidates and
standalone 4–8 digit possible authentication codes are conservatively redacted. Tests verify
that raw values never reach the delegate provider.

## 7. Automation and STOP

Suggest is the source/persistence default and does not type or send automatically. Auto requires
all of: disclosure accepted, Auto mode selected through a detailed warning dialog, a dedicated
checkbox confirming it can type/send without another tap, Monitoring on, and all runtime safety
checks. Disabling Auto, switching modes, or STOP clears the Auto acknowledgement.

STOP sets a synchronous process-wide latch before asynchronous persistence. It then clears the
engine's pending suggestion/in-flight send boundary, stops `AssistantService`, removes and
disables the overlay, stops/releases and disables `ScreenCaptureService`/OCR, disables
Monitoring/Auto, and prevents further accessibility processing or sending. The engine rechecks stopped state after an
in-flight AI call and immediately before insertion/send.

## 8. Dependency audit

Direct production dependencies retained and used:

- AndroidX Core, Activity Compose, Lifecycle runtime/service/ViewModel, Compose UI/graphics and
  Material 3 — Android lifecycle, UI, foreground service and notifications;
- AndroidX DataStore — local non-secret settings;
- Kotlin coroutines — lifecycle/background work and flows;
- OkHttp — selected AI endpoint transport;
- Google ML Kit text recognition — on-device optional OCR; its separate Google operational
  metrics collection is disclosed in the privacy/Data Safety documents; and
- JUnit (test scope only) — JVM unit tests.

Removed as unused: Navigation Compose, Material Icons Core, Compose tooling/preview,
`kotlinx-coroutines-test`, AndroidX instrumented JUnit/Espresso, the Android test runner, and the
unused internal notification receiver. The API key is not a Gradle/resource dependency or build
secret.

## 9. Public-distribution preparation

- Privacy policy: [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md)
- Store listing copy: [`docs/PLAY_STORE_LISTING.md`](docs/PLAY_STORE_LISTING.md)
- Accessibility declaration/reviewer-video checklist:
  [`docs/ACCESSIBILITY_DECLARATION.md`](docs/ACCESSIBILITY_DECLARATION.md)
- Data Safety working sheet: [`docs/DATA_SAFETY.md`](docs/DATA_SAFETY.md)

The publisher must host the privacy policy, add legal/support contact details, complete current
Play Console forms, supply an accurate reviewer video, protect the production signing key, and
submit the actual Auto behavior. Google Play approval and warning-free installation are not
guaranteed. Accessibility, overlay, and MediaProjection are high-risk capabilities, especially
in a sideloaded APK. Disabling Play Protect is not recommended or presented as a solution.

## 10. Verification results

GitHub Actions run [`37224652485`](https://github.com/ayushghbk-afk/Live-reply/actions/runs/37224652485)
on application-source commit `8e16da4` completed successfully. It executed the requested
commands separately and all four returned success:

```text
./gradlew clean
./gradlew test
./gradlew assembleDebug
./gradlew assembleRelease
```

The same source tree passed the offline platform-neutral suite: **202 tests run, 202 passed,
0 failed**. XML parsing covered 17 files and `git diff --check` passed.

### Debug APK

| Field | Result |
|---|---|
| Build path | `app/build/outputs/apk/debug/app-debug.apk` |
| Artifact name | `LiveReply-1.0.1-debug.apk` in artifact `live-ai-reply-apk-8e16da4a3ee70e67e5273cbd9e5d25492807ca18` |
| Size | 54,593,693 bytes |
| APK SHA-256 | `645497000542632695deb534728bd0f0bdd22cddb9899cf4027521b2a7f0b92b` |
| `apksigner verify` | Exit 0; verifies with APK Signature Scheme v2; v1/v3/v3.1/v3.2/v4 false |
| Signers | 1; RSA 2048 |
| Certificate DN | `C=US, O=Android, CN=Android Debug` |
| Certificate SHA-256 | `e4b71bfd292bf263cf39211d89d8ccef906880e1982ff00ee76ba1fa4f4e7740` |

The workflow generated that debug key for this run. It is ephemeral and not a production/upload
identity; a later CI debug build can have a different certificate and cannot update this APK.

### Release build output

| Field | Result |
|---|---|
| Build path | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Artifact name | `LiveReply-1.0.1-release-unsigned.apk` in artifact `live-ai-reply-apk-8e16da4a3ee70e67e5273cbd9e5d25492807ca18` |
| Size | 44,792,881 bytes |
| APK SHA-256 | `0b9b15120f76ac572e875404d828d3539c56ad2a760b89de1bf62b1a5335faf8` |
| `apksigner verify` | Exit 1: `DOES NOT VERIFY`; `ERROR: Missing META-INF/MANIFEST.MF` |
| Certificate SHA-256 | None — the release output is unsigned |

`assembleRelease` succeeded, but an unsigned APK is not an installable/distributable release.
The repository has no production key by design, and no release-signing secrets were configured
for this run. A publisher must configure its protected stable upload/app-signing key, rebuild,
and rerun `apksigner verify --verbose --print-certs` before distribution.

Both APKs report package `com.liveaireply.app`, `versionCode=2`, `versionName=1.0.1`, minimum SDK
24, compile SDK 35, and target SDK 35. The complete merged permission and component inventories
are recorded in sections 1 and 2 above and in the CI `BUILD-REPORT.txt` artifact.

### Runtime evidence

No APK was installed on a physical Android device or emulator. Runtime behavior—including the
floating-overlay expansion fix—has therefore compiled successfully but has not been confirmed
by a physical-device interaction test.
