package org.craftcore.stellaria.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "10m"/"1h"/"3d"/"perm" のような相対時間文字列をパースするユーティリティ。
 * ミュートコマンドの期限指定・残り時間表示で使う。単位の複合指定（"1h30m"等）は非対応。
 */
public final class DurationParser {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    /**
     * 期間文字列を秒数に変換する。"perm"/"permanent" は永久を表す -1 を返す。
     *
     * @throws IllegalArgumentException 形式が不正な場合
     */
    public static long parseSeconds(String input) {
        if (input == null) {
            throw new IllegalArgumentException("期間が指定されていません");
        }
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("perm") || trimmed.equalsIgnoreCase("permanent")) {
            return -1;
        }
        Matcher matcher = PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("期間の形式が不正です: " + input);
        }
        try {
            long value = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2).toLowerCase()) {
                case "s" -> value;
                case "m" -> Math.multiplyExact(value, 60L);
                case "h" -> Math.multiplyExact(value, 3600L);
                case "d" -> Math.multiplyExact(value, 86400L);
                default -> throw new IllegalArgumentException("期間の形式が不正です: " + input);
            };
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("期間が長すぎます: " + input, e);
        }
    }

    /** 相対秒数からepoch millisecondsの期限を求める。永久指定は負の値を返す。 */
    public static long expiresAtMillis(long nowMillis, long seconds) {
        if (seconds < 0) {
            return -1L;
        }
        try {
            return Math.addExact(nowMillis, Math.multiplyExact(seconds, 1000L));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("期間が長すぎます", e);
        }
    }

    /**
     * 有効期限（epoch millis、永久は負の値）から、現在時刻までの残り時間を最大の単位1つで整形する。
     * 例: "3日" "5時間" "12分" "45秒"。既に期限切れなら "0秒"。
     */
    public static String formatRemaining(long expiresAtMillis) {
        if (expiresAtMillis < 0) {
            return "永久";
        }
        long remainingSeconds = Math.max(0, (expiresAtMillis - System.currentTimeMillis()) / 1000L);
        if (remainingSeconds >= 86400) {
            return (remainingSeconds / 86400) + "日";
        } else if (remainingSeconds >= 3600) {
            return (remainingSeconds / 3600) + "時間";
        } else if (remainingSeconds >= 60) {
            return (remainingSeconds / 60) + "分";
        } else {
            return remainingSeconds + "秒";
        }
    }

    /**
     * 秒数を上位2つの単位で人間可読に整形する（プレイ時間・経過時間の表示用）。
     * 例: {@code 187265} -> {@code "2日4時間"}、{@code 3725} -> {@code "1時間2分"}、
     * {@code 65} -> {@code "1分5秒"}、{@code 5} -> {@code "5秒"}。
     * {@link #formatRemaining(long)}（残り時間、単位1つだけ）とは別物。
     */
    public static String formatDuration(long totalSeconds) {
        long seconds = Math.max(0, totalSeconds);
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (days > 0) {
            return days + "日" + hours + "時間";
        } else if (hours > 0) {
            return hours + "時間" + minutes + "分";
        } else if (minutes > 0) {
            return minutes + "分" + secs + "秒";
        } else {
            return secs + "秒";
        }
    }
}
