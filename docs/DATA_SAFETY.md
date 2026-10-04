# Google Play Data Safety working sheet

This is a technical inventory, not a completed legal declaration. Google changes the Data
Safety questionnaire and definitions over time. The account owner must answer the current form
for the exact production build, selected AI provider defaults, support operations, and any
changes made by the distributor.

## Current-build data flow inventory

| Data category | Accessed/collected? | Purpose | Handling |
|---|---:|---|---|
| User content — visible chat text | Yes, when Monitoring is on in selected apps | App functionality: understand context and generate a reply | Bounded recent context; sensitive-value redaction; sent to user-selected AI endpoint; not stored by this app |
| User content — OCR frame | Locally processed only, after one-shot Android confirmation | Optional accessibility fallback | ML Kit on-device; in memory; immediately recycled; never sent or stored |
| App activity — enabled/excluded package names and settings | Yes, locally | App functionality and privacy controls | Stored in private DataStore; excluded from backup |
| Authentication information — AI API key | Yes, user enters it | Authenticate to selected AI provider | AES-GCM ciphertext locally; key in Android Keystore; plaintext used only for request authorization; excluded from backup |
| Diagnostics | Yes, locally and ephemerally | App functionality/troubleshooting | Bounded process-memory buffer; secret-redacted; never uploaded; not persisted |
| Device or other IDs | No app-generated analytics/advertising IDs | — | No analytics or advertising SDK |
| Contacts, SMS, call logs, phone | No | — | No permission/API |
| Location | No | — | No permission/API |
| Photos/files/storage | No | — | No storage permission; OCR frame is not a user file and is never written |
| Audio/camera | No | — | No permission/API |
| Financial information | Not needed; actively filtered/skipped | — | Users are told not to provide it; card/CVV/bank patterns are redacted before AI requests |

## “Collected” and “shared” considerations

Conversation text leaves the device for the user-selected AI provider. Even though this app's
developer does not operate a backend and the transfer is necessary for user-requested app
functionality, the Play form may still classify this as collection and/or sharing depending on
its current definitions and the provider relationship. Do not answer “not collected” solely
because the endpoint is selected by the user.

Recommended conservative review posture:

- declare **User content / Messages or other in-app communications** as processed for **App
  functionality**;
- disclose that processing is ephemeral in this app but provider retention depends on the
  selected provider's policy/account settings;
- declare encryption in transit only for HTTPS endpoints (the app disables cleartext traffic);
- do not claim provider-side deletion unless the selected provider contract actually supports
  it and the app/public support process explains how to request it;
- do not declare financial or authentication content as an intended collection purpose—the app
  explicitly says it does not need these and redacts detected values—but document that no
  pattern filter can guarantee recognition of every possible secret format; and
- reassess “shared” under the current form's service-provider/user-initiated-transfer
  exceptions with qualified policy/legal review.

## Security practices to declare only if still true in the uploaded build

- Data is encrypted in transit: manifest blocks cleartext, and configured production endpoints
  must be HTTPS.
- API credential at rest is encrypted with Android Keystore AES-GCM.
- Backups/device transfer exclude preferences, files, and databases.
- Users can clear their API key, clear diagnostics, clear Android app data, or uninstall.
- No account creation exists in this app; provider accounts are external.
- No analytics, ad network, or developer conversation backend is present.

## Play Console permission declarations

Explain each sensitive capability consistently with the listing and in-app disclosure:

- AccessibilityService: core visible-chat understanding and controlled reply insertion.
- `SYSTEM_ALERT_WINDOW`: optional, user-controlled floating status/suggestion UI.
- MediaProjection foreground service: optional one-shot, user-confirmed, on-device OCR fallback.
- Foreground special-use service: transparent monitoring session with persistent Stop action.
- Notifications: visible monitoring/capture state and immediate Pause/Stop control.

Attach `docs/ACCESSIBILITY_DECLARATION.md`, the hosted `docs/PRIVACY_POLICY.md`, the reviewer
video, screenshots of Security & Settings, and an exact built-artifact permission/component
report. None of these materials guarantee Google Play approval.
