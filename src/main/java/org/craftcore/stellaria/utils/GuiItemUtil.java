package org.craftcore.stellaria.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * GUIのアイコンとして道具・武器・防具などの「実際に使えるアイテム」を使うと、バニラの
 * ツールチップ（攻撃力・耐久などの属性表示）が付いてきてしまう。表示専用アイコンから
 * それらを取り除くためのユーティリティ。
 */
public final class GuiItemUtil {

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
}
