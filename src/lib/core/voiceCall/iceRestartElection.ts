/**
 * ICE-restart initiator election. When both ends of a peer connection
 * observe `iceConnectionState === 'failed'` (or the disconnected-grace
 * window expires), exactly one side SHALL initiate the ICE restart by
 * publishing a kind-25055 Call Renegotiate with an
 * {@code iceRestart: true} SDP. The other side waits for the inbound
 * kind-25055.
 *
 * Election rule: the side whose lowercase-hex pubkey sorts strictly
 * less than the peer's is the initiator. This is the same lex-pubkey
 * rule already used for kind-25055 glare resolution (see
 * {@code VoiceCallService.handleRenegotiate} glare branch).
 *
 * Mirrored on Android by {@code com.nospeak.app.IceRestartElection}.
 * The two implementations MUST agree byte-for-byte on the comparison
 * (lowercase-hex string compare) so peers running different backends
 * elect the same side.
 *
 * Self-call edge case ({@code localHex === peerHex}) — already
 * forbidden by call-initiation logic — returns {@code false} with a
 * console warning.
 */
export function shouldInitiateIceRestart(
    localHex: string,
    peerHex: string
): boolean {
    const local = localHex.toLowerCase();
    const peer = peerHex.toLowerCase();
    if (local === peer) {
        console.warn(
            '[VoiceCall][IceRestartElection] localHex === peerHex; refusing to elect (self-call?)'
        );
        return false;
    }
    return local < peer;
}
