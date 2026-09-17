package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationParserTest {

    @Test
    void rejectsFiniteDurationsThatOverflowDuringParsingOrExpiryCalculation() {
        assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parseSeconds("9223372036854775807d"));
        assertThrows(IllegalArgumentException.class,
                () -> DurationParser.expiresAtMillis(Long.MAX_VALUE - 5L, 1L));
    }

    @Test
    void keepsPermanentDurationDistinctFromFiniteDuration() {
        assertEquals(-1L, DurationParser.expiresAtMillis(100L, -1L));
        assertEquals(1_100L, DurationParser.expiresAtMillis(100L, 1L));
    }
}
