# Google Play listing draft

This is publication copy for the current implementation. The publisher must verify it against
the exact uploaded App Bundle/APK and add support contact details, screenshots, a hosted privacy
policy URL, and any required demonstration video.

## App name

Live AI Reply

## Short description

Draft AI replies for selected chats with clear privacy and user controls.

## Full description

Live AI Reply helps you draft responses to conversations visible in chat apps you select.
You choose an OpenAI-compatible provider and model, choose a reply style, and stay in control
of when the assistant runs.

**Suggest mode is the default**

- Detect a new visible incoming message in a selected chat app.
- Redact detected passwords, OTPs, PINs, card and bank details, CVVs, and authentication codes.
- Send the remaining recent context to the AI endpoint you selected.
- Show the generated suggestion for review.
- Nothing is typed or sent automatically in Suggest mode.

**Modes you control**

- Suggest: review, edit, copy, reject, or choose Send.
- Approve: stage a reply in the identified message composer; sending still requires your action.
- Auto: only after a separate warning and opt-in, insert a generated reply and activate the
  identified chat's labelled Send control after your chosen delay. Auto can be turned off at
  any time and is subject to safety checks.

**Transparent sensitive capabilities**

Accessibility Service is the core feature used to read visible conversation text in package
names you enable and identify the composer and a labelled Send control. It does not filter key
events or dispatch coordinate gestures. The floating assistant is optional and off by default.
Optional OCR is an accessibility fallback: each one-shot frame requires Android's normal screen
capture confirmation, is recognized on-device, and is immediately discarded. Screenshots are
never uploaded. Android's `FLAG_SECURE` protection is honored without workarounds.

**Immediate STOP**

Use STOP AI from the app, optional floating assistant, or persistent notification to stop the
assistant, remove the overlay, release screen capture, and block further automated replies.

**Your provider, your endpoint**

Configure OpenRouter or another OpenAI-compatible HTTPS endpoint. Your API key is encrypted
using Android Keystore and is excluded from backup. The selected provider processes request
text under its own privacy policy and account settings.

Important: AI-generated replies can be inaccurate or inappropriate. Review suggestions before
sending. Do not use Live AI Reply for passwords, OTPs, PINs, banking information, payment
information, emergencies, or high-stakes decisions.

## Suggested feature graphic / screenshot captions

1. “Suggest is the default — review before sending.”
2. “Choose exactly which chat apps Accessibility may process.”
3. “Optional overlay and one-shot OCR are off until you enable them.”
4. “See provider, endpoint, capability status, and data processing in one Settings screen.”
5. “STOP AI immediately ends monitoring, overlay, OCR, and automation.”

## Content and policy notes for the publisher

- Do not describe the app as an accessibility tool for disability support; that is not its
  primary purpose and `isAccessibilityTool` is correctly `false`.
- Do not imply that enabling Accessibility is optional for the core reply-assistant function.
- Show the in-app prominent disclosure and Auto-mode opt-in in listing screenshots/video.
- Disclose AI-generated content and the user-selected third-party AI provider.
- Google Play review may restrict or reject autonomous Accessibility API use. Submit the actual
  Auto behavior transparently in the Accessibility declaration; do not hide it or use remote
  configuration to change behavior during review.
- Approval is not guaranteed. Accessibility, overlay, and MediaProjection remain high-risk
  capabilities even when implemented transparently.
