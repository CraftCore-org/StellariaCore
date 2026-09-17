package org.craftcore.stellaria.utils;

import net.kyori.adventure.text.Component;

import java.util.Arrays;
import java.util.List;

/** 手持ちアイテムの名前・説明文に使うコマンド入力を Adventure Component に変換する。 */
public final class ItemTextUtil {

    private ItemTextUtil() {
    }

    /** リテラルの {@code \n} を改行として扱い、各行のカラーコードを変換する。 */
    public static List<Component> loreComponents(String input) {
        return Arrays.stream(input.split("\\\\n", -1))
                .map(ColorUtil::component)
                .toList();
    }

    /** アイテム名のカラーコードを変換する。 */
    public static Component nameComponent(String input) {
        return ColorUtil.component(input);
    }
}
