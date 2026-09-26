package org.craftcore.stellaria.enchants;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 範囲破壊の入手手段。範囲破壊はエンチャントテーブル・取引に出ない（StellariaEnchants 側で treasure 扱い）ため、
 * 古代都市のチェストが生成されたときだけ、設定した確率で範囲破壊の本を 1 冊加える。氷室のチェストは対象外。
 */
public final class AncientCityLootListener implements Listener {

    private static final NamespacedKey ANCIENT_CITY = NamespacedKey.minecraft("chests/ancient_city");

    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;

    public AncientCityLootListener(CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.registry = registry;
        this.config = config;
    }

    static boolean shouldAddBook(NamespacedKey lootTable, double chance, double roll) {
        return ANCIENT_CITY.equals(lootTable) && roll < chance;
    }

    @EventHandler(ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        Enchantment excavation = registry.enchantment(CustomEnchant.EXCAVATION);
        if (excavation == null
                || !shouldAddBook(event.getLootTable().getKey(), config.excavationAncientCityChance(),
                        ThreadLocalRandom.current().nextDouble())) {
            return;
        }
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, meta -> meta.addStoredEnchant(excavation, 1, true));
        event.getLoot().add(book);
    }
}
