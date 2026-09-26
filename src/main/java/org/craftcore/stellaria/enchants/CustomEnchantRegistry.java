package org.craftcore.stellaria.enchants;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * StellariaEnchants が登録したエンチャントをキーで引いて保持する。見つからないエンチャントは無効扱いにし、
 * StellariaEnchants が導入されていない場合でも本体の起動を止めない。
 */
public final class CustomEnchantRegistry {

    private final Map<CustomEnchant, Enchantment> resolved = new EnumMap<>(CustomEnchant.class);
    private final List<String> missingIds = new ArrayList<>();

    private CustomEnchantRegistry() {
    }

    public static CustomEnchantRegistry fromServer(Logger logger) {
        Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        return resolve(registry::get, logger);
    }

    static CustomEnchantRegistry resolve(Function<NamespacedKey, Enchantment> lookup, Logger logger) {
        CustomEnchantRegistry result = new CustomEnchantRegistry();
        for (CustomEnchant enchant : CustomEnchant.values()) {
            Enchantment enchantment = lookup.apply(enchant.key());
            if (enchantment == null) {
                result.missingIds.add(enchant.id());
            } else {
                result.resolved.put(enchant, enchantment);
            }
        }
        if (result.resolved.isEmpty()) {
            logger.warning("StellariaEnchants が導入されていないため、カスタムエンチャントを無効化します。");
        } else if (!result.missingIds.isEmpty()) {
            logger.warning("次のカスタムエンチャントが見つからないため無効化します: " + String.join(", ", result.missingIds));
        }
        return result;
    }

    public boolean isAvailable(CustomEnchant enchant) {
        return resolved.containsKey(enchant);
    }

    public boolean anyAvailable() {
        return !resolved.isEmpty();
    }

    public List<String> missingIds() {
        return Collections.unmodifiableList(missingIds);
    }

    /** アイテムに付いているレベル。アイテムが無い・エンチャントが無効なら 0。 */
    public int level(@Nullable ItemStack item, CustomEnchant enchant) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        Enchantment enchantment = resolved.get(enchant);
        return enchantment == null ? 0 : item.getEnchantmentLevel(enchantment);
    }
}
