package org.craftcore.stellaria.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Arrays;
import java.util.List;

/** 手持ちアイテムの名前・説明文に使うコマンド入力を Adventure Component に変換する。 */
public final class ItemTextUtil {

    private static final Style DEFAULT_ITEM_TEXT_STYLE = Style.style(
            TextColor.color(0xFFFFFF), TextDecoration.ITALIC.withState(false));

    private ItemTextUtil() {
    }

    /** リテラルの {@code \n} を改行として扱い、各行のカラーコードを変換する。 */
    public static List<Component> loreComponents(String input) {
        return Arrays.stream(input.split("\\\\n", -1))
                .map(ItemTextUtil::withDefaultStyle)
                .toList();
    }

    /** アイテム名のカラーコードを変換する。 */
    public static Component nameComponent(String input) {
        return withDefaultStyle(input);
    }

    private static Component withDefaultStyle(String input) {
        Component component = ColorUtil.component(input);
        return component.style(component.style().merge(DEFAULT_ITEM_TEXT_STYLE, Style.Merge.Strategy.IF_ABSENT_ON_TARGET));
    }
}
