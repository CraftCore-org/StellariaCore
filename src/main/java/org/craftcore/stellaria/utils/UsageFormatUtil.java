package org.craftcore.stellaria.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * コマンド使用方法メッセージ（例: "/land rule <pvp|explosions|doors|chests> <on|off|default>"）を
 * 読みやすく整形するユーティリティ。&lt;、&gt;、|が本文と同じ色でぴったり詰めて書かれていると
 * どこで区切れているか視認しづらいため、これらの記号だけグレー（&%7）に色分けし、
 * |の前後にスペースを入れる。
 *
 * ColorUtilによる&amp;%&lt;char&gt;展開より前の生文字列に対して使うこと
 * （{@code ConfigManager#getRawMessage}の戻り値が対象）。{@code getMessage}の戻り値は
 * 既に色コードが§に展開済みのため、このUtilを通しても記号がグレーに色分けされない。
 */
public final class UsageFormatUtil {

    private static final Pattern COLOR_TOKEN_PATTERN = Pattern.compile("&%(.)");
    private static final String GRAY_TOKEN = "&%7";
    private static final String DEFAULT_BASE_TOKEN = "&%f";

    private UsageFormatUtil() {
    }

    /**
     * usage文字列中の最初の&amp;%&lt;char&gt;色コードを「基準色」として検出し、記号の前後は
     * その色に戻す形で&lt;・&gt;・|だけグレーに塗り分ける。基準色が見つからなければ白（&%f）を使う。
     */
    public static String format(String usage) {
        if (usage == null || usage.isEmpty()) {
            return usage;
        }
        String baseToken = detectBaseToken(usage);
        StringBuilder result = new StringBuilder(usage.length() + 16);
        for (int i = 0; i < usage.length(); i++) {
            char c = usage.charAt(i);
            switch (c) {
                case '<', '>' -> result.append(GRAY_TOKEN).append(c).append(baseToken);
                case '|' -> result.append(' ').append(GRAY_TOKEN).append('|').append(baseToken).append(' ');
                default -> result.append(c);
            }
        }
        // 元の文字列側に既にスペースが入っていた場合の二重スペース対策。
        return result.toString().replaceAll(" {2,}", " ");
    }

    private static String detectBaseToken(String text) {
        Matcher matcher = COLOR_TOKEN_PATTERN.matcher(text);
        return matcher.find() ? "&%" + matcher.group(1) : DEFAULT_BASE_TOKEN;
    }
}
