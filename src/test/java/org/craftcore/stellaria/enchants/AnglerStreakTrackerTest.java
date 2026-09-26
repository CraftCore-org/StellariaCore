package org.craftcore.stellaria.enchants;

import org.craftcore.stellaria.enchants.AnglerStreakTracker.Result;
import org.craftcore.stellaria.enchants.AnglerStreakTracker.Settings;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnglerStreakTrackerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);
    private static final Settings SETTINGS = new Settings(60_000, 5, 10, 2.0, 3000);

    private final UUID player = UUID.randomUUID();

    @Test
    void firstCatchStartsAStreakWithoutBonus() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Result result = tracker.recordCatch(player, 0, DAY, SETTINGS);
        assertEquals(0, result.streak());
        assertEquals(0, result.payout());
    }

    @Test
    void catchesWithinTheWindowGrowTheStreakAndPay() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        tracker.recordCatch(player, 0, DAY, SETTINGS);
        Result second = tracker.recordCatch(player, 60_000, DAY, SETTINGS);
        assertEquals(1, second.streak());
        assertEquals(10, second.payout()); // 5 × 1 × 2.0
        Result third = tracker.recordCatch(player, 100_000, DAY, SETTINGS);
        assertEquals(2, third.streak());
        assertEquals(20, third.payout());
    }

    @Test
    void catchOutsideTheWindowResetsTheStreak() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        tracker.recordCatch(player, 0, DAY, SETTINGS);
        tracker.recordCatch(player, 10_000, DAY, SETTINGS);
        Result late = tracker.recordCatch(player, 70_001, DAY, SETTINGS);
        assertEquals(0, late.streak());
        assertEquals(0, late.payout());
    }

    @Test
    void bonusIsCappedAtMaxStreak() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Result last = null;
        for (int i = 0; i <= 15; i++) {
            last = tracker.recordCatch(player, i * 1_000L, DAY, SETTINGS);
        }
        assertEquals(15, last.streak());
        assertEquals(100, last.payout()); // 5 × min(15,10) × 2.0
    }

    @Test
    void dailyCapLimitsPayoutAndReportsReachingItOnce() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Settings smallCap = new Settings(60_000, 5, 10, 2.0, 25);
        tracker.recordCatch(player, 0, DAY, smallCap);
        assertEquals(10, tracker.recordCatch(player, 1_000, DAY, smallCap).payout());
        Result capped = tracker.recordCatch(player, 2_000, DAY, smallCap);
        assertEquals(15, capped.payout()); // 20 のうち残り 15 だけ
        assertTrue(capped.capJustReached());
        Result after = tracker.recordCatch(player, 3_000, DAY, smallCap);
        assertEquals(0, after.payout());
        assertFalse(after.capJustReached());
    }

    @Test
    void newDayResetsThePaidTotal() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Settings smallCap = new Settings(60_000, 5, 10, 2.0, 10);
        tracker.recordCatch(player, 0, DAY, smallCap);
        tracker.recordCatch(player, 1_000, DAY, smallCap);
        assertEquals(10, tracker.recordCatch(player, 2_000, DAY.plusDays(1), smallCap).payout());
    }

    @Test
    void resetStreakKeepsThePaidTotal() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Settings smallCap = new Settings(60_000, 5, 10, 2.0, 10);
        tracker.recordCatch(player, 0, DAY, smallCap);
        tracker.recordCatch(player, 1_000, DAY, smallCap);
        tracker.resetStreak(player);
        assertEquals(0, tracker.recordCatch(player, 2_000, DAY, smallCap).streak());
        assertEquals(0, tracker.recordCatch(player, 3_000, DAY, smallCap).payout());
    }
}
