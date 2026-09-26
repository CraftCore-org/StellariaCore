package org.craftcore.stellaria.enchants.premium;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.craftcore.stellaria.managers.ConfigManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * 上質作物のアイテム。見た目はバニラの作物と同じ Material に、名前・説明文・エンチャントの輝き・識別用 PDC タグを付けたもの。
 * 判定は PDC タグで行うため、/stellariareload で名前を変えても既存のアイテムは上質作物のまま扱われる。
 */
public final class PremiumCrops {

    public static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT, Material.NETHER_WART);
    public static final int CONVERSION_RATIO = 3;

    private final ConfigManager config;
    private final NamespacedKey markerKey;

    public PremiumCrops(Plugin plugin, ConfigManager config) {
        this.config = config;
        this.markerKey = new NamespacedKey(plugin, "premium_crop");
    }

    public boolean isPremium(@Nullable ItemStack item) {
        return item != null
                && !item.isEmpty()
                && CROPS.contains(item.getType())
                && item.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    public ItemStack create(Material crop, int amount) {
        ItemStack stack = new ItemStack(crop, amount);
        String cropName = config.getRawMessage("custom-enchants.premium-crop.crop-names." + crop.name());
        String name = config.getRawMessage("custom-enchants.premium-crop.name").replace("%crop%", cropName);
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            meta.setEnchantmentGlintOverride(true);
            meta.displayName(ColorUtil.component(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(config.getMessageList("custom-enchants.premium-crop.lore").stream()
                    .map(line -> ColorUtil.component(line).decoration(TextDecoration.ITALIC, false))
                    .toList());
        });
        return stack;
    }
}
