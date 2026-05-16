## MODIFIED Requirements

### Requirement: Call Timeouts

The system SHALL apply a 60-second timeout to outgoing 1-on-1 call offers; if no `answer` arrives within this window the call SHALL transition to `ended` with reason `timeout`. The system SHALL apply a 30-second timeout to ICE connection establishment after answer exchange; if the peer connection does not reach `connected`/`completed` within this window the call SHALL transition to `ended` with reason `ice-failed`. This requirement governs **1-on-1** call timeouts; group calls use the separate `Per-Edge Timeouts` requirement, which is unchanged by this change.

For a 1-on-1 call whose status is `connecting` or `active`, the system SHALL NOT transition to `ended` solely on the first observation of `iceConnectionState === 'disconnected'`. Instead, on that transition the system SHALL set the call's `connectionQuality` to `'reconnecting'` and arm a disconnected-grace timer of 15 seconds (`ICE_DISCONNECTED_GRACE_MS`). If `iceConnectionState` returns to `connected` or `completed` before the timer fires, the system SHALL cancel the timer and restore `connectionQuality` to `'good'`. If `iceConnectionState` transitions to `failed` before the timer fires, the system SHALL cancel the timer and proceed to the ICE-restart election described in the `ICE Restart on Failure` requirement. If the timer fires while still `disconnected`, the system SHALL proceed to the ICE-restart election as if `failed` had been observed.

For a 1-on-1 call whose status is `outgoing-ringing` or `incoming-ringing` (i.e., before answer exchange has completed and ICE establishment has begun in earnest), the system SHALL continue to transition to `ended` with reason `ice-failed` immediately on `iceConnectionState === 'failed'` per the existing pre-setup failure behavior; the grace-window and ICE-restart machinery do NOT apply to pre-setup failures.

The system SHALL transition the 1-on-1 call to `ended` with reason `ice-failed` only after the one-shot ICE restart attempt itself fails (per the `ICE Restart on Failure` requirement) OR when an ICE restart is not attempted because the election rule assigns the restart to the peer and no recovery occurs within the restart watchdog window OR for pre-setup failures as described above.

#### Scenario: Call offer times out without answer
- **GIVEN** the user initiated a call and the status is `outgoing-ringing`
- **WHEN** 60 seconds pass without an `answer` signal
- **THEN** the local status SHALL transition to `ended` with reason `timeout`
- **AND** an outgoing call event message SHALL be created in the conversation

#### Scenario: ICE establishment times out
- **GIVEN** an `answer` has been processed and the status is `connecting`
- **WHEN** 30 seconds pass without the ICE state reaching `connected` or `completed`
- **THEN** the local status SHALL transition to `ended` with reason `ice-failed`

#### Scenario: Transient disconnected recovers within grace window
- **GIVEN** the call is `active` with `connectionQuality = 'good'`
- **WHEN** `iceConnectionState` transitions to `disconnected`
- **THEN** the system SHALL set `connectionQuality` to `'reconnecting'`
- **AND** the system SHALL arm a 15-second disconnected-grace timer
- **AND** the system SHALL NOT transition the call status to `ended`
- **WHEN** `iceConnectionState` returns to `connected` within the grace window
- **THEN** the system SHALL cancel the grace timer
- **AND** the system SHALL restore `connectionQuality` to `'good'`
- **AND** the call status SHALL remain `active`

#### Scenario: Grace window expires without recovery
- **GIVEN** the call is `active` and `iceConnectionState` has been `disconnected` for less than 15 seconds with `connectionQuality = 'reconnecting'`
- **WHEN** the 15-second disconnected-grace timer fires while still `disconnected`
- **THEN** the system SHALL proceed to the ICE-restart election as defined in the `ICE Restart on Failure` requirement

#### Scenario: ICE state transitions directly to failed
- **GIVEN** the call is `active` or `connecting`
- **WHEN** `iceConnectionState` transitions to `failed`
- **THEN** the system SHALL cancel any in-flight disconnected-grace timer
- **AND** the system SHALL proceed to the ICE-restart election as defined in the `ICE Restart on Failure` requirement
- **AND** the system SHALL NOT immediately transition the call status to `ended`

#### Scenario: Pre-setup ICE failure terminates immediately
- **GIVEN** the 1-on-1 call status is `outgoing-ringing` or `incoming-ringing`
- **WHEN** `iceConnectionState` transitions to `failed`
- **THEN** the system SHALL transition the call status to `ended` with reason `ice-failed`
- **AND** the system SHALL NOT attempt an ICE restart

## ADDED Requirements

### Requirement: ICE Restart on Failure

The system SHALL attempt exactly **one** ICE restart per ICE failure before transitioning the call to `ended` with reason `ice-failed`. This requirement applies to **1-on-1 calls only**; group-call edges retain the existing immediate-teardown behavior on per-edge ICE failure because group calls do not use kind-25055 Call Renegotiate in v1. The attempt MAY be deferred per the `Coordination with In-Flight Renegotiation` requirement. The attempt is bounded by the `ICE Restart Watchdog` requirement. The ICE-restart election only applies when the 1-on-1 call status is `connecting` or `active`; ICE failures observed while in `outgoing-ringing` or `incoming-ringing` SHALL continue to terminate the call immediately with reason `ice-failed` per the existing pre-setup failure path.

The system SHALL elect which side initiates the restart using the **lex-pubkey rule**: the side whose lowercase-hex pubkey sorts strictly less than the peer's pubkey is the initiator. The losing-pubkey side SHALL NOT send a restart kind-25055 and SHALL instead wait for the peer's incoming kind-25055 (or the watchdog).

The elected initiator SHALL create an offer with the `iceRestart: true` flag (W3C `RTCOfferOptions`), set it as the local description, and publish it as a kind-25055 Call Renegotiate inner event over the existing NIP-AC wire (NIP-59 kind-21059 gift wrap). The wire shape, tags, and gift-wrap envelope SHALL be byte-equivalent to a media-change kind-25055 renegotiate — receivers SHALL process both indistinguishably by calling `setRemoteDescription` followed by `createAnswer`.

The system SHALL maintain an `iceRestartAttempted` flag per 1-on-1 peer connection. The flag SHALL be set when the restart is fired (initiator) or when the corresponding kind-25055 is received and processed (non-initiator). The flag SHALL be cleared when `iceConnectionState` returns to `connected` or `completed`. If `iceConnectionState` transitions to `failed` while the flag is set, the system SHALL transition the call to `ended` with reason `ice-failed` without attempting a further restart.

The system SHALL maintain `connectionQuality = 'reconnecting'` for the entire duration of the restart attempt, from the moment the restart is fired (or the corresponding incoming kind-25055 is received) until the next `connected`/`completed` or terminal failure.

#### Scenario: Local side wins election and fires restart
- **GIVEN** the call is `active` and the local lowercase-hex pubkey sorts strictly less than the peer's
- **AND** `iceRestartAttempted` is `false`
- **WHEN** `iceConnectionState` transitions to `failed`
- **THEN** the system SHALL set `iceRestartAttempted` to `true`
- **AND** the system SHALL set `connectionQuality` to `'reconnecting'`
- **AND** the system SHALL call `createOffer({iceRestart: true})`, set the result as local description, and publish it as a kind-25055 Call Renegotiate
- **AND** the call status SHALL remain `active`

#### Scenario: Local side loses election and waits
- **GIVEN** the call is `active` and the local lowercase-hex pubkey sorts strictly greater than the peer's
- **AND** `iceRestartAttempted` is `false`
- **WHEN** `iceConnectionState` transitions to `failed`
- **THEN** the system SHALL set `connectionQuality` to `'reconnecting'`
- **AND** the system SHALL NOT publish a kind-25055 Call Renegotiate
- **WHEN** an inbound kind-25055 Call Renegotiate arrives from the peer
- **THEN** the system SHALL set `iceRestartAttempted` to `true`
- **AND** the system SHALL process the offer via the existing renegotiate path (setRemoteDescription → createAnswer)

#### Scenario: Restart succeeds and call continues
- **GIVEN** a restart has been fired and `iceRestartAttempted` is `true`
- **WHEN** `iceConnectionState` transitions to `connected` or `completed`
- **THEN** the system SHALL clear `iceRestartAttempted` to `false`
- **AND** the system SHALL restore `connectionQuality` to `'good'`
- **AND** the call SHALL remain `active`

#### Scenario: Restart fails and call terminates
- **GIVEN** a restart has been fired and `iceRestartAttempted` is `true`
- **WHEN** `iceConnectionState` transitions to `failed`
- **THEN** the system SHALL transition the call status to `ended` with reason `ice-failed`
- **AND** the existing `VoiceCallIceFailed` diagnostic dump SHALL fire

#### Scenario: Election helper rejects self-pubkey
- **GIVEN** the local pubkey is byte-equal to the peer pubkey (self-call edge case)
- **WHEN** the election helper is invoked
- **THEN** the helper SHALL return `false` (local does not initiate)
- **AND** the system SHALL log a warning

### Requirement: ICE Restart Watchdog

The system SHALL arm a watchdog timer of `ICE_CONNECTION_TIMEOUT_MS` (30 seconds, reusing the existing constant) when the elected initiator publishes the restart kind-25055 OR when the elected non-initiator first observes `iceConnectionState` reaching `failed` or `disconnected` (whichever side it is on).

The watchdog SHALL be cancelled if `iceConnectionState` reaches `connected` or `completed` before it fires. The watchdog SHALL also be cancelled if `iceConnectionState` reaches `failed` while `iceRestartAttempted` is `true` (the post-restart failure path runs to terminal teardown instead).

If the watchdog fires while `iceRestartAttempted` is `true` and `iceConnectionState` is neither `connected` nor `completed`, the system SHALL transition the call to `ended` with reason `ice-failed`.

#### Scenario: Watchdog expires without peer answer
- **GIVEN** the local side has fired a restart kind-25055 and `iceRestartAttempted` is `true`
- **AND** no kind-25055 answer has arrived from the peer
- **WHEN** 30 seconds elapse without `iceConnectionState` reaching `connected`/`completed` or `failed`
- **THEN** the system SHALL transition the call status to `ended` with reason `ice-failed`
- **AND** the existing `VoiceCallIceFailed` diagnostic dump SHALL fire

#### Scenario: Watchdog cancelled on successful recovery
- **GIVEN** a restart watchdog is armed
- **WHEN** `iceConnectionState` reaches `connected` before the watchdog fires
- **THEN** the system SHALL cancel the watchdog
- **AND** the call status SHALL remain `active`

### Requirement: Coordination with In-Flight Renegotiation

The system SHALL serialize ICE-restart kind-25055 transactions with other kind-25055 transactions (e.g., voice→video upgrade) using the existing `renegotiationState` machine (`'idle' | 'outgoing' | 'incoming' | 'glare'`). The wire format of an ICE-restart kind-25055 SHALL be byte-equivalent to a media-change kind-25055; the distinction SHALL exist only as a private `renegotiationTrigger` field (`'media' | 'ice-restart' | null`) used for diagnostics.

If a restart trigger fires while `renegotiationState !== 'idle'`, the system SHALL set a `pendingIceRestart` flag and SHALL NOT publish the restart kind-25055 immediately. When `renegotiationState` returns to `'idle'`, the system SHALL re-evaluate: if `iceConnectionState` is now `connected` or `completed`, the system SHALL clear `pendingIceRestart` and SHALL NOT fire the restart; otherwise the system SHALL fire the restart subject to the election rule and the one-attempt cap.

If a restart kind-25055 arrives from the peer while a local-initiated media-change kind-25055 is in flight (`renegotiationState === 'outgoing'`), the existing glare resolution by lex-pubkey SHALL apply unchanged: the lower-pubkey side's offer wins; the higher-pubkey side rolls back its local offer and accepts the incoming offer. The trigger label (`'media'` vs `'ice-restart'`) SHALL NOT affect glare resolution.

#### Scenario: Restart deferred during voice-to-video upgrade
- **GIVEN** the call is `active` and a voice→video upgrade is in flight (`renegotiationState === 'outgoing'`, `renegotiationTrigger === 'media'`)
- **WHEN** `iceConnectionState` transitions to `failed`
- **THEN** the system SHALL set `pendingIceRestart` to `true`
- **AND** the system SHALL NOT publish a kind-25055 Call Renegotiate
- **WHEN** the upgrade renegotiation completes and `renegotiationState` returns to `'idle'`
- **AND** `iceConnectionState` is still neither `connected` nor `completed`
- **THEN** the system SHALL fire the deferred restart subject to the election rule

#### Scenario: Deferred restart cancelled when connection recovers during upgrade
- **GIVEN** `pendingIceRestart` is `true` and a media renegotiation is completing
- **WHEN** `renegotiationState` returns to `'idle'` AND `iceConnectionState` is `connected` or `completed`
- **THEN** the system SHALL clear `pendingIceRestart` to `false`
- **AND** the system SHALL NOT fire a restart

#### Scenario: Glare between local media upgrade and remote ICE restart
- **GIVEN** a local voice→video upgrade kind-25055 is in flight and the local pubkey is greater than the peer pubkey
- **WHEN** an inbound kind-25055 Call Renegotiate arrives from the peer (an ICE-restart attempt)
- **THEN** the existing glare resolution SHALL apply: the local side SHALL roll back its local offer
- **AND** the local side SHALL accept the peer's incoming offer via the existing renegotiate path

### Requirement: Connection Quality Signal

The 1-on-1 call state SHALL expose a `connectionQuality` field with values `'good'` or `'reconnecting'` on `voiceCallState.connectionQuality`. The field SHALL be `'good'` by default.

The field SHALL be set to `'reconnecting'` whenever any of the following are true for the 1-on-1 peer connection: `iceConnectionState === 'disconnected'` and within the grace window; OR an ICE restart is in flight (whether initiated locally or by the peer); OR `pendingIceRestart === true`. The field SHALL be restored to `'good'` immediately on `iceConnectionState` reaching `connected` or `completed`, or when the call transitions to `ended`.

The 1-on-1 call overlay (`ActiveCallOverlay.svelte`) and the Android in-call surface (`ActiveCallActivity.java`) SHALL render a visible "Reconnecting…" indicator whenever `voiceCallState.connectionQuality` is `'reconnecting'`. The indicator SHALL NOT be rendered when `connectionQuality` is `'good'`.

The group call state's `ParticipantState` SHALL include a `connectionQuality` field of the same `'good' | 'reconnecting'` shape for forward compatibility with the future group-call ICE-restart change. The field SHALL default to `'good'`. No code path in the current change mutates this field for group calls; group-call edges retain their existing immediate-teardown behavior on ICE failure.

#### Scenario: Reconnecting indicator appears during disconnected grace window
- **GIVEN** the call is `active` with `connectionQuality = 'good'`
- **WHEN** `iceConnectionState` transitions to `disconnected`
- **THEN** `connectionQuality` SHALL become `'reconnecting'`
- **AND** the call overlay SHALL render the "Reconnecting…" indicator

#### Scenario: Reconnecting indicator clears on recovery
- **GIVEN** the call has `connectionQuality = 'reconnecting'`
- **WHEN** `iceConnectionState` returns to `connected` or `completed`
- **THEN** `connectionQuality` SHALL become `'good'`
- **AND** the call overlay SHALL NOT render the "Reconnecting…" indicator

#### Scenario: Group ParticipantState carries connectionQuality default
- **GIVEN** a group call is initiated with one or more participants
- **THEN** each `groupVoiceCallState.participants[hex].connectionQuality` SHALL be `'good'`
- **AND** group-edge ICE failures SHALL continue to transition that edge to `pcStatus='ended'` with `endReason='ice-failed'` per the existing per-edge teardown requirement
- **AND** the group call overlay SHALL NOT render a "Reconnecting…" indicator

### Requirement: ICE Restart Diagnostics

The system SHALL emit a structured log line under the tag `VoiceCallIceRestart` (web: `console.info`; Android: `Log.i`) at the following points:

- **restart-fired:** when the elected initiator publishes the restart kind-25055. The log line SHALL include the election outcome (`'local-wins'`), the trigger (`'failed'` or `'disconnected-grace-timeout'`), and a redacted peer identifier.
- **restart-received:** when the elected non-initiator receives and begins processing an inbound restart kind-25055. The log line SHALL include the election outcome (`'local-loses'`) and a redacted peer identifier.
- **restart-succeeded:** when `iceConnectionState` returns to `connected`/`completed` while `iceRestartAttempted` was `true`. The log line SHALL include the elapsed milliseconds from restart-fired (or restart-received) to recovery.
- **restart-failed:** when the restart attempt terminates with `ice-failed` (either via post-restart `failed` state or watchdog expiry). The log line SHALL include the elapsed milliseconds and the failure mode (`'failed-state'` or `'watchdog-expired'`).

The log lines SHALL NOT contain SDP, ICE candidate addresses, or any other PII beyond the redacted peer identifier (matching the redaction policy used by the existing `VoiceCallIceFailed` diagnostic).

The existing `VoiceCallIceFailed` diagnostic SHALL continue to fire on terminal `ice-failed` teardown and SHALL NOT fire on the intermediate restart attempt.

#### Scenario: Restart-fired log emitted on local-initiated restart
- **GIVEN** the local side wins the election and publishes a restart kind-25055
- **THEN** a `VoiceCallIceRestart` log line SHALL be emitted with `event: 'restart-fired'`, `election: 'local-wins'`, and the trigger

#### Scenario: Restart-succeeded log emitted on recovery
- **GIVEN** a restart was fired or received and `iceRestartAttempted` is `true`
- **WHEN** `iceConnectionState` returns to `connected` or `completed`
- **THEN** a `VoiceCallIceRestart` log line SHALL be emitted with `event: 'restart-succeeded'` and the elapsed milliseconds

#### Scenario: Diagnostic dump fires only on terminal teardown
- **GIVEN** a restart is in flight
- **WHEN** the restart succeeds and the call continues
- **THEN** the `VoiceCallIceFailed` diagnostic SHALL NOT fire
- **WHEN** the restart fails and the call transitions to `ended` with reason `ice-failed`
- **THEN** the `VoiceCallIceFailed` diagnostic SHALL fire exactly once
