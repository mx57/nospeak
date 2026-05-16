package com.nospeak.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pure-Java tests for {@link IceRestartDecision}. The matrix is
 * exhaustive (2 × 2 × 2 = 8 combinations) so any future change to the
 * decision-tree precedence (one-attempt cap > defer > election) is
 * caught immediately. Mirrors the JS-side decision branch in
 * {@code VoiceCallService.triggerIceRestart}.
 */
public class IceRestartDecisionTest {

    @Test
    public void firstAttempt_idleRenegotiation_localWins_fires() {
        assertEquals(
            IceRestartDecision.Action.FIRE,
            IceRestartDecision.decide(false, false, true)
        );
    }

    @Test
    public void firstAttempt_idleRenegotiation_localLoses_waitsAsLoser() {
        assertEquals(
            IceRestartDecision.Action.WAIT_AS_LOSER,
            IceRestartDecision.decide(false, false, false)
        );
    }

    @Test
    public void firstAttempt_renegotiationInFlight_localWins_defers() {
        assertEquals(
            IceRestartDecision.Action.DEFER,
            IceRestartDecision.decide(false, true, true)
        );
    }

    @Test
    public void firstAttempt_renegotiationInFlight_localLoses_defers() {
        assertEquals(
            IceRestartDecision.Action.DEFER,
            IceRestartDecision.decide(false, true, false)
        );
    }

    @Test
    public void alreadyAttempted_overridesEverything_noop_1() {
        assertEquals(
            IceRestartDecision.Action.NOOP,
            IceRestartDecision.decide(true, false, true)
        );
    }

    @Test
    public void alreadyAttempted_overridesEverything_noop_2() {
        assertEquals(
            IceRestartDecision.Action.NOOP,
            IceRestartDecision.decide(true, false, false)
        );
    }

    @Test
    public void alreadyAttempted_overridesEverything_noop_3() {
        assertEquals(
            IceRestartDecision.Action.NOOP,
            IceRestartDecision.decide(true, true, true)
        );
    }

    @Test
    public void alreadyAttempted_overridesEverything_noop_4() {
        assertEquals(
            IceRestartDecision.Action.NOOP,
            IceRestartDecision.decide(true, true, false)
        );
    }
}
