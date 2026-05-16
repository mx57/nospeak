package com.nospeak.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-Java tests for {@link IceRestartElection}. Mirrors the JS-side
 * tests in {@code src/lib/core/voiceCall/iceRestartElection.test.ts} to
 * keep the two implementations in lock-step. Both implementations MUST
 * agree byte-for-byte on every input so peers running different
 * backends elect the same side.
 */
public class IceRestartElectionTest {

    @Test
    public void returnsTrue_whenLocalSortsStrictlyLower() {
        assertTrue(IceRestartElection.shouldInitiate(
            "0000000000000000000000000000000000000000000000000000000000000001",
            "0000000000000000000000000000000000000000000000000000000000000002"
        ));
    }

    @Test
    public void returnsFalse_whenLocalSortsStrictlyHigher() {
        assertFalse(IceRestartElection.shouldInitiate(
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0000000000000000000000000000000000000000000000000000000000000001"
        ));
    }

    @Test
    public void returnsFalse_whenBothByteEqual() {
        String hex = "abababababababababababababababababababababababababababababababab";
        assertFalse(IceRestartElection.shouldInitiate(hex, hex));
    }

    @Test
    public void returnsFalse_whenEqualAfterCaseNormalization() {
        assertFalse(IceRestartElection.shouldInitiate(
            "AAAA000000000000000000000000000000000000000000000000000000000000",
            "aaaa000000000000000000000000000000000000000000000000000000000000"
        ));
    }

    @Test
    public void normalizesUppercaseLocalInput() {
        // "AAAA..." -> "aaaa..." < "bbbb...": local wins.
        assertTrue(IceRestartElection.shouldInitiate(
            "AAAA000000000000000000000000000000000000000000000000000000000000",
            "bbbb000000000000000000000000000000000000000000000000000000000000"
        ));
    }

    @Test
    public void normalizesUppercasePeerInput() {
        // local "cccc" > peer "BBBB" -> "bbbb": local does NOT initiate.
        assertFalse(IceRestartElection.shouldInitiate(
            "cccc000000000000000000000000000000000000000000000000000000000000",
            "BBBB000000000000000000000000000000000000000000000000000000000000"
        ));
    }

    @Test
    public void handlesMixedCaseConsistently() {
        String localUpper = "AbCdEf0000000000000000000000000000000000000000000000000000000000";
        String peerUpper  = "aBcDeF0000000000000000000000000000000000000000000000000000000000";
        // Both normalize to the same hex; expect equal-path return.
        assertFalse(IceRestartElection.shouldInitiate(localUpper, peerUpper));
    }

    @Test
    public void lexCompareUsesStringOrdering_not_numericMagnitude() {
        // "10" < "9" in string compare. Confirm we follow the string
        // rule (matching JS sort semantics on String.compareTo).
        assertTrue(IceRestartElection.shouldInitiate(
            "1000000000000000000000000000000000000000000000000000000000000000",
            "9000000000000000000000000000000000000000000000000000000000000000"
        ));
    }

    @Test
    public void returnsFalse_onNullLocal() {
        assertFalse(IceRestartElection.shouldInitiate(
            null,
            "aaaa000000000000000000000000000000000000000000000000000000000000"
        ));
    }

    @Test
    public void returnsFalse_onNullPeer() {
        assertFalse(IceRestartElection.shouldInitiate(
            "aaaa000000000000000000000000000000000000000000000000000000000000",
            null
        ));
    }

    @Test
    public void returnsFalse_onBothNull() {
        assertFalse(IceRestartElection.shouldInitiate(null, null));
    }
}
