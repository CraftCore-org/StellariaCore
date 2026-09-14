package org.craftcore.stellaria.utils;

import java.util.Locale;

/**
 * 金額を「万」「億」「兆」単位の短い表記に変換する（例: {@code 15000} -> {@code "1.5万"}、
 * {@code 250000000} -> {@code "2.5億"}、{@code 1000000000000} -> {@code "1兆"}）。
 * orelia-serverutil の {@code MoneyFormat}（k/m/b/t表記）を移植し、単位だけ日本語に変更したもの。
 */
public final class MoneyFormat {

    private MoneyFormat() {
    }

    public static String format(double amount) {
        double abs = Math.abs(amount);
        String sign = amount < 0 ? "-" : "";
        if (abs >= 1_000_000_000_000.0) {
            return sign + trimTrailingZero(abs / 1_000_000_000_000.0) + "兆";
        }
        if (abs >= 100_000_000.0) {
            return sign + trimTrailingZero(abs / 100_000_000.0) + "億";
        }
        if (abs >= 10_000.0) {
            return sign + trimTrailingZero(abs / 10_000.0) + "万";
        }
        return sign + trimTrailingZero(abs);
    }

    private static String trimTrailingZero(double value) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
    }
}
