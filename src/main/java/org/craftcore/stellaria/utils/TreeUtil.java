package org.craftcore.stellaria.utils;

import org.bukkit.Material;

/**
 * 丸太・葉っぱブロックの判定に使う静的ヘルパー。KikoriManager/KikoriListenerから使う。
 */
public final class TreeUtil {

    private TreeUtil() {
    }

    /** 丸太・木材ブロックか（樹種問わず）。皮むき済みも含む。 */
    public static boolean isLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD");
    }

    /** バニラの自然生成には存在しない「皮むき丸太/木材」か。常に人工物として扱う対象。 */
    public static boolean isStrippedLog(Material material) {
        return material.name().startsWith("STRIPPED_");
    }

    /** 葉っぱブロックか。 */
    public static boolean isLeaves(Material material) {
        return material.name().endsWith("_LEAVES");
    }
}
