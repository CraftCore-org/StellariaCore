package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailRideRecordTest {

    private static final List<String> LINE = List.of("Spawn", "Village", "Mine", "Port");

    @Test
    void endToEndIsFullLineBothWaysOnTwoWayLine() {
        assertTrue(RailRideRecord.isFullLine(LINE, false, "spawn", "Port"));
        assertTrue(RailRideRecord.isFullLine(LINE, false, "Port", "Spawn"));
    }

    @Test
    void oneWayLineCountsOnlyForward() {
        assertTrue(RailRideRecord.isFullLine(LINE, true, "Spawn", "Port"));
        assertFalse(RailRideRecord.isFullLine(LINE, true, "Port", "Spawn"));
    }

    @Test
    void partialRidesAndUnknownDepartureDoNotCount() {
        assertFalse(RailRideRecord.isFullLine(LINE, false, "Village", "Port"));
        assertFalse(RailRideRecord.isFullLine(LINE, false, null, "Port"));
        assertFalse(RailRideRecord.isFullLine(List.of("Solo"), false, "Solo", "Solo"));
    }
}
