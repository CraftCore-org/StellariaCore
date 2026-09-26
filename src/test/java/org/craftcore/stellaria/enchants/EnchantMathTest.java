package org.craftcore.stellaria.enchants;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantMathTest {

    private static DoubleSupplier sequence(Double... values) {
        Iterator<Double> iterator = List.of(values).iterator();
        return iterator::next;
    }

    @Test
    void rollSucceedsOnlyBelowChance() {
        assertTrue(EnchantMath.roll(0.3, () -> 0.29));
        assertFalse(EnchantMath.roll(0.3, () -> 0.3));
        assertFalse(EnchantMath.roll(0.0, () -> 0.0));
    }

    @Test
    void chanceForLevelScalesAndClampsToOne() {
        assertEquals(0.3, EnchantMath.chanceForLevel(0.1, 3), 1e-9);
        assertEquals(1.0, EnchantMath.chanceForLevel(0.6, 2), 1e-9);
        assertEquals(0.0, EnchantMath.chanceForLevel(0.1, 0), 1e-9);
    }

    @Test
    void rollSuccessesCountsEachUnitIndependently() {
        assertEquals(2, EnchantMath.rollSuccesses(3, 0.5, sequence(0.1, 0.9, 0.2)));
        assertEquals(0, EnchantMath.rollSuccesses(0, 0.5, sequence()));
    }

    @Test
    void roundExperienceUsesFractionAsChance() {
        assertEquals(2, EnchantMath.roundExperience(2.7, () -> 0.8));
        assertEquals(3, EnchantMath.roundExperience(2.7, () -> 0.6));
        assertEquals(0, EnchantMath.roundExperience(0.0, () -> 0.0));
    }

    @Test
    void foodGateRequiresAtLeastTheMinimum() {
        assertTrue(EnchantMath.hasEnoughFood(7, 7));
        assertFalse(EnchantMath.hasEnoughFood(6, 7));
    }

    @Test
    void perLevelPicksTheLevelEntryAndClampsToTheLast() {
        double[] values = {15, 12, 10};
        assertEquals(15, EnchantMath.perLevel(values, 1));
        assertEquals(10, EnchantMath.perLevel(values, 3));
        assertEquals(10, EnchantMath.perLevel(values, 5));
        assertEquals(15, EnchantMath.perLevel(values, 0));
    }

    @Test
    void healedHealthNeverExceedsMax() {
        assertEquals(14.0, EnchantMath.healedHealth(10.0, 20.0, 4.0));
        assertEquals(20.0, EnchantMath.healedHealth(18.0, 20.0, 6.0));
    }

    @Test
    void lastStandTriggersOnlyWhenAliveAndAtOrBelowThreshold() {
        assertTrue(EnchantMath.shouldTriggerLastStand(6.0, 20.0, 0.3));
        assertFalse(EnchantMath.shouldTriggerLastStand(6.5, 20.0, 0.3));
        assertFalse(EnchantMath.shouldTriggerLastStand(0.0, 20.0, 0.3));
    }
}
