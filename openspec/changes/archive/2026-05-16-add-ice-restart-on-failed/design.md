## Context

Both nospeak voice/video call backends (`VoiceCallServiceWeb` for PWA, `NativeVoiceCallManager` for Android) currently treat `iceConnectionState === 'failed'` AND `iceConnectionState === 'disconnected'` as terminal: the call is torn down immediately and the user sees `ice-failed`. Per the W3C WebRTC spec, `disconnected` is explicitly transient — it represents a momentary connectivity loss (Wi-Fi handoff, cellular handover, brief packet loss) and libwebrtc usually self-recovers to `connected` within a few seconds. Treating it as terminal turns every Wi-Fi-to-cellular handover into a dropped call.

Neither backend attempts an ICE restart before giving up. The reference implementation (Amethyst `WebRtcCallSession`, PR vitorpamplona/amethyst#2068) attempts exactly one `createOffer({iceRestart: true})` on `FAILED` and tears down only if the restart also fails. That single round-trip recovers the vast majority of post-network-change failures without user-visible interruption.

nospeak already has all the wire-level scaffolding required:
- **NIP-AC kind-25055 Call Renegotiate** is implemented end-to-end on both backends (`handleRenegotiate` web, `handleRemoteRenegotiate` Android), used today for the voice→video upgrade flow.
- **Glare resolution by lex-pubkey** is implemented for kind-25055 (lower-pubkey side wins).
- A **`renegotiationState`** Svelte store (`'idle' | 'outgoing' | 'incoming' | 'glare'`) tracks in-flight kind-25055 transactions, mirrored from the Android side via the `renegotiationStateChanged` plugin event.
- A **`VoiceCallIceFailed` diagnostic dump** fires on terminal `ice-failed` teardown (web + Android), shipped in `fix-android-ice-servers-from-runtime-config`.

This change is therefore mostly a state-machine extension — the wire format, glare rule, and renegotiate handlers are reused as-is. No new NIP-AC inner kind, no wire fixture changes (beyond a parity smoke test).

## Goals / Non-Goals

**Goals:**

- Stop terminating calls on the first `iceConnectionState === 'disconnected'` transition. Treat it as transient and wait through a grace window.
- On `iceConnectionState === 'failed'` (or grace-window expiry while still `disconnected`), attempt exactly **one** ICE restart by sending a kind-25055 Call Renegotiate carrying an `iceRestart: true` SDP. If the restart succeeds (state returns to `connected`/`completed`), continue the call. If it fails (state returns to `failed`, or no `connected` within `ICE_CONNECTION_TIMEOUT_MS` of starting the restart, or the kind-25055 answer never arrives), tear down with the existing `ice-failed` end reason.
- Elect "who restarts" using the existing **lex-pubkey rule** so both sides cannot race each other.
- Coordinate cleanly with an in-flight voice→video upgrade (or any future kind-25055 use) so the two cannot collide and so each is correctly attributed in diagnostics.
- Surface a `'reconnecting'` connection-quality signal to the UI (`ActiveCallOverlay.svelte` and `ActiveCallActivity.java`) while in `disconnected` OR while a restart is in flight.
- Maintain wire-format byte-equivalence between web and Android NIP-AC senders (the kind-25055 envelope is unchanged; verify via the existing wire-parity fixture mechanism).

**Non-Goals:**

- **Group-call ICE restart.** kind-25055 Call Renegotiate is currently a 1-on-1-only wire path; extending it to carry group tags is a coordinated wire-format change (sender signatures, gift-wrap helper, receiver dispatchers that today drop group-25055, fixtures, AGENTS.md) that lives with the future group voice→video upgrade work. Per-edge group ICE failures continue to use the existing immediate-teardown behavior in `handleGroupIceFailure`.
- Proactive ICE restart on detected network change (`navigator.connection.onchange`, Android `ConnectivityManager.NetworkCallback`). Deferred to a follow-up change; the reactive path here recovers from the same scenarios via libwebrtc's own state machine.
- More than one restart attempt per call. A second failure indicates the network change is permanent; trying again wastes user time without changing the outcome.
- New NIP-AC inner kinds. The kind-25055 envelope is reused; the SDP itself signals ICE restart per W3C semantics (`a=ice-options:trickle` + fresh `a=ice-ufrag`/`a=ice-pwd`).
- Application-layer signaling that the kind-25055 is "for ICE restart" vs. "for media change". The receiver treats both identically (setRemoteDescription → createAnswer); the trigger is interesting for the sender's diagnostics only.
- Changes to ICE-server configuration (STUN/TURN). Out of scope; see `add-plain-turn-to-default-ice-servers` and `fix-android-ice-servers-from-runtime-config`.
- Audio/video quality adaptation during reconnection (bitrate downshift, etc.). Out of scope.

## Decisions

### 1. Treat `disconnected` as transient with a grace window

On `iceConnectionState === 'disconnected'`, do not tear down. Instead:

1. Set `connectionQuality = 'reconnecting'` in the call state store (1-on-1) or set the per-participant flag (group).
2. Arm a `disconnectedGraceTimer` of **15 seconds** (`ICE_DISCONNECTED_GRACE_MS`).
3. If `iceConnectionState` returns to `connected`/`completed` before the timer fires, cancel the timer and clear `connectionQuality` back to `'good'`.
4. If `iceConnectionState` transitions to `failed` before the timer fires, cancel the timer and immediately proceed to the ICE-restart election (Decision 3).
5. If the timer fires while still `disconnected`, treat it as the moral equivalent of `failed` and proceed to the ICE-restart election.

**Rationale.** libwebrtc usually escalates to `failed` within ~10–15 s, but not always — some implementations sit in `disconnected` indefinitely. The grace window covers both common cases (quick blip recovers; slow escalation gets nudged into restart) without immediately abandoning calls on a 500 ms packet loss spike.

**Alternatives considered:**

- *No grace window — wait only for `failed`* (Amethyst's actual behavior). Rejected: relies on libwebrtc escalating, which it does inconsistently. The grace window is cheap and bounded.
- *Shorter grace (5 s)*. Rejected: too aggressive — common Wi-Fi roams take 8–12 s.
- *Longer grace (30 s)*. Rejected: indistinguishable from "the connection is gone" from the user's perspective. 15 s is the sweet spot.

### 2. One restart attempt total, no more

After exactly one ICE restart attempt the call is either recovered (→ continue) or terminated (→ `ice-failed`). The `iceRestartAttempted` flag is set when the restart is fired and reset only on the next `connected`/`completed` transition.

**Rationale.** A second restart after the first has failed almost always indicates a permanent network change (peer went offline, NAT mapping died and won't come back) or a TURN issue. Iterating wastes user time and keeps the UI in a "Reconnecting…" limbo. One try then terminal is the same policy Amethyst ships.

**Alternatives considered:**

- *Two attempts with exponential backoff*. Rejected: low recovery yield, high UI confusion. If the first attempt didn't work, hang up and let the user redial.
- *Unbounded restarts until user hangup*. Rejected: pathological — a call could "ring" reconnecting forever.

### 3. Election: lex-pubkey rule (lower pubkey restarts)

When both ends detect `failed`/`disconnected` simultaneously, only the side whose lowercase-hex pubkey sorts **lower** initiates the restart. The other side detects the same condition but waits for the incoming kind-25055 instead.

**Implementation:** add a pure helper (web: `shouldInitiateIceRestart(localHex, peerHex): boolean` in a new module under `src/lib/core/voiceCall/`; Java: `IceRestartElection.shouldInitiate(localHex, peerHex)` under `com.nospeak.app`) returning `localHex < peerHex`. Same rule applied per-edge for group calls.

**Rationale.** This is the exact rule already used for kind-25055 glare resolution, so it's well understood and the receiver-side handler already does the right thing if both sides happen to send (the loser rolls back). Reusing the rule keeps the cognitive load low and the test surface small.

**Alternatives considered:**

- *Initiator-only restarts.* Rejected: doesn't generalize to group-call edges (the "initiator" of a group call is one peer, but each mesh edge has its own offerer determined by — yes — the lex-pubkey rule). Using a different rule for restart would invite drift bugs.
- *Both sides restart, accept the glare cost.* Rejected: doubles the wire traffic on every failure for no benefit; the rollback path is also more complex than just-don't-send.

### 4. Coordinate with in-flight kind-25055 via the existing `renegotiationState` machine

When an ICE-restart trigger fires:

- If `renegotiationState === 'idle'`: proceed immediately. Set `renegotiationState = 'outgoing'` (web) / equivalent (Android) and tag the in-flight transaction internally as `'ice-restart'` for diagnostics only.
- If `renegotiationState !== 'idle'`: do **not** fire the restart now. Set a `pendingIceRestart = true` flag and let the in-flight renegotiation complete. When `renegotiationState` returns to `'idle'`:
  - If the connection has recovered to `connected`/`completed` (e.g., the in-flight media renegotiate happened to re-gather ICE candidates and luck saved us), clear `pendingIceRestart` and do nothing.
  - Otherwise, fire the restart now (subject to the one-attempt cap and the election rule still applying).

The Android plugin event `renegotiationStateChanged` already mirrors the Android state into the Svelte store; no new event surface is required.

**Internal trigger label.** The existing `renegotiationState` field stays as the public state (`'idle' | 'outgoing' | 'incoming' | 'glare'`); a private sibling `renegotiationTrigger: 'media' | 'ice-restart' | null` is added so log lines and stats can distinguish the two. The wire is unchanged.

**Rationale.** Two kind-25055 transactions cannot overlap on the same PeerConnection (libwebrtc would error on setLocalDescription); we already have the serializer, we just need to teach it about a second source of triggers.

**Alternatives considered:**

- *Cancel the in-flight upgrade and restart immediately.* Rejected: voice→video upgrades are user-initiated; canceling silently is worse UX than waiting 1–2 seconds for the upgrade to settle.
- *Run both transactions concurrently.* Rejected: WebRTC forbids it.

### 5. Watchdog timer on the in-flight restart

After firing the restart (sending the kind-25055), arm a watchdog of **`ICE_CONNECTION_TIMEOUT_MS` (30 s, the existing constant)** that:

- Cancels itself if `iceConnectionState` reaches `connected`/`completed`.
- Cancels itself if `iceConnectionState` reaches `failed` (the existing failure path runs, which because `iceRestartAttempted === true` now goes terminal).
- On expiry, calls the existing `handleIceFailure` / `finishCall("ice-failed", ...)` path with the same terminal `ice-failed` end reason.

**Rationale.** Covers the case where the peer's renegotiate handler is buggy or the answer event never makes it through the relay — the call terminates within a bounded time. 30 s matches the existing initial-ICE establishment window so there is only one "what's the watchdog" timing knob.

**Alternatives considered:**

- *Shorter watchdog (10 s).* Rejected: ICE re-gathering can legitimately take 10+ s through TURN over TCP on a high-latency connection.
- *No watchdog (rely on libwebrtc states).* Rejected: if the kind-25055 answer is dropped at the relay, libwebrtc will sit forever — no PC state transition is guaranteed.

### 6. Group-call ICE restart deferred to a follow-up change

Group-call edges continue to use the existing `handleGroupIceFailure` immediate-teardown behavior. The current `sendRenegotiate` JS sender deliberately omits `NipAcGroupSendContext` and the receivers (web `handleNipAcEvent` and Java `NativeBackgroundMessagingService`) explicitly drop inbound kind-25055 with a `group-call-id` tag (see `VoiceCallService.ts` comment "kind-25055 with group-call-id is not supported in v1; dropping"). Reusing kind-25055 for group ICE restart requires:

- Extending `sendRenegotiate` to accept `opts?: { group?: NipAcGroupSendContext }` (TypeScript + Java sides).
- Extending `Messaging.ts` and `nipAcGiftWrap.ts` `buildGroupExtraTags` to emit group tags on kind-25055.
- Extending `NativeBackgroundMessagingService.sendVoiceCallRenegotiate` to accept group context and emit byte-equivalent group tags.
- Removing the group-25055 drop in receiver dispatchers AND adding the per-edge dispatch logic.
- Adding a wire-parity fixture entry for group-25055 covered by both `wireParity.test.ts` and `NativeNipAcSenderTest.java`.
- Updating AGENTS.md to remove "kind 25055 is voice-only and not used for groups".

This is a non-trivial wire-format expansion that should land alongside the future group voice→video upgrade (which has the same needs). For this change, group-call ICE failures retain the existing single-edge immediate-teardown behavior; the spec language for group calls is unchanged.

**`ParticipantState.connectionQuality`** is added to the type for forward compatibility (so the future group ICE-restart change can mutate it without a follow-up type change) and defaults to `'good'`, but no group code path mutates it in this change. The group call overlay is not modified.

**Alternatives considered:**

- *Include group ICE restart from day one.* Rejected after build-mode investigation: the wire-format expansion is significant (2 senders × {web, Android} × wire-parity fixture × receiver dispatch removal × AGENTS.md update) and largely independent of the 1-on-1 logic. Keeping this change focused on the most common loss case (1-on-1 Wi-Fi handoff) ships the user-visible benefit faster.

### 7. UI: a single `connectionQuality` flag

Add `connectionQuality: 'good' | 'reconnecting'` to `voiceCallState` (1-on-1). Also add a sibling per-participant field on `groupVoiceCallState.participants[hex]` for forward compatibility, defaulting to `'good'`; nothing mutates it in this change. Drives a "Reconnecting…" pill in `ActiveCallOverlay.svelte` and `ActiveCallActivity.java`. `GroupActiveCallOverlay.svelte` is not modified.

`connectionQuality` is set to `'reconnecting'` when:
- `iceConnectionState === 'disconnected'` (and we're still inside the grace window), OR
- An ICE restart is in flight (after sending kind-25055, before connected/completed).

Cleared to `'good'` when:
- `iceConnectionState` returns to `connected`/`completed`.
- The call ends (any reason) — the flag is reset along with the rest of the call state.

**Rationale.** A single boolean signal is enough — the UI doesn't need to know *why* we're reconnecting (network blip vs. active restart). The pill copy is identical in both cases.

**Alternatives considered:**

- *Three states (`'good' | 'degraded' | 'reconnecting'`).* Rejected: no clear UX benefit and a third state means a third copy string to translate.
- *No UI signal.* Rejected: silent reconnects feel like the app is hanging.

### 8. Testing strategy

**Web (Vitest):** the existing `VoiceCallService.test.ts` mocks `RTCPeerConnection` with a `pc.oniceconnectionstatechange` hook. Add cases (1-on-1 only):

- transient disconnected → connected (no terminal, `connectionQuality` cycles `good` → `reconnecting` → `good`)
- transient disconnected → grace timeout → restart fired (election: local lower) → connected
- transient disconnected → grace timeout → restart fired (election: local higher) → no restart sent, wait for incoming kind-25055 → connected
- failed → restart fired → connected
- failed → restart fired → failed again → terminal with `ice-failed`
- failed → restart deferred for in-flight voice→video upgrade → upgrade completes → restart fires
- failed → restart watchdog expires (no peer answer) → terminal with `ice-failed`

Group `oniceconnectionstatechange` behavior is unchanged in this change; existing `GroupVoiceCallStateMachine.test.ts` cases continue to pass without modification.

**Java (pure-logic JUnit):** new test class `IceRestartElectionTest` covering the lex-pubkey helper (equal, lower, higher, uppercase normalization edge cases).

**Wire parity:** add an ICE-restart kind-25055 fixture entry to `tests/fixtures/nip-ac-wire/inner-events.json` and assert byte-equivalence between `Messaging.ts` sender and `NativeBackgroundMessagingService.sendVoiceCall*` Java sender. Confirms that even though the trigger is internal, both backends serialize the wrapping the same way.

**Manual smoke tests:** documented in tasks.md — Wi-Fi → cellular handover during an active call (recovery expected); kill the peer's app during `connecting` (terminal `ice-failed` expected, with one visible restart attempt in logs); kill the peer's app during `active` (terminal `ice-failed` expected, with one visible restart attempt).

### 9. Diagnostics

- The existing `VoiceCallIceFailed` log dump continues to fire on the final terminal teardown only (never on the intermediate restart attempt). The diagnostic helper (`iceFailedDiagnostic.ts` web; equivalent in `NativeVoiceCallManager` Android) takes no changes.
- New structured log lines `[VoiceCallIceRestart]` are emitted at: restart-fired (with election result and trigger reason `'disconnected-grace-timeout'` vs `'failed'`), restart-succeeded (with elapsed-ms from fire to `connected`), restart-failed (with elapsed-ms and failure reason `'failed-state'` vs `'watchdog-expired'`). Web: `console.info`. Android: `Log.i(TAG, ...)`.
- Log lines are PII-clean (no SDP, no candidate addresses, just state machine transitions and timings).

## Risks / Trade-offs

- **[Risk]** Peer client misbehaves and never sends an answer to the restart kind-25055 → call hangs in "Reconnecting…" for 30 s before terminal. **→ Mitigation:** the 30 s watchdog (Decision 5) bounds this; user can hang up at any time; total worst-case is the same as today's existing 30 s ICE-establishment timeout.

- **[Risk]** Multiple network blips in a single call → after the first restart fires and succeeds, the second blip would not get another restart (one-attempt cap, Decision 2). **→ Mitigation:** the cap resets to false on successful `connected`/`completed`, so each "good period" gets one restart attempt. We're capping per-failure, not per-call. (Spec language reflects this.)

- **[Risk]** Lex-pubkey election fails if both sides have the same pubkey (self-call). **→ Mitigation:** self-calls are already forbidden by the call-initiation logic; the election helper returns `false` for the `localHex === peerHex` case and we log a warning.

- **[Risk]** The grace window (15 s) is long enough that users perceive "Reconnecting…" as broken before recovery happens. **→ Mitigation:** the UI pill is unobtrusive and the average path is much shorter (most blips recover in 2–4 s); the watchdog enforces the upper bound. Make the constant tunable via runtime config if real-world data warrants it.

- **[Trade-off]** Group calls do not benefit from ICE restart in this change. **→ Decision:** acceptable for v1 — group calls are a small fraction of usage and the wire-format extension to support group kind-25055 is best amortized against the future group voice→video upgrade work.

- **[Trade-off]** Reusing kind-25055 for two purposes (media change AND ICE restart) means the wire cannot distinguish them. A future spec change wanting per-purpose telemetry on the relay side would need a new tag. **→ Decision:** accept the trade-off — relay-side telemetry isn't a current requirement, and the internal `renegotiationTrigger` field gives us all the client-side diagnostics we need.

- **[Trade-off]** No proactive restart on network change means we still pay the ~5–15 s for libwebrtc to detect the failure. **→ Decision:** acceptable for v1; follow-up change adds the proactive path.

## Migration Plan

This is an additive behavior change at the spec level; no data migration required.

**Rollout sequence:**

1. Ship the spec change and implementation behind the existing single-binary deploy (no feature flag — the behavior change is monotonic improvement and the failure mode is the same `ice-failed` end reason).
2. The first build that contains this change interoperates correctly with older clients: an older peer receiving an ICE-restart kind-25055 processes it as a normal renegotiate (setRemoteDescription → createAnswer); a newer client receiving no restart from an older peer (because the older peer terminated immediately on `disconnected`) sees `iceConnectionState` go to `failed`, fires its own restart, gets no answer (because the older peer already torn down), watchdog expires, terminates with `ice-failed`. Worst case: identical user-visible outcome to today, just 30 s slower to give up. Best case: both peers updated → calls survive network handovers.
3. Monitor `[VoiceCallIceRestart]` log volume and `ice-failed` rate post-rollout. Expectation: `ice-failed` rate drops; restart-success rate ≥ 50% indicates the feature is working.

**Rollback:** revert the change. No state or wire artifacts persist; older clients have always handled kind-25055 from older codebases.

## Open Questions

- **Q: Should the `disconnectedGraceTimer` be tunable via runtime config?** Recommendation: ship hardcoded at 15 s for v1; add a runtime-config knob only if production data shows a need. (Defer to follow-up.)
- **Q: Should we emit a chat-history pill when a call recovers via ICE restart, separate from the existing `active`/`failed` pills?** Recommendation: no for v1 — the recovery is invisible from the user's perspective and a new pill would clutter the chat. Revisit if users report confusion.
- **Q: When the future group-call ICE restart lands, will the lex-pubkey election rule extend cleanly?** Yes — each mesh edge is a pair, and the same `shouldInitiateIceRestart(localHex, peerHex)` helper applies unchanged with the per-edge `peerHex`. The per-edge `connectionQuality` field added in this change anticipates that work.
