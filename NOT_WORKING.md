# What is not working — Live AI Reply defect report

**Date:** 4 October 2026
**Branch:** `arena/01a10894-live-reply`, branched from `main` @ `527fb72`
**Method:** full static review of the Android source + CI history. No physical device /
emulator was available in this sandbox, so items are code-verified; where behavior
depends on a device it is marked **needs device verification**.

---

## 0. First, what IS working

The build is **not** broken:

- GitHub Actions (latest run `37227154243` on `main`): `./gradlew clean`, `test`,
  `assembleDebug`, `assembleRelease` all pass; unit suite passes on CI; the offline
  harness passes 202/202 JVM tests.
- Manifest, permissions, signing config, workflows and the audit docs are coherent.

So the problems are **runtime defects and configuration gaps**: the APK installs, but
the assistant freezes or silently never replies, and several states look broken to a
user. In order of impact:

| # | Area | Symptom a user reports |
|---|---|---|
| 1 | Main-thread AI calls from the accessibility service | "App freezes / dies when a message arrives" |
| 2 | Blocking work on overlay button taps | "Overlay buttons freeze the screen" |
| 3 | Accessibility tree scanned twice, once on main thread | Everything is laggy while a chat is open |
| 4 | `startForegroundService` from background is not guarded | App can crash-loop after reboot / swipe-away |
| 5 | Monitoring FGS never notices accessibility is revoked | "It says Monitoring but nothing happens" |
| 6 | Persistent notification never updates; Pause has no Resume | Stale status; can't resume from the shade |
| 7 | Conversation ID flips when the chat title appears | One reply randomly skipped when opening chats |
| 8 | "User is typing" inferred from any window event | Auto-send randomly stays a suggestion |
| 9 | Expanded overlay card can't scroll | STOP/Send buttons unreachable on small screens |
| 10 | No default model / API key | "It does nothing" until fully configured |
| 11 | CI APK signing story | "App not installed" when updating / installing release |
| 12 | Accessibility + monitoring off by default | "It does nothing at all" first run |
| 13 | Chat-app view IDs drift between app versions | Silent fallback to suggestion-only |

---

## 1. CRITICAL — AI network call runs on the main thread (freeze / ANR)

**Where:** `app/src/main/java/com/liveaireply/app/accessibility/LiveReplyAccessibilityService.kt`

The service scope is `Dispatchers.Main.immediate` (line ~52). Snapshot building is
already correctly moved off the main thread, but the continuation that calls the
engine comes back to Main:

```kotlin
// LiveReplyAccessibilityService.processNow()
scope.launch {                                                    // Main.immediate
    val built = withContext(Dispatchers.Default) { buildSnapshot(...) } ?: return@launch
    ...
    automation.refresh()                                          // on MAIN
    engine.handleSnapshot(SnapshotInput(...))                     // on MAIN  <-- the bug
}
```

`ReplyEngine.handleSnapshot()` → `generateForIncoming()` → `ReplyPipeline.generate()` →
`OkHttpTransport.execute()` calls OkHttp **synchronously** (`scoped.newCall(request).execute()`),
with up to `retryCount + 1` attempts (default 3) separated by `Thread.sleep` backoff
(`ReplyPipeline.kt` ~line 141). Default `timeoutMs` is 20 s per attempt, so one incoming
message can pin the main thread for **60+ seconds**. In AUTO mode the configured reply
delay is also `Thread.sleep` on Main (`Sleeper.REAL`), and `_simulateTyping` sleeps via
`SystemClock.sleep` inside `insertText`.

`ReplyEngine`'s own KDoc states the contract this violates:

> *Threading contract: [handleSnapshot] and [handleAction] may block (AI call, reply
> delay). Call them from a background dispatcher, never from the UI thread or from an
> accessibility event callback.*

**Impact:** the accessibility service and the activity share one process main thread:
- the app/overlay UI freezes while a reply is generated;
- ANR ("Live AI Reply isn't responding") after ~5 s if the app's UI is visible, or if
  broadcast/service lifecycle callbacks queue up;
- accessibility event callbacks stall — the system can unbind a stalled service;
- STOP can be ignored for the full duration of the in-flight call/blocking sleeps.

**Fix sketch:** move the whole call off the main dispatcher, e.g. inside `processNow()`:
```kotlin
scope.launch(Dispatchers.Default) {
    ...buildSnapshot / refresh / engine.handleSnapshot...
}
```
(`automation.refresh()` and node actions can stay on this background thread as well —
`AccessibilityNodeInfo.performAction` is thread-safe; only the small `NodeView` caches
must be kept consistent.) Long-term, convert the transport to `OkHttp.enqueue` or a
suspend pipeline.

---

## 2. HIGH — Overlay action buttons run blocking work on the main thread

**Where:** `app/src/main/java/com/liveaireply/app/overlay/OverlayContent.kt`
(`private fun act(...)` at the bottom), `ReplyEngine.handleAction()`.

Every overlay button executes synchronously in the Compose click handler (main thread):

- **REGENERATE** → `pipeline.generate(...)` — a blocking multi-attempt network call
  with `Thread.sleep` backoff, on Main.
- **SEND** with *Simulate typing* on → `typeProgressively()` loops `SystemClock.sleep`
  on Main.
- **PAUSE_CHAT** → `DataStoreConversationPauses.mutate(...)` → `runBlocking { ... }`
  around a DataStore write, on Main (disk I/O on the UI thread).

**Impact:** overlay taps freeze the floating window and the host app; REGENERATE freezes
for the whole model round trip and can ANR the app.

**Fix sketch:** dispatch `handleAction` to the engine's scope (e.g.
`AssistantRuntime`-owned `CoroutineScope(SupervisorJob() + Dispatchers.Default)`) and
remove the `runBlocking` in `AssistantService.DataStoreConversationPauses.mutate`
(the caller already runs in a coroutine-capable context).

---

## 3. MEDIUM — The accessibility tree is mapped twice per event burst, once on Main

**Where:** `LiveReplyAccessibilityService.processNow()` +
`AccessibilityAutomationController.refresh()`.

Per burst of chat events:
1. `buildSnapshot()` maps the entire window tree on `Dispatchers.Default`
   (`NodeMapper.map(rootInfo, packageName)`) to extract bubbles/composer/title.
2. Back on Main, `automation.refresh()` maps **the same root again** just to locate the
   composer and send target.

On deep trees (Instagram is notorious) each map is expensive; doing it twice and doing
one of the passes on Main compounds defect #1.

**Fix sketch:** reuse the mapped `NodeView` from `buildSnapshot()` and pass it into the
automation cache instead of re-mapping; run the refresh on the background dispatcher.

---

## 4. HIGH — `startForegroundService` from background is not guarded (crash risk)

**Where:** `LiveReplyAccessibilityService.onServiceConnected()` and its settings
collector: `ContextCompat.startForegroundService(...)` with **no `try/catch`**, from a
service that Android re-binds while the app is fully in the background.

Sequence: user enables monitoring → later swipes the app away or reboots the phone →
monitoring stays persisted `true` → the system restarts the process to re-bind the
accessibility service **in the background** → `onServiceConnected` / the collector calls
`startForegroundService` → on Android 12+ (API 31+) the system can throw
`ForegroundServiceStartNotAllowedException`. Per the Android docs, background FGS starts
are only allowed under specific exemptions; having a bound accessibility service is not
one of them, and on Android 15 even the `SYSTEM_ALERT_WINDOW` exemption was narrowed to
require an *existing visible overlay window*, which this flow does not guarantee.
The exception surfaces inside a coroutine on Main (uncaught → process crash), and the
accessibility service reconnects automatically — i.e. a **crash-and-rebind loop** until
the user disables the service or clears the app state.

**Fix sketch:** wrap the call (`runCatching` / catch `IllegalStateException`) and degrade
to a posted notification telling the user to open the app to resume monitoring; only the
visible app (or a real exemption state) should (re)start the foreground service.
**Needs device verification** (reboot / swiping from recents; Android 12/14/15).

---

## 5. HIGH — Monitoring service never notices the accessibility service is gone

**Where:** `LiveReplyAccessibilityService.onDestroy()/onUnbind()` clears
`AssistantRuntime.automation`; `AssistantService.mayStart()` requires
`automation != null` — but `mayStart` is only evaluated on service intents and settings
emissions, so revoking the service in Android Settings leaves `AssistantService` (and its
"Monitoring enabled by you" notification) running forever with no possible input.

**Impact:** the app and notification claim monitoring is active while nothing can ever
be read — a classic "why is it not working?" state, and the only recovery is manual.

**Fix sketch:** in `onUnbind()`/`onDestroy()`, send `AssistantService` a stop/pause intent
(or mutate a watched state flag) so the foreground assistant tears down the moment the
accessibility connection is lost.

---

## 6. MEDIUM — Persistent notification never updates; Pause has no Resume

**Where:** `EngineHostAndroid.onStatusChanged()` publishes overlay state but never calls
`MonitoringNotifier.update(...)`; `MonitoringNotifier.build()` adds only **Pause** and
**Stop** actions. `AssistantService.ACTION_RESUME` exists in code but is never sent by
any UI.

**Impact:** notification content is stale the moment the engine starts thinking; after
Pause the user cannot resume from the shade and may conclude monitoring died.

**Fix sketch:** have the host callback forward status text to the notifier (rate-limited)
and add a Resume action that swaps in when the engine state is PAUSED.

---

## 7. LOW — Conversation identity flips when the chat title appears/disappears

**Where:** `ConversationSnapshot.conversationId` = `packageName + screenLabel` (falling
back to `activityName`). When the title node becomes readable (or drops out) between
snapshots, the ID changes, the detector takes the *conversation changed* branch,
re-seeds memory and **ignores the exact message** that arrived during the transition.

**Impact:** sporadically, the first reply in a chat is silently skipped. Self-healing,
but looks like flakiness.

**Fix sketch:** derive the conversation id from stable parts (package + activity, or a
first-bubble signature) instead of the volatile title; keep the title for prompts only.

---

## 8. LOW — "User is typing" inferred from any window event

**Where:** `LiveReplyAccessibilityService.userIsTyping()`:
`now - lastEventAtMs < 1500 && composer not blank` — and `lastEventAtMs` is updated on
`TYPE_WINDOW_STATE_CHANGED` too (e.g. keyboard opening, dialogs).

**Impact:** AUTO sends can be held back as "You are typing" after events that were not
typing. Fails safe (suggestion instead of send), but is confusing.

**Fix sketch:** update `lastEventAtMs` only for `TYPE_VIEW_TEXT_CHANGED` events whose
node is the identified composer.

---

## 9. LOW — Expanded overlay card is not scrollable as a whole

**Where:** `OverlayContent.ExpandedCard` — the `Column` has no `verticalScroll`; only the
reply text block scrolls.

**Impact:** a long suggestion on a small screen or in landscape pushes Send/Copy/STOP off
the bottom of the card with no way to reach them — the emergency control should never be
unreachable.

**Fix sketch:** make the whole card column scrollable (keep the inner reply scroll or
drop it and rely on the outer scroll).

---

## 10. EXPECTED-BY-DESIGN — No model or API key ships with the app

**Where:** `AppSettings.DEFAULT`: `primaryModel = ""`, and the encrypted credential store
starts empty. Setup wizard step 10 ("Finish setup") only requires the disclosure
checkbox — not a key or model.

**Impact:** a user who finishes setup without entering an endpoint/key/model sees the app
"do nothing": every detected message ends in `No model configured` /
`No API key is configured` (visible only in Logs / overlay error state).
This is intentional (no secrets in the repo), but it is the #1 support question.

**Fix sketch (UX):** make the Home status card surface "AI not configured" prominently
and link straight to AI settings; optionally block "Monitoring" until a model and key
are set; keep the logs/overlay error the same.

---

## 11. EXPECTED-BY-DESIGN, BUT LOOKS BROKEN — Installing CI artifacts

Two artifact issues explain most "**App not installed**" reports:

1. **`app-release-unsigned.apk` cannot be installed at all.** Without the
   `ANDROID_KEYSTORE_*` secrets, CI intentionally ships the release variant unsigned; no
   release-signing secrets are configured in this repository. It must be signed first
   (`apksigner sign` / configure the secrets) — verified in the audit of run
   `37224652485`.
2. **The debug APK changes signing identity every run.** CI generates a fresh debug key
   when no `ANDROID_DEBUG_KEYSTORE_BASE64` / release secrets exist, so a newer debug APK
   cannot update an older one: Android reports a signature mismatch and the install
   fails. The fix is to uninstall first, or set `ANDROID_DEBUG_KEYSTORE_BASE64` (or the
   release secrets) so the identity is stable — this is documented in
   `signing/README.md` but is easy to miss.

Additionally, sideloading an app with AccessibilityService + overlay + MediaProjection
can trigger Play Protect warnings (documented); that is expected behavior, not a defect.

---

## 12. EXPECTED-BY-DESIGN — First run does nothing until fully enabled

By policy/by design, the assistant is inert until **all** of these are true:

1. first-run disclosure accepted;
2. **Live AI Reply** enabled in Android Settings → Accessibility;
3. Monitoring switched on in the app;
4. the open chat app is in the enabled list (defaults: WhatsApp, Telegram, Instagram
   only); and
5. provider + API key + model configured (see #10).

A green "Service: Running" does not imply replies — the Home status card should make the
missing step obvious (see #10 fix).

---

## 13. EXTERNAL — Chat-app UI structures drift

The WhatsApp/Telegram/Instagram adapters key off internal view IDs
(`com.whatsapp:id/entry`, `:id/send`, …). Chat apps rename these between releases; when a
hint stops matching, the app silently degrades to suggestion-only (no typing/sending),
and can eventually fail to detect bubbles at all. This maintenance tax is acknowledged in
`AUDIT_REPORT.md` and the README ("Chat app UI structures change"); it is listed here
because it *is* a recurring real-world "not working" cause. The in-app adapter-override
settings are the intended remedy.

---

## Fix order recommended

| Priority | Item |
|---|---|
| P0 | #1 — background dispatcher for `handleSnapshot` (kills freezes/ANRs) |
| P0 | #2 — background `handleAction` for overlay buttons |
| P0 | #4 — guard `startForegroundService` (crash loop after reboot/swipe-away) |
| P1 | #5 — stop the FGS when accessibility is revoked |
| P1 | #10 — surface "AI not configured" before allowing Monitoring |
| P1 | #3 — single tree map per event burst |
| P2 | #6 — live notification text + Resume action; #9 — scrollable overlay card |
| P2 | #7 — stable conversation id; #8 — typed-only "user is typing" |
| Docs/CI | #11 — prefer stable debug key via `ANDROID_DEBUG_KEYSTORE_BASE64`; #12/#13 — already documented |

## Verification status of this report

- Items 1–9: verified by reading the source on this branch; the threading findings are
  also contradicted by `ReplyEngine`'s own documented contract, which makes them
  unambiguous.
- Item 4: crash depends on Android version and prior app state — **needs device
  verification** (reboot with monitoring on; swipe from recents; Android 12/14/15).
- No runtime/device test was executed in this sandbox (no Android SDK, and gradle.org is
  unreachable from it); see `BUILD_NOTES.md` for the local vs CI evidence split.
