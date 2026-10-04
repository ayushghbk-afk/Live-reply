# AccessibilityService disclosure and Google Play declaration draft

**Service:** `com.liveaireply.app.accessibility.LiveReplyAccessibilityService`
**Binding permission:** `android.permission.BIND_ACCESSIBILITY_SERVICE`
**Accessibility tool:** No (`android:isAccessibilityTool="false"`)

This draft must be submitted against the exact production build. The publisher should attach a
video showing every behavior described below and answer the current Play Console form exactly;
form wording can change.

## Primary purpose

Live AI Reply is a conversation reply assistant. The service observes visible conversation UI
in chat app package names explicitly selected by the user. It detects a new incoming message,
extracts a bounded recent context, identifies the visible message composer and a labelled Send
control, and enables reply suggestion/insertion according to the user-selected mode.

The app is not a disability-support accessibility tool and does not claim that designation.
Accessibility access is nevertheless essential to the core feature because Android does not
provide another general API for reading the visible chat UI and inserting a user-approved reply
across independent chat apps.

## Data accessed

The service may access:

- visible text, content descriptions, view IDs, geometry, and editable-state metadata in package
  names enabled by the user;
- the visible conversation title/contact label when exposed by the chat UI;
- the visible message composer and a labelled/clickable Send node.

The service does not request or use key-event filtering, touch exploration, notification access,
or gesture dispatch. Runtime `AccessibilityServiceInfo.packageNames` is set to exactly the
user's enabled list; an empty list is scoped to this app rather than represented as unrestricted
`null`.

## Actions performed

- **Suggest (default):** read selected visible chat UI and generate/display a suggestion. It
  does not write into or send from the chat automatically.
- **Approve:** use `ACTION_SET_TEXT` to stage the selected suggestion in the identified composer.
  It does not activate Send until the user does.
- **Auto:** only after a separate in-app disclosure and checkbox opt-in, use `ACTION_SET_TEXT`
  and `ACTION_CLICK` on a confidently identified, labelled Send node after a visible configured
  delay. Auto refuses and falls back to a suggestion when recipient, composer, Send node,
  screen, app, typing state, or confidence checks fail.
- **User Send from the overlay:** after a direct user tap, insert the reviewed/edited reply and
  activate the identified Send node.

The service does not change system settings, grant permissions, dismiss security warnings,
accept terms, make purchases, authorize payments, enter credentials, or use coordinates to tap
unknown UI.

## Prominent disclosure shown before the system setting

The first-run screen displays, before the Accessibility Settings button is enabled:

> Live AI Reply can read visible text from the apps you choose so it can understand
> conversations and generate replies. Text may be sent to your selected AI provider. The app
> does not need passwords, OTPs, PINs, banking information, or payment information.

The user must select:

> I understand and agree

Without that stored acceptance, monitoring, Accessibility processing, overlay, OCR, and Auto
are disabled at the repository, service, and engine boundaries. Merely enabling the Android
Accessibility service does not start monitoring.

## Data handling safeguards

- Disabled and excluded package events are rejected before the active window root is read.
- Password fields, credential/payment hints, and known sensitive package patterns stop
  extraction before an AI request.
- Every completion request passes through outbound redaction for OTPs, passwords, PINs, card
  numbers, CVVs, bank-account values, and authentication codes.
- Cleartext network traffic is disabled; requests go to the user-configured endpoint.
- The API key is Android-Keystore encrypted and excluded from backup.
- No advertising, analytics, SMS, contacts, phone, location, storage, microphone, camera,
  notification-listener, broad package-query, or app-install permission is declared.

## User control and stopping

A global STOP action is visible in the main app, optional overlay, and foreground notification.
It synchronously sets an in-memory stop latch, clears pending automation, stops the assistant
and MediaProjection foreground services, removes the overlay, releases screen capture, disables
Auto, and persists the stopped state. Restart requires a deliberate Monitoring action; Auto
requires a new explicit opt-in.

## Reviewer demonstration checklist

Record one continuous video that shows:

1. fresh install and the complete prominent disclosure;
2. the disabled Accessibility/OCR controls before “I understand and agree” is checked;
3. Android's Accessibility service description and enable flow;
4. package selection and dynamic scope explanation;
5. Suggest mode generating without typing/sending;
6. Approve mode staging without sending;
7. the entire Auto warning, checkbox, opt-in, delay, and a successful labelled-node send;
8. a safety refusal that falls back to a suggestion;
9. optional overlay grant plus in-app toggle;
10. optional one-shot MediaProjection confirmation and immediate release after OCR;
11. STOP from the app, overlay, and notification; and
12. Security & Settings showing statuses, provider, endpoint, data processing, and STOP.

## Policy risk that must not be hidden

Google Play's Accessibility API policy may prohibit or restrict autonomous action execution,
including Auto mode, depending on the policy and review interpretation in effect at submission.
The publisher must declare Auto exactly as implemented and obtain approval for the production
behavior. Do not disguise it, disable it only during review, use remote configuration to hide it,
or claim `isAccessibilityTool=true`. Transparent implementation improves legitimacy but does
not guarantee approval or eliminate Play Protect warnings.
