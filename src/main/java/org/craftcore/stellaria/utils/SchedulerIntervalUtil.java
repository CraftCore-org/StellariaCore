package org.craftcore.stellaria.utils;

/** 設定由来のスケジューラ間隔を、Folia が受け取れる正の tick 数へ正規化する。 */
public final class SchedulerIntervalUtil {

    private SchedulerIntervalUtil() {
    }

    public static long ticks(int configuredTicks) {
        return Math.max(1L, configuredTicks);
    }

    public static long minutesToTicks(int configuredMinutes) {
        return Math.max(1L, (long) configuredMinutes * 60L * 20L);
    }

    public static long secondsToTicks(int configuredSeconds) {
        return Math.max(1L, (long) configuredSeconds * 20L);
    }
}
