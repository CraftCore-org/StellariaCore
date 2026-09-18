package org.craftcore.stellaria.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * GUIのアイコンとして道具・武器・防具などの「実際に使えるアイテム」を使うと、バニラの
 * ツールチップ（攻撃力・耐久などの属性表示）が付いてきてしまう。表示専用アイコンから
 * それらを取り除くためのユーティリティ。
 */
public final class GuiItemUtil {

    private static final TextColor DEFAULT_WHITE = TextColor.color(0xFFFFFF);

    private GuiItemUtil() {
    }

    /** 指定素材のアイコン用ItemStackを、属性等のツールチップを消した状態で作る。 */
    public static ItemStack cleanIcon(Material material) {
        ItemStack item = new ItemStack(material);
        hideExtras(item);
        return item;
    }

    /** 既存のItemStackから、属性・エンチャント等のバニラツールチップをすべて消す。 */
    public static void hideExtras(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
    }

    /**
     * GUIアイテムの表示名や説明文用テキストを Component に変換する。
     * 色指定がない場合は &%f（白）を適用し、バニラ既定の斜体（イタリック）を解除する。
     */
    public static Component text(String text) {
        if (text == null) {
            return Component.empty().decoration(TextDecoration.ITALIC, false);
        }
        String withColor = (text.startsWith("&") || text.startsWith("§")) ? text : "&%f" + text;
        return ColorUtil.component(withColor).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 既存の Component の斜体（イタリック）を解除し、未指定なら白（&%f相当）を適用する。
     */
    public static Component text(Component component) {
        if (component == null) {
            return Component.empty().decoration(TextDecoration.ITALIC, false);
        }
        Component result = component.decoration(TextDecoration.ITALIC, false);
        if (result.color() == null) {
            result = result.color(DEFAULT_WHITE);
        }
        return result;
    }

    /**
     * Component のリスト全体に {@link #text(Component)} を適用する。
     */
    public static List<Component> lore(List<Component> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream().map(GuiItemUtil::text).toList();
    }

    /**
     * 文字列リスト全体に {@link #text(String)} を適用する。
     */
    public static List<Component> loreFromStrings(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream().map(GuiItemUtil::text).toList();
    }
}
