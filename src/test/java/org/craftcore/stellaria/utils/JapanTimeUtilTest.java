package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JapanTimeUtilTest {
    @Test
    void mapsJstSolarMilestonesToMinecraftTicks() {
        assertEquals(18_000L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-17T15:00:00Z")));
        assertEquals(0L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-17T21:00:00Z")));
        assertEquals(6_000L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-18T03:00:00Z")));
        assertEquals(12_000L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-18T09:00:00Z")));
    }

    @Test
    void floorsPartialMinecraftTicksWithoutCrossingTheDayBoundary() {
        assertEquals(18_001L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-17T15:00:04Z")));
        assertEquals(17_999L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-17T14:59:59Z")));
    }
}
