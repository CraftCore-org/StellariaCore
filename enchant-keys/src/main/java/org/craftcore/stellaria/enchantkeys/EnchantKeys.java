package org.craftcore.stellaria.enchantkeys;

import java.util.List;

/**
 * カスタムエンチャントのキー定数。登録側（StellariaEnchants）と効果側（StellariaCore）の両方に同梱し、
 * 名前のずれを防ぐ。Paper プラグインのクラスを別プラグインから実行時に参照しないよう、共有するのは定数だけにしている。
 */
public final class EnchantKeys {

    public static final String NAMESPACE = "stellaria";

    public static final String SMELTING = "smelting";
    public static final String PURSUIT = "pursuit";
    public static final String REPLANT = "replant";
    public static final String HARVEST = "harvest";
    public static final String GLIDE_BOOST = "glide_boost";
    public static final String LAUNCH = "launch";
    public static final String LIFESTEAL = "lifesteal";
    public static final String LAST_STAND = "last_stand";
    public static final String DOUBLE_JUMP = "double_jump";
    public static final String ANGLER = "angler";

    public static final List<String> ALL = List.of(
            SMELTING, PURSUIT, REPLANT, HARVEST, GLIDE_BOOST,
            LAUNCH, LIFESTEAL, LAST_STAND, DOUBLE_JUMP, ANGLER
    );

    private EnchantKeys() {
    }

    public static String namespaced(String id) {
        return NAMESPACE + ":" + id;
    }
}
