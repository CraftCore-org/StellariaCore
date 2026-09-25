package org.craftcore.stellaria.enchants;

import org.bukkit.NamespacedKey;
import org.craftcore.stellaria.enchantkeys.EnchantKeys;

/** StellariaEnchants が登録するカスタムエンチャント。ID は EnchantKeys と共有する。 */
public enum CustomEnchant {
    SMELTING(EnchantKeys.SMELTING),
    PURSUIT(EnchantKeys.PURSUIT),
    REPLANT(EnchantKeys.REPLANT),
    HARVEST(EnchantKeys.HARVEST),
    GLIDE_BOOST(EnchantKeys.GLIDE_BOOST),
    LAUNCH(EnchantKeys.LAUNCH),
    LIFESTEAL(EnchantKeys.LIFESTEAL),
    LAST_STAND(EnchantKeys.LAST_STAND),
    DOUBLE_JUMP(EnchantKeys.DOUBLE_JUMP),
    ANGLER(EnchantKeys.ANGLER);

    private final String id;

    CustomEnchant(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public NamespacedKey key() {
        return new NamespacedKey(EnchantKeys.NAMESPACE, id);
    }
}
