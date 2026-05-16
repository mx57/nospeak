/*
 * Pure-logic helper for the ICE-restart decision branch in
 * {@link NativeVoiceCallManager#triggerIceRestart}. Extracted so the
 * three-input decision (already-attempted? in-flight renegotiation?
 * election outcome?) can be unit-tested without spinning up a
 * peer-connection factory.
 *
 * <p>The factored decision is the same as in
 * {@code VoiceCallService.triggerIceRestart} (web), so a single test
 * suite per platform validates that the JS and Java branches stay in
 * lock-step.
 */
package com.nospeak.app;

public final class IceRestartDecision {

    public enum Action {
        /** Local side won the election; publish a kind-25055 now. */
        FIRE,
        /** Local side lost the election; arm the watchdog and wait. */
        WAIT_AS_LOSER,
        /** A media renegotiation is in flight; defer the restart. */
        DEFER,
        /** Restart already attempted on this failure; no second try. */
        NOOP
    }

    private IceRestartDecision() {
        /* Static helper. */
    }

    /**
     * Decide the action given the three relevant inputs.
     *
     * @param iceRestartAttempted true iff a restart has already been
     *     fired (or received) since the last CONNECTED/COMPLETED.
     * @param renegotiationInFlight true iff
     *     {@link NativeVoiceCallManager.RenegotiationState} is
     *     non-IDLE (a media renegotiation is in flight).
     * @param localWinsElection true iff
     *     {@link IceRestartElection#shouldInitiate} returned true for
     *     the (localHex, peerHex) pair.
     */
    public static Action decide(
            boolean iceRestartAttempted,
            boolean renegotiationInFlight,
            boolean localWinsElection) {
        if (iceRestartAttempted) return Action.NOOP;
        if (renegotiationInFlight) return Action.DEFER;
        return localWinsElection ? Action.FIRE : Action.WAIT_AS_LOSER;
    }
}
