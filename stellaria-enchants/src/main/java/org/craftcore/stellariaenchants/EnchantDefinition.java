package org.craftcore.stellariaenchants;

import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemType;
import org.craftcore.stellaria.enchantkeys.EnchantKeys;
import org.jetbrains.annotations.Nullable;

/**
 * エンチャント 1 種類分の登録内容。対応アイテムはアイテムタグ（itemTag）か単体アイテム（singleItem）のどちらか一方で指定する。
 * 最小コストは「minCostBase + minCostPerLevel × (レベル - 1)」、最大コストは最小コスト + 30。
 * treasure が true のものはエンチャントテーブルと村人の取引に出さず、StellariaCore 側で別の入手手段を用意する。
 */
@SuppressWarnings("UnstableApiUsage")
public record EnchantDefinition(
        String id,
        String displayName,
        @Nullable TagKey<ItemType> itemTag,
        @Nullable TypedKey<ItemType> singleItem,
        int maxLevel,
        int weight,
        int minCostBase,
        int minCostPerLevel,
        int anvilCost,
        EquipmentSlotGroup slot,
        boolean exclusiveWithSilkTouch,
        boolean treasure
) {

    public TypedKey<Enchantment> typedKey() {
        return TypedKey.create(RegistryKey.ENCHANTMENT, Key.key(EnchantKeys.NAMESPACE, id));
    }
}
