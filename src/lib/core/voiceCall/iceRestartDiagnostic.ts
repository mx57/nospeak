/**
 * ICE-restart diagnostic logging. Emits structured log lines under the
 * tag {@code VoiceCallIceRestart} at the four lifecycle points of an
 * ICE-restart attempt:
 *
 * <ul>
 *   <li>{@code restart-fired} — local side won the lex-pubkey election
 *       and just published the kind-25055 Call Renegotiate with
 *       {@code iceRestart: true}.</li>
 *   <li>{@code restart-received} — local side lost the election and is
 *       processing the peer's inbound kind-25055 as a restart.</li>
 *   <li>{@code restart-succeeded} — {@code iceConnectionState} returned
 *       to {@code connected}/{@code completed} while a restart was in
 *       flight.</li>
 *   <li>{@code restart-failed} — restart attempt terminated; either
 *       {@code iceConnectionState} re-entered {@code failed} or the
 *       watchdog expired.</li>
 * </ul>
 *
 * Peer pubkey is redacted to the first 8 hex chars matching the policy
 * used by {@link iceFailedDiagnostic}. No SDP, no candidate addresses.
 *
 * Mirrored on Android by {@code com.nospeak.app.IceRestartLogger} which
 * writes to logcat under the same tag.
 *
 * Part of {@code add-ice-restart-on-failed}.
 */

export type IceRestartEvent =
    | 'restart-fired'
    | 'restart-received'
    | 'restart-succeeded'
    | 'restart-failed';

export type IceRestartElectionResult = 'local-wins' | 'local-loses';

export type IceRestartTrigger = 'failed' | 'disconnected-grace-timeout';

export type IceRestartFailure = 'failed-state' | 'watchdog-expired';

const LOG_TAG = '[VoiceCallIceRestart]';

/**
 * Redact a peer pubkey to its first 8 lowercase-hex chars (matches the
 * policy used by {@code iceFailedDiagnostic.redactAddress} for IPv4
 * networks, scaled to the hex identifier domain).
 */
export function redactPeerHex(peerHex: string | null | undefined): string {
    if (!peerHex || typeof peerHex !== 'string') return 'unknown';
    return peerHex.toLowerCase().slice(0, 8);
}

export interface IceRestartFiredFields {
    election: IceRestartElectionResult;
    trigger: IceRestartTrigger;
    peerHex: string | null;
    /** Optional per-edge identifier for group calls. */
    edgeId?: string;
}

export interface IceRestartReceivedFields {
    election: IceRestartElectionResult;
    peerHex: string | null;
    edgeId?: string;
}

export interface IceRestartSucceededFields {
    elapsedMs: number;
    peerHex: string | null;
    edgeId?: string;
}

export interface IceRestartFailedFields {
    elapsedMs: number;
    failure: IceRestartFailure;
    peerHex: string | null;
    edgeId?: string;
}

/**
 * Emit a {@code VoiceCallIceRestart} log line. Best-effort: any
 * failure inside the logger is swallowed and SHALL NOT affect the
 * caller's control flow.
 */
export function logIceRestartEvent(
    event: 'restart-fired',
    fields: IceRestartFiredFields
): void;
export function logIceRestartEvent(
    event: 'restart-received',
    fields: IceRestartReceivedFields
): void;
export function logIceRestartEvent(
    event: 'restart-succeeded',
    fields: IceRestartSucceededFields
): void;
export function logIceRestartEvent(
    event: 'restart-failed',
    fields: IceRestartFailedFields
): void;
export function logIceRestartEvent(
    event: IceRestartEvent,
    fields:
        | IceRestartFiredFields
        | IceRestartReceivedFields
        | IceRestartSucceededFields
        | IceRestartFailedFields
): void {
    try {
        const peerRedacted = redactPeerHex(fields.peerHex);
        // Spread fields but replace the raw peerHex with the redacted
        // form. The redacted form is also exposed under `peer` for
        // ease of consumption by log-aggregation queries.
        const payload: Record<string, unknown> = {
            event,
            peer: peerRedacted,
            ...fields
        };
        delete (payload as { peerHex?: unknown }).peerHex;
        console.info(LOG_TAG, payload);
    } catch {
        // Diagnostic path must never affect call flow. Swallow.
    }
}
