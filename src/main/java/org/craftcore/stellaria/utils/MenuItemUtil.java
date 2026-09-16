package org.craftcore.stellaria.utils;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /menu を開くためのコンパス（メニューアイテム）の生成・判定・付与。
 * 実際のエンチャント効果は持たせず、見た目の光沢だけを付ける
 * （{@link ItemMeta#setEnchantmentGlintOverride(Boolean)}）。将来のショップ機能で
 * このアイテムが売却対象にならないよう、PersistentDataContainerにタグを付けておく。
 * スタック不可（1個ずつ）にし、既に持っている場合や連続入手はUtil側で弾く。
 */
public final class MenuItemUtil {

    private MenuItemUtil() {
    }

    /** クールダウン管理用。プレイヤーごとの最終付与時刻（ミリ秒）。 */
    private static final Map<UUID, Long> LAST_GIVEN_AT = new HashMap<>();

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
        meta.setMaxStackSize(1);
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

    public static boolean hasMenuItem(StellariaCore plugin, Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isMenuItem(plugin, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 既に持っている場合は{@code menuitem.already_have}を、クールダウン中なら
     * {@code menuitem.cooldown}を送って何もしない。それ以外は付与して{@code menuitem.given}を送る。
     * `/menuitem`コマンドとメニューGUIの「メニューアイテムを入手」の両方から共通で使う。
     */
    public static void give(StellariaCore plugin, Player player) {
        if (hasMenuItem(plugin, player)) {
            player.sendMessage(plugin.getConfigManager().getMessage("menuitem.already_have", player));
            return;
        }

        long cooldownMillis = Math.max(0, plugin.getConfigManager().getInt("menuitem.cooldown-seconds", 10)) * 1000L;
        long now = System.currentTimeMillis();
        Long lastGiven = LAST_GIVEN_AT.get(player.getUniqueId());
        if (lastGiven != null && now - lastGiven < cooldownMillis) {
            long remainingSeconds = (cooldownMillis - (now - lastGiven) + 999) / 1000;
            player.sendMessage(plugin.getConfigManager().getMessage("menuitem.cooldown", player)
                    .replace("%seconds%", String.valueOf(remainingSeconds)));
            return;
        }

        LAST_GIVEN_AT.put(player.getUniqueId(), now);
        player.getInventory().addItem(create(plugin));
        player.sendMessage(plugin.getConfigManager().getMessage("menuitem.given", player));
    }
}
