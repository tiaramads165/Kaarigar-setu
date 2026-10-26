package com.kaarigarsetu.trust;

import com.kaarigarsetu.trust.TrustAttackSimulation.Capability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees the scoring design makes against fake-employer rings, checked with the production scoring code. */
class TrustAttackSimulationTest {
    @Test
    void beforeTheVettingRuleThreeThrowawayAccountsWereEnoughForExcellent() {
        assertEquals(3, TrustAttackSimulation.employersNeeded(Capability.THROWAWAY, 80, 30, TrustAttackSimulation.withoutVettingRule()), "the weakness the simulation exposed");
    }

    @Test
    void nowNoNumberOfUnvettedAccountsReachesExcellentWhateverTheirHistoryOrStanding() {
        for (Capability capability : new Capability[]{Capability.THROWAWAY, Capability.WARMED_UP, Capability.WARMED_AND_WELL_CONNECTED}) {
            for (int k : new int[]{3, 10, 30, 100}) {
                var report = TrustAttackSimulation.attack(capability, k, 1, 0);
                assertTrue(report.score().doubleValue() <= 79.99, capability + " k=" + k + " reached " + report.score());
            }
            assertEquals(-1, TrustAttackSimulation.employersNeeded(capability, 80, 100));
        }
    }

    @Test
    void theAttackerMustNowCorruptTheVettingProcessAndStillNeedsThreeAccounts() {
        assertEquals(3, TrustAttackSimulation.employersNeeded(Capability.VETTED_INSIDER, 80, 30));
        assertTrue(TrustAttackSimulation.attack(Capability.VETTED_INSIDER, 2, 1, 0).score().doubleValue() < 65, "two accounts are never enough, even vetted");
    }

    @Test
    void oneEmployerCannotGoAbove49NoMatterHowOftenOrHowVetted() {
        for (Capability capability : Capability.values()) {
            assertTrue(TrustAttackSimulation.attack(capability, 1, 500, 0).score().doubleValue() < 50, capability.name());
        }
    }

    @Test
    void aModeratorRulingAgainstOneConfirmationCostsTheRing() {
        double before = TrustAttackSimulation.attack(Capability.VETTED_INSIDER, 3, 1, 0).score().doubleValue();
        double after = TrustAttackSimulation.attack(Capability.VETTED_INSIDER, 3, 1, 1).score().doubleValue();
        assertTrue(after < before - 4, before + " -> " + after);
    }
}
