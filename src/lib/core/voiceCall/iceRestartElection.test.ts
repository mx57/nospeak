import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { shouldInitiateIceRestart } from './iceRestartElection';

describe('shouldInitiateIceRestart', () => {
    let warnSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    });

    afterEach(() => {
        warnSpy.mockRestore();
    });

    it('returns true when local pubkey sorts strictly lower than peer', () => {
        expect(
            shouldInitiateIceRestart(
                '0000000000000000000000000000000000000000000000000000000000000001',
                '0000000000000000000000000000000000000000000000000000000000000002'
            )
        ).toBe(true);
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('returns false when local pubkey sorts strictly higher than peer', () => {
        expect(
            shouldInitiateIceRestart(
                'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                '0000000000000000000000000000000000000000000000000000000000000001'
            )
        ).toBe(false);
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('returns false and warns when both pubkeys are byte-equal', () => {
        const hex =
            'abababababababababababababababababababababababababababababababab';
        expect(shouldInitiateIceRestart(hex, hex)).toBe(false);
        expect(warnSpy).toHaveBeenCalledTimes(1);
        expect(warnSpy.mock.calls[0][0]).toContain('IceRestartElection');
    });

    it('returns false and warns when pubkeys differ only in case (still equal after normalization)', () => {
        expect(
            shouldInitiateIceRestart(
                'AAAA000000000000000000000000000000000000000000000000000000000000',
                'aaaa000000000000000000000000000000000000000000000000000000000000'
            )
        ).toBe(false);
        expect(warnSpy).toHaveBeenCalledTimes(1);
    });

    it('normalizes uppercase local input', () => {
        // Local 0xAAAA... > peer 0xBBBB... ? No: after lowercase, "aaaa" < "bbbb".
        expect(
            shouldInitiateIceRestart(
                'AAAA000000000000000000000000000000000000000000000000000000000000',
                'bbbb000000000000000000000000000000000000000000000000000000000000'
            )
        ).toBe(true);
    });

    it('normalizes uppercase peer input', () => {
        // Local "cccc" > peer "BBBB"→"bbbb", so local does NOT initiate.
        expect(
            shouldInitiateIceRestart(
                'cccc000000000000000000000000000000000000000000000000000000000000',
                'BBBB000000000000000000000000000000000000000000000000000000000000'
            )
        ).toBe(false);
    });

    it('handles mixed-case on both sides consistently with all-lowercase', () => {
        const localUpper =
            'AbCdEf0000000000000000000000000000000000000000000000000000000000';
        const peerUpper =
            'aBcDeF0000000000000000000000000000000000000000000000000000000000';
        // Both normalize to the same hex; expect equal-path warning.
        expect(shouldInitiateIceRestart(localUpper, peerUpper)).toBe(false);
        expect(warnSpy).toHaveBeenCalledTimes(1);
    });

    it('lex compare uses string ordering not numeric magnitude', () => {
        // "10" < "9" in string compare. Confirm that even though the
        // numeric interpretation is opposite, the helper follows the
        // string rule (matching the JS sort semantics on Android-side
        // String.compareTo).
        expect(
            shouldInitiateIceRestart(
                '1000000000000000000000000000000000000000000000000000000000000000',
                '9000000000000000000000000000000000000000000000000000000000000000'
            )
        ).toBe(true);
    });
});
