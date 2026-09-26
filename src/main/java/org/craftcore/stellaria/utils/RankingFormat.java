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

    /**
     * 降順に並んだ 1 ページ分の値に順位を振る。同じ値は同じ順位にし、その次は人数ぶん飛ばす（1, 2, 2, 4）。
     *
     * @param offset    このページの先頭が全体で何番目か（0 始まり）
     * @param firstRank 先頭の値の順位（自分より値が大きい公開プレイヤーの数 + 1）。前ページから同順位が続く場合に効く
     */
    public static long[] competitionRanks(long[] values, int offset, long firstRank) {
        long[] ranks = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            ranks[i] = i == 0 ? firstRank
                    : values[i] == values[i - 1] ? ranks[i - 1] : offset + i + 1L;
        }
        return ranks;
    }

    /** 母数。自分が publicCount に含まれていない（非公開、またはまだ記録がない）なら自分を足す。 */
    public static long totalWithSelf(long publicCount, boolean selfCounted) {
        return selfCounted ? publicCount : publicCount + 1L;
    }
}
