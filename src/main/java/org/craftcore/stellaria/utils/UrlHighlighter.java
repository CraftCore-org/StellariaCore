package org.craftcore.stellaria.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * プレーンテキスト中のURL（http/https）を検出して、クリックでブラウザが開くリンクに変換する。
 * 日本語の句読点・閉じ括弧はURLの一部として巻き込まないように除外している
 * （例: 「見てね(https://example.com)」の閉じ括弧まで拾わないようにする）。
 */
public final class UrlHighlighter {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s、。」』】)\\]]+");

    private UrlHighlighter() {
    }

    /**
     * {@code text} 内のURLをクリック可能なリンク（青色＋下線）に変換したComponentを返す。
     * URL以外の部分は {@code colorize} が true なら {@link ColorUtil#component(String)} で
     * 色変換し、false ならリテラルのまま扱う（チャットの色コード権限に対応するため）。
     *
     * @param hoverTemplate リンクにホバーした時のツールチップ文字列（{@code %url%} をURLに置換して使う）。
     *                      {@code null}または空文字ならホバーイベントを付けない。
     */
    public static Component highlight(String text, boolean colorize, String hoverTemplate) {
        Matcher matcher = URL_PATTERN.matcher(text);
        Component result = Component.empty();
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                result = result.append(literal(text.substring(lastEnd, matcher.start()), colorize));
            }
            String url = matcher.group();
            Component link = Component.text(url)
                    .color(NamedTextColor.BLUE)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(url));
            if (hoverTemplate != null && !hoverTemplate.isEmpty()) {
                Component hover = ColorUtil.component(hoverTemplate.replace("%url%", url));
                link = link.hoverEvent(HoverEvent.showText(hover));
            }
            result = result.append(link);
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            result = result.append(literal(text.substring(lastEnd), colorize));
        }
        return result;
    }

    private static Component literal(String text, boolean colorize) {
        return colorize ? ColorUtil.component(text) : Component.text(text);
    }
}
