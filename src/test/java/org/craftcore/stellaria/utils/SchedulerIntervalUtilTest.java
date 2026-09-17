package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulerIntervalUtilTest {

    @Test
    void clampsNonPositiveIntervalsAndUsesLongForMinuteConversion() {
        assertEquals(1L, SchedulerIntervalUtil.ticks(0));
        assertEquals(1L, SchedulerIntervalUtil.ticks(-5));
        assertEquals(1L, SchedulerIntervalUtil.minutesToTicks(-1));
        assertEquals(2_576_980_376_400L, SchedulerIntervalUtil.minutesToTicks(Integer.MAX_VALUE));
        assertEquals(1L, SchedulerIntervalUtil.secondsToTicks(0));
        assertEquals(300L, SchedulerIntervalUtil.secondsToTicks(15));
    }
}
