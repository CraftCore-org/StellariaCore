package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoginDaysTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

    private static Set<LocalDate> daysBack(int... offsets) {
        Set<LocalDate> days = new HashSet<>();
        for (int offset : offsets) {
            days.add(TODAY.minusDays(offset));
        }
        return days;
    }

    @Test
    void streakCountsConsecutiveDaysEndingToday() {
        assertEquals(3, LoginDays.streak(daysBack(0, 1, 2, 4), TODAY));
        assertEquals(0, LoginDays.streak(daysBack(1, 2), TODAY));
        assertEquals(7, LoginDays.streak(daysBack(0, 1, 2, 3, 4, 5, 6), TODAY));
    }

    @Test
    void streakCrossesMonthBoundary() {
        assertEquals(5, LoginDays.streak(daysBack(0, 1, 2, 3, 4), TODAY)); // 9/29〜10/3
    }

    @Test
    void countWithinIncludesTodayAndExcludesOlderDays() {
        assertEquals(2, LoginDays.countWithin(daysBack(0, 29, 30), TODAY, 30));
        assertEquals(20, LoginDays.countWithin(daysBack(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19), TODAY, 30));
    }
}
