/*
 * ICE-restart initiator election. Mirrors the JS helper in
 * src/lib/core/voiceCall/iceRestartElection.ts byte-for-byte.
 *
 * <p>When both ends of a 1-on-1 peer connection observe
 * {@code iceConnectionState === 'failed'} (or the disconnected-grace
 * window expires), exactly one side SHALL initiate the ICE restart by
 * publishing a kind-25055 Call Renegotiate with an
 * {@code iceRestart: true} SDP. The other side waits for the inbound
 * kind-25055.
 *
 * <p>Election rule: the side whose lowercase-hex pubkey sorts strictly
 * less than the peer's is the initiator. This is the same lex-pubkey
 * rule already used for kind-25055 glare resolution
 * ({@code NativeVoiceCallManager.handleRemoteRenegotiate}).
 *
 * <p>Self-call edge case ({@code localHex == peerHex}, already
 * forbidden by call-initiation logic) returns {@code false} with a
 * logcat warning.
 */
package com.nospeak.app;

import android.util.Log;

import java.util.Locale;

public final class IceRestartElection {

    private static final String TAG = "IceRestartElection";

    private IceRestartElection() {
        /* Static helper. */
    }

    /**
     * Returns {@code true} iff the local side is the elected initiator
     * for an ICE restart on this peer connection.
     */
    public static boolean shouldInitiate(String localHex, String peerHex) {
        if (localHex == null || peerHex == null) {
            Log.w(TAG, "shouldInitiate: null hex; refusing to elect "
                + "(local=" + (localHex == null ? "null" : "set")
                + ", peer=" + (peerHex == null ? "null" : "set") + ")");
            return false;
        }
        String local = localHex.toLowerCase(Locale.ROOT);
        String peer = peerHex.toLowerCase(Locale.ROOT);
        if (local.equals(peer)) {
            Log.w(TAG, "shouldInitiate: localHex == peerHex; refusing to elect (self-call?)");
            return false;
        }
        return local.compareTo(peer) < 0;
    }
}
