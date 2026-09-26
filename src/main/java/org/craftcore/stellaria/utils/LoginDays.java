package org.craftcore.stellaria.utils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/** 連続ログイン日数と、直近 N 日のうちログインした日数の計算。日付は日本時間。 */
public final class LoginDays {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private LoginDays() {
    }

    public static LocalDate today() {
        return LocalDate.now(JAPAN);
    }

    /** today を含めて、さかのぼって連続している日数。today にログインしていなければ 0。 */
    public static int streak(Set<LocalDate> days, LocalDate today) {
        int count = 0;
        LocalDate day = today;
        while (days.contains(day)) {
            count++;
            day = day.minusDays(1);
        }
        return count;
    }

    /** today を含む直近 windowDays 日のうち、ログインした日数。 */
    public static int countWithin(Set<LocalDate> days, LocalDate today, int windowDays) {
        LocalDate from = today.minusDays(windowDays - 1L);
        return (int) days.stream().filter(day -> !day.isBefore(from) && !day.isAfter(today)).count();
    }
}
