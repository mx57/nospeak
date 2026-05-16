/*
 * ICE-restart diagnostic logging. Mirrors the JS-side
 * {@code iceRestartDiagnostic.ts} helper so support reports include
 * the lifecycle of any ICE-restart attempt on both backends with the
 * same log tag and field set.
 *
 * <p>Emits {@code Log.i(LOG_TAG, ...)} lines at the four lifecycle
 * points:
 *
 * <ul>
 *   <li>{@code restart-fired} — local side won the lex-pubkey election
 *       and just published the kind-25055 Call Renegotiate (or, when
 *       local loses, just observed the failure and is waiting for the
 *       peer's inbound).</li>
 *   <li>{@code restart-received} — local side lost the election and is
 *       processing the peer's inbound kind-25055 as a restart.</li>
 *   <li>{@code restart-succeeded} — {@code iceConnectionState} returned
 *       to {@code CONNECTED}/{@code COMPLETED} while a restart was in
 *       flight.</li>
 *   <li>{@code restart-failed} — restart attempt terminated; either
 *       state re-entered {@code FAILED} or the watchdog expired.</li>
 * </ul>
 *
 * <p>Peer pubkey is redacted to the first 8 lowercase-hex chars
 * matching {@code IceFailedDiagnostic} policy and the JS-side
 * {@code redactPeerHex} helper. No SDP, no candidate addresses.
 */
package com.nospeak.app;

import android.util.Log;

import java.util.Locale;

public final class IceRestartLogger {

    public static final String TAG = "VoiceCallIceRestart";

    public static final String EVENT_FIRED     = "restart-fired";
    public static final String EVENT_RECEIVED  = "restart-received";
    public static final String EVENT_SUCCEEDED = "restart-succeeded";
    public static final String EVENT_FAILED    = "restart-failed";

    public static final String ELECTION_LOCAL_WINS  = "local-wins";
    public static final String ELECTION_LOCAL_LOSES = "local-loses";

    public static final String TRIGGER_FAILED      = "failed";
    public static final String TRIGGER_GRACE_TIMEOUT = "disconnected-grace-timeout";

    public static final String FAILURE_FAILED_STATE   = "failed-state";
    public static final String FAILURE_WATCHDOG_EXPIRED = "watchdog-expired";

    private IceRestartLogger() {
        /* Static helper. */
    }

    /**
     * Redact a peer pubkey to its first 8 lowercase-hex chars. Matches
     * the redaction policy in {@code iceRestartDiagnostic.redactPeerHex}
     * (JS) so log aggregation can correlate across backends.
     */
    public static String redactPeerHex(String peerHex) {
        if (peerHex == null || peerHex.isEmpty()) return "unknown";
        String lower = peerHex.toLowerCase(Locale.ROOT);
        return lower.length() <= 8 ? lower : lower.substring(0, 8);
    }

    public static void logFired(
            String election, String trigger, String peerHex) {
        try {
            Log.i(TAG,
                "{event:" + EVENT_FIRED
                + ", election:" + election
                + ", trigger:" + trigger
                + ", peer:" + redactPeerHex(peerHex)
                + "}");
        } catch (Throwable t) {
            /* swallow */
        }
    }

    public static void logReceived(String election, String peerHex) {
        try {
            Log.i(TAG,
                "{event:" + EVENT_RECEIVED
                + ", election:" + election
                + ", peer:" + redactPeerHex(peerHex)
                + "}");
        } catch (Throwable t) {
            /* swallow */
        }
    }

    public static void logSucceeded(long elapsedMs, String peerHex) {
        try {
            Log.i(TAG,
                "{event:" + EVENT_SUCCEEDED
                + ", elapsedMs:" + Math.max(0L, elapsedMs)
                + ", peer:" + redactPeerHex(peerHex)
                + "}");
        } catch (Throwable t) {
            /* swallow */
        }
    }

    public static void logFailed(long elapsedMs, String failure, String peerHex) {
        try {
            Log.i(TAG,
                "{event:" + EVENT_FAILED
                + ", elapsedMs:" + Math.max(0L, elapsedMs)
                + ", failure:" + failure
                + ", peer:" + redactPeerHex(peerHex)
                + "}");
        } catch (Throwable t) {
            /* swallow */
        }
    }
}
