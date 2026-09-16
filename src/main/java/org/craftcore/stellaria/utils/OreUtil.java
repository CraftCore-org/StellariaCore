package org.craftcore.stellaria.utils;

import org.bukkit.Material;

/**
 * 鉱石ブロックの判定に使う静的ヘルパー。MineManager/MineListenerから使う。
 */
public final class OreUtil {

    private OreUtil() {
    }

    /** 鉱石ブロックか（深層岩・ネザー含む全種）。バニラに「全鉱石共通タグ」は存在しないため名前判定する。 */
    public static boolean isOre(Material material) {
        return material.name().endsWith("_ORE") || material == Material.ANCIENT_DEBRIS;
    }
}
