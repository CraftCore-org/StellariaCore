package org.craftcore.stellaria.utils;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** /ranking の値の表示形式と、自分の順位の計算。 */
public final class RankingFormat {

    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.ROOT);

    private RankingFormat() {
    }

    /** distance は cm で受け取り、1 km 未満は m（切り捨て）、それ以上は km（小数 1 桁）で返す。 */
    public static String value(String statKey, long value) {
        if ("distance".equals(statKey)) {
            long meters = value / 100L;
            if (meters < 1_000L) {
                return new DecimalFormat("#,##0", SYMBOLS).format(meters) + " m";
            }
            return new DecimalFormat("#,##0.0", SYMBOLS).format(value / 100_000.0) + " km";
        }
        return new DecimalFormat("#,##0", SYMBOLS).format(value);
    }

    /** 自分より値が大きい公開プレイヤーの数から順位を求める。同じ値のプレイヤーは同じ順位になる。 */
    public static long rank(long publicCountAbove) {
        return publicCountAbove + 1L;
    }

    /** 母数。自分が非公開なら公開人数に自分を足す。 */
    public static long total(long publicCount, boolean selfHidden) {
        return selfHidden ? publicCount + 1L : publicCount;
    }
}
