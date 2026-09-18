package org.craftcore.stellaria.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemTextUtilTest {

    @Test
    void splitsLiteralNewlinesAndColorizesEveryLoreLine() {
        List<Component> lore = ItemTextUtil.loreComponents("&%a最初の行\\n&a次の行");

        assertEquals(List.of(
                Component.text("最初の行").color(TextColor.color(0x96EBB5)).decoration(TextDecoration.ITALIC, false),
                Component.text("次の行").color(TextColor.color(0x55FF55)).decoration(TextDecoration.ITALIC, false)
        ), lore);
    }

    @Test
    void preservesBlankLinesBetweenLiteralNewlines() {
        assertEquals(3, ItemTextUtil.loreComponents("一行目\\n\\n三行目").size());
    }

    @Test
    void colorizesItemNames() {
        assertEquals(Component.text("名称").color(TextColor.color(0x96EBB5)).decoration(TextDecoration.ITALIC, false),
                ItemTextUtil.nameComponent("&%a名称"));
    }

    @Test
    void givesPlainNamesAndLoreWhiteNonItalicDefaults() {
        assertEquals(Component.text("名称")
                        .color(TextColor.color(0xFFFFFF))
                        .decoration(TextDecoration.ITALIC, false),
                ItemTextUtil.nameComponent("名称"));
        assertEquals(List.of(Component.text("説明")
                        .color(TextColor.color(0xFFFFFF))
                        .decoration(TextDecoration.ITALIC, false)),
                ItemTextUtil.loreComponents("説明"));
    }

    @Test
    void preservesExplicitCustomColor() {
        assertEquals(TextColor.color(0x96EBB5), ItemTextUtil.nameComponent("&%a名称").color());
    }

    @Test
    void preservesExplicitItalicDecoration() {
        assertEquals(TextDecoration.State.TRUE, ItemTextUtil.nameComponent("&o名称").decoration(TextDecoration.ITALIC));
    }
}
