package org.craftcore.stellaria.utils;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.craftcore.stellaria.StellariaCore;

/**
 * /menu を開くためのコンパス（メニューアイテム）の生成・判定。
 * 実際のエンチャント効果は持たせず、見た目の光沢だけを付ける
 * （{@link ItemMeta#setEnchantmentGlintOverride(Boolean)}）。将来のショップ機能で
 * このアイテムが売却対象にならないよう、PersistentDataContainerにタグを付けておく。
 */
public final class MenuItemUtil {

    private MenuItemUtil() {
    }

    private static NamespacedKey key(StellariaCore plugin) {
        return new NamespacedKey(plugin, "menu_item");
    }

    public static ItemStack create(StellariaCore plugin) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtil.component(plugin.getConfigManager().getMessage("menuitem.name", null)));
        meta.lore(plugin.getConfigManager().getMessageList("menuitem.lore").stream()
                .map(ColorUtil::component)
                .toList());
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** 将来のショップ機能で、このアイテムを売却対象から除外するために使う判定。 */
    public static boolean isMenuItem(StellariaCore plugin, ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(key(plugin), PersistentDataType.BYTE);
    }
}
