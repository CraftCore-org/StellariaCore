package org.craftcore.stellaria.utils;

import java.time.Instant;
import java.time.ZoneId;

public final class JapanTimeUtil {
    private static final ZoneId JAPAN_TIME = ZoneId.of("Asia/Tokyo");

    private JapanTimeUtil() {
    }

    public static long minecraftTicks(Instant instant) {
        long nanosPerDay = 86_400_000_000_000L;
        long shiftedNanos = Math.floorMod(
                instant.atZone(JAPAN_TIME).toLocalTime().toNanoOfDay() + 64_800_000_000_000L,
                nanosPerDay);
        return shiftedNanos * 24_000L / nanosPerDay;
    }
}
