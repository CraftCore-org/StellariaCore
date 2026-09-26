package org.craftcore.stellariaenchants;

import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.ItemTypeKeys;
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemType;
import org.craftcore.stellaria.enchantkeys.EnchantKeys;

import java.util.List;

/**
 * 11 種類のエンチャントの登録内容。出現重みはバニラの目安（10=よく出る、5=普通、2=珍しい、1=とても珍しい）に合わせる。
 * 剣・斧用は #minecraft:enchantable/sharp_weapon（ダメージ増加と同じ対象）を使う。
 * 範囲破壊は treasure 扱いで、テーブル・取引には出ない（古代都市のチェストからのみ入手）。
 */
@SuppressWarnings("UnstableApiUsage")
public final class EnchantDefinitions {

    /** 範囲破壊の対象（ツルハシ・シャベル）。StellariaEnchantsBootstrap で中身を登録する。 */
    public static final TagKey<ItemType> EXCAVATION_ITEMS =
            TagKey.create(RegistryKey.ITEM, Key.key(EnchantKeys.NAMESPACE, "enchantable/excavation"));

    public static final List<EnchantDefinition> ALL = List.of(
            new EnchantDefinition(EnchantKeys.SMELTING, "自動精錬",
                    ItemTypeTagKeys.PICKAXES, null, 1, 2, 15, 0, 4, EquipmentSlotGroup.MAINHAND, true, false),
            new EnchantDefinition(EnchantKeys.PURSUIT, "追撃",
                    ItemTypeTagKeys.ENCHANTABLE_SHARP_WEAPON, null, 3, 5, 10, 10, 2, EquipmentSlotGroup.MAINHAND, false, false),
            new EnchantDefinition(EnchantKeys.REPLANT, "植樹",
                    ItemTypeTagKeys.AXES, null, 1, 5, 10, 0, 2, EquipmentSlotGroup.MAINHAND, false, false),
            new EnchantDefinition(EnchantKeys.HARVEST, "豊穣",
                    ItemTypeTagKeys.HOES, null, 3, 5, 10, 10, 2, EquipmentSlotGroup.MAINHAND, false, false),
            new EnchantDefinition(EnchantKeys.GLIDE_BOOST, "滑空加速",
                    null, ItemTypeKeys.ELYTRA, 3, 2, 15, 10, 4, EquipmentSlotGroup.CHEST, false, false),
            new EnchantDefinition(EnchantKeys.LAUNCH, "跳躍",
                    null, ItemTypeKeys.ELYTRA, 2, 2, 15, 12, 4, EquipmentSlotGroup.CHEST, false, false),
            new EnchantDefinition(EnchantKeys.LIFESTEAL, "吸命",
                    ItemTypeTagKeys.ENCHANTABLE_SHARP_WEAPON, null, 3, 2, 15, 10, 4, EquipmentSlotGroup.MAINHAND, false, false),
            new EnchantDefinition(EnchantKeys.LAST_STAND, "背水",
                    ItemTypeTagKeys.ENCHANTABLE_HEAD_ARMOR, null, 1, 1, 20, 0, 8, EquipmentSlotGroup.HEAD, false, false),
            new EnchantDefinition(EnchantKeys.DOUBLE_JUMP, "二段跳び",
                    ItemTypeTagKeys.ENCHANTABLE_FOOT_ARMOR, null, 2, 2, 15, 12, 4, EquipmentSlotGroup.FEET, false, false),
            new EnchantDefinition(EnchantKeys.ANGLER, "釣り人の粘り",
                    ItemTypeTagKeys.ENCHANTABLE_FISHING, null, 3, 5, 10, 10, 2, EquipmentSlotGroup.MAINHAND, false, false),
            new EnchantDefinition(EnchantKeys.EXCAVATION, "範囲破壊",
                    EXCAVATION_ITEMS, null, 1, 1, 25, 0, 8, EquipmentSlotGroup.MAINHAND, false, true)
    );

    private EnchantDefinitions() {
    }

    /** エンチャントテーブルと村人の取引に出すもの（treasure 以外）。 */
    public static List<EnchantDefinition> discoverable() {
        return ALL.stream().filter(definition -> !definition.treasure()).toList();
    }
}
