## Why

Today both nospeak voice/video call backends tear the call down immediately when the WebRTC peer connection's `iceConnectionState` becomes `failed` *or* `disconnected`. The `disconnected` case is especially harmful: per the WebRTC spec it is a transient state (Wi-Fi handoff, cellular handover, brief packet loss) and usually self-recovers within a few seconds. The current behavior turns every brief network blip into a permanently dropped call.

Neither backend attempts an **ICE restart** before giving up. A single `createOffer({iceRestart: true})` round-trip — sent over the existing NIP-AC kind-25055 (Call Renegotiate) wire — recovers the vast majority of post-network-change failures without any user-visible interruption. Reference implementation: Amethyst's `WebRtcCallSession` (PR vitorpamplona/amethyst#2068) attempts exactly one ICE restart on `FAILED` and only declares the call dead if that restart also fails.

## What Changes

- **Treat `iceConnectionState === 'disconnected'` as transient.** Stop immediately ending the call on this state. Instead, surface a "Reconnecting…" UI signal and arm a grace-window timer (~15 s). If the connection recovers (→ `connected`/`completed`) before the timer fires, clear the UI signal and continue. If the timer fires without recovery AND the state has not yet escalated to `failed`, proactively trigger the ICE-restart path described below.
- **Attempt one ICE restart on `iceConnectionState === 'failed'`.** Create an offer with `iceRestart: true`, set the local description, and publish it as a kind-25055 Call Renegotiate. The peer's existing kind-25055 handler accepts the SDP, creates an answer, and the connection re-gathers candidates. If the connection reaches `connected`/`completed` after the restart, clear the attempt flag and continue. If `failed` fires again before recovery (or the in-flight renegotiation answer times out), tear down with the existing `ice-failed` end reason.
- **Determine "who restarts" with the lex-pubkey rule** already used for kind-25055 glare resolution: the side whose lowercase-hex pubkey sorts lower initiates the restart; the higher-pubkey side just waits for the incoming kind-25055. Same rule for group-call edges.
- **Group-call ICE restart is OUT OF SCOPE for this change.** kind-25055 Call Renegotiate is currently a 1-on-1-only wire path (see AGENTS.md voice-calling section and the existing `sendRenegotiate` sender signature which deliberately omits `NipAcGroupSendContext`). Extending kind-25055 to carry group tags requires a coordinated wire-format change (web sender, Android sender, gift-wrap helper, receiver dispatchers that currently drop group-25055, wire-parity fixture). Group ICE restart will land alongside the future group voice→video upgrade work in a follow-up change. Per-edge group ICE failures continue to use the existing immediate-teardown behavior in `handleGroupIceFailure`.
- **Coordinate with existing kind-25055 renegotiations** (voice→video upgrade). If `renegotiationState !== 'idle'` when an ICE-restart trigger fires, defer the restart until the in-flight renegotiation resolves; if the renegotiation answer fails, run the restart immediately afterward. The existing `renegotiationState` machine is reused; the restart path participates in the same glare resolution.
- **Surface "Reconnecting…" in the call overlays** (`ActiveCallOverlay.svelte`, `GroupActiveCallOverlay.svelte`, `ActiveCallActivity.java`) while in `disconnected` OR during an active ICE-restart attempt. Clears automatically on recovery or terminal teardown.
- **`ice-failed` remains the terminal end reason.** The diagnostic `VoiceCallIceFailed` dump (from `fix-android-ice-servers-from-runtime-config`) continues to fire on the final teardown — never on the intermediate restart attempt.
- **Proactive `triggerIceRestart()` on detected network changes is deferred** to a follow-up change. The reactive path in this change handles the same cases via libwebrtc's own state machine.
- **BREAKING (spec only, not wire):** the current normative requirement in `openspec/specs/voice-calling/spec.md` that the call "SHALL also transition to `ended` with reason `ice-failed` if the ICE connection state becomes `failed` or `disconnected`" is weakened — terminal teardown now only follows a failed restart attempt, never the initial `disconnected` transition. No wire-format change; the kind-25055 inner-event shape is reused as-is.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- **voice-calling** — Adds normative requirements for one-shot ICE restart on `failed` (1-on-1 calls only), transient-disconnected handling, lex-pubkey-driven restart election, restart/upgrade coordination via the existing `renegotiationState` machine, and the "Reconnecting…" UI signal. Group-call ICE restart is explicitly deferred to a follow-up change; per-edge group ICE failures continue to terminate that edge immediately per the existing behavior.

## Impact

- **Affected files (web/PWA):**
  - `src/lib/core/voiceCall/VoiceCallService.ts` — 1-on-1 ICE-state handler (`oniceconnectionstatechange` around line 821), `handleIceFailure`, `handleRenegotiate` coordination. Group paths (`handleGroupIceFailure`, group `oniceconnectionstatechange`) are NOT modified in this change.
  - `src/lib/core/voiceCall/types.ts` and `src/lib/core/voiceCall/voiceCall.ts` — add `RenegotiationTrigger` type (`'media' | 'ice-restart' | null`); extend `voiceCallState` with `connectionQuality: 'good' | 'reconnecting'` flag and `renegotiationTrigger` field. The group `ParticipantState` also gets a `connectionQuality` field for type completeness and future-proofing, defaulting to `'good'`, but no group code path mutates it in this change.
  - `src/lib/core/voiceCall/constants.ts` — add `ICE_DISCONNECTED_GRACE_MS = 15_000`, reuse `ICE_CONNECTION_TIMEOUT_MS` for the post-restart watchdog.
  - `src/lib/components/ActiveCallOverlay.svelte` — render "Reconnecting…" pill bound to `connectionQuality`. `GroupActiveCallOverlay.svelte` is NOT modified in this change.
  - `src/lib/i18n/en.json` and other locale files — add `voiceCall.reconnecting` string.

- **Affected files (Android native):**
  - `android/app/src/main/java/com/nospeak/app/NativeVoiceCallManager.java` — `PCObserver.onIceConnectionChange` (around line 2292), `finishCall("ice-failed", ...)` paths, glare-aware restart election, watchdog timer for the grace window, plumbing for kind-25055 restart sender (the existing `bridge.sendVoiceCallRenegotiate` path is reused).
  - `android/app/src/main/java/com/nospeak/app/AndroidVoiceCallPlugin.java` — add a `connectionQualityChanged` plugin event (or extend the existing event surface) so the JS side can mirror the state into `voiceCallState`.
  - `android/app/src/main/java/com/nospeak/app/ActiveCallActivity.java` — render "Reconnecting…" while connection quality is degraded; existing `ice-failed` end-reason copy unchanged.

- **Tests:**
  - New unit tests in `src/lib/core/voiceCall/VoiceCallService.test.ts` and `GroupVoiceCallStateMachine.test.ts` covering: transient disconnected → recovery; transient disconnected → grace timeout → restart; failed → restart succeeds; failed → restart fails → terminal teardown; lex-pubkey election (initiator-loses, initiator-wins); coordination with an in-flight voice→video upgrade.
  - New Java unit tests (pure logic, no Android framework) for the restart-election helper and the renegotiation-state coordinator on the Android side.
  - Wire-parity fixture in `tests/fixtures/nip-ac-wire/inner-events.json` for an ICE-restart kind-25055 (verifies byte-equivalence between JS and Java senders, even though the wire shape is identical to a media-change renegotiate).

- **Spec change:** `openspec/specs/voice-calling/spec.md` — modify the existing call-timeout requirement (currently lines 148–164) to introduce restart semantics and weaken the `disconnected` terminal clause.

- **No wire-format change.** No new NIP-AC inner kinds; the kind-25055 envelope, tags, and gift-wrap path are reused as-is. The `25055` ICE-restart SDP carries `a=ice-options:trickle` and a fresh `a=ice-ufrag`/`a=ice-pwd` pair per the W3C WebRTC spec for `createOffer({iceRestart: true})` — no application-layer signal that the renegotiate is for ICE restart vs. media change.

- **Backward compatibility.** A peer running an older nospeak (or any NIP-AC-compliant client) receives the restart kind-25055 indistinguishably from a media-change kind-25055; the existing handler does the right thing (setRemoteDescription → createAnswer). If the peer's renegotiate handler is buggy and the answer never arrives, the watchdog times out and we tear down with `ice-failed` — same observable outcome as today, just with an extra 5–10 seconds of attempt time.

- **Proactive `triggerIceRestart()` on network change** is deliberately out of scope. A follow-up change will wire `navigator.connection.onchange` (web) and `ConnectivityManager.NetworkCallback` (Android) to call into the restart path.
