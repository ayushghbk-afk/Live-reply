# Live AI Reply Privacy Policy

**Effective date:** 4 October 2026
**App package:** `com.liveaireply.app`

Live AI Reply is a user-configured conversation reply assistant. This policy describes the
current open-source implementation. If a distributor changes the code, adds analytics, or
operates a proxy/backend, that distributor must update this policy and the Google Play Data
Safety answers before publication.

## Data the app accesses

With the user's explicit Android and in-app authorization, the app can access:

- visible conversation text and UI labels in the chat app package names the user enables;
- the visible message composer and a labelled Send control, for user-requested insertion or
  separately opted-in automation;
- one screen frame for an optional OCR fallback, but only after Android's normal
  MediaProjection confirmation for that one-shot fallback;
- AI provider configuration, model preferences, persona instructions, and an API key entered
  by the user;
- network availability, so the app can explain that an AI request cannot run offline.

The app does **not** request SMS, contacts, call logs, phone, location, camera, microphone,
storage, package-installation, or broad installed-app-list access.

## Why data is processed

Visible conversation text is processed to detect a new incoming message, construct a limited
recent context, and generate a reply. Composer and Send controls are used only for the mode
the user chose:

- **Suggest (default):** generate and display; do not type or send automatically.
- **Approve:** place a suggestion in the identified composer for review; do not activate Send
  until the user does.
- **Auto:** after a separate explicit opt-in, insert a generated reply and activate the
  identified chat's labelled Send control, subject to the app's safety checks and delay.

## AI provider transmission

The configured number of recent visible conversation lines, persona/prompt instructions,
model settings, and request metadata may be transmitted over TLS to the AI API endpoint the
user selected. The app has no developer-operated conversation backend. The selected AI
provider processes transmitted text under that provider's own privacy policy, retention
terms, and account settings.

Before every completion request, the app redacts detected:

- OTPs and one-time codes;
- passwords and passphrases;
- PINs and passcodes;
- credit/debit card numbers;
- CVV/CVC values;
- bank-account and IBAN values; and
- authentication, verification, login, 2FA, security, and recovery codes.

Filtering is deliberately conservative but cannot guarantee that every possible secret format
will be recognized. Users should not use the app to process credentials, banking information,
or payment information.

## Accessibility Service

Accessibility access is the core product feature. The service receives window/content/text
change events and retrieves visible window content for package names selected by the user. It
uses view IDs and node actions to identify visible chat text, the composer, and a labelled Send
control. The service does not request key-event filtering, touch exploration, or gesture
dispatch. Android event delivery is dynamically restricted to the enabled package list; an
empty list is never treated as access to all packages.

The service skips excluded packages, known banking/wallet/authenticator/password-manager
package patterns, password fields, and screens with credential or payment hints. It is declared
honestly as a non-accessibility-tool service because its primary purpose is conversation
assistance, not disability support.

## Optional screen capture and OCR

OCR is off by default and is only a fallback when Accessibility exposes no conversation turns.
The user must first enable OCR in the app, tap **Authorize one OCR fallback**, and approve
Android's MediaProjection dialog. That authorization is consumed by one frame. Text recognition
runs on the device; the bitmap is kept only in memory, recycled immediately, never written to
disk, and never uploaded.

Android excludes content protected with `FLAG_SECURE` from MediaProjection. Live AI Reply uses
only the public MediaProjection API and implements no alternate capture or workaround for that
protection.

## Optional overlay

The floating assistant is off by default. It appears only when the user grants Android's
“Display over other apps” access **and** enables the in-app floating-assistant switch while
monitoring. The overlay is removed when the switch is turned off, monitoring stops, or the
global STOP action is used. The app's overlay itself is marked `FLAG_SECURE`.

## Storage and security

- The API key is encrypted with AES-GCM using a non-exportable Android Keystore key.
- Android backup and device-transfer rules exclude all app preferences, files, and databases.
- Conversation screenshots are never stored.
- The current app has no persistent conversation-history store.
- Diagnostics are held in a bounded in-memory buffer, are not uploaded by Live AI Reply, and
  pass through secret redaction. They disappear when the process ends or the user clears them.
- Cleartext HTTP traffic is disabled in the app manifest.

On-device OCR uses Google's standalone ML Kit SDK. Google states that OCR input pixels, text,
and resulting output are processed on-device and are not sent to Google. ML Kit does send
operational metrics to Google, including device/app information, a per-installation identifier,
performance, API configuration, feature input/output size, and feature version, for diagnostics
and usage analytics. Google states this metrics data is encrypted in transit and is not shared
with third parties. See Google's current disclosures:
<https://developers.google.com/ml-kit/android-data-disclosure> and
<https://developers.google.com/ml-kit/terms>.

The chosen AI provider may retain request data according to its own policy; users must review
that provider's settings and terms.

## Sharing, sale, and advertising

This implementation does not sell personal data, use it for advertising, or include an ad SDK
or a general-purpose developer analytics SDK. Conversation text is disclosed only to the AI
endpoint selected by the user for the user-requested reply-generation function. Google ML Kit
processes OCR input/output on-device but separately sends the operational metrics described
above to Google. The direct dependencies are AndroidX UI and lifecycle libraries, Android
DataStore, Kotlin coroutines, OkHttp, and on-device Google ML Kit text recognition.

## User controls and deletion

Users can:

- choose and exclude package names;
- turn Monitoring, overlay, and OCR off independently;
- reject suggestions and pause a conversation;
- clear the API key and in-memory diagnostics;
- use **STOP AI** in the app, optional overlay, or foreground notification to synchronously
  block further processing and automation, stop both foreground services, turn off/remove the
  overlay and OCR fallback, and release MediaProjection; and
- revoke Accessibility, overlay, notification, or screen-capture access in Android.

Uninstalling the app or clearing its Android app data removes app preferences and encrypted
credential ciphertext. Provider-side deletion requests must be directed to the selected AI
provider.

## Children

The app is not designed or directed specifically to children. A publisher must set the correct
Google Play target-audience declarations for its intended distribution.

## Changes and contact

Material policy changes should update the effective date and be shown to users before newly
expanded data access. Questions and deletion/support requests for this open-source build can be
filed at <https://github.com/ayushghbk-afk/Live-reply/issues>. A public distributor should add a
monitored support email and its legal entity/contact details before listing the app.
