package org.craftcore.stellaria.enchants;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownTrackerTest {

    private final UUID player = UUID.randomUUID();

    @Test
    void firstUseSucceedsAndStartsTheCooldown() {
        CooldownTracker tracker = new CooldownTracker();
        assertTrue(tracker.tryUse(player, 1_000, 900_000));
        assertFalse(tracker.tryUse(player, 1_000 + 899_999, 900_000));
        assertEquals(1, tracker.remainingMillis(player, 1_000 + 899_999));
    }

    @Test
    void useSucceedsAgainOnceTheCooldownHasPassed() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.tryUse(player, 0, 900_000);
        assertTrue(tracker.tryUse(player, 900_000, 900_000));
    }

    @Test
    void playersHaveIndependentCooldowns() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.tryUse(player, 0, 900_000);
        assertTrue(tracker.tryUse(UUID.randomUUID(), 0, 900_000));
        assertEquals(0, tracker.remainingMillis(UUID.randomUUID(), 0));
    }
}
