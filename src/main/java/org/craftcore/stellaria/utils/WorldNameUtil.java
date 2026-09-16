package org.craftcore.stellaria.utils;

import org.bukkit.World;
import org.craftcore.stellaria.managers.ConfigManager;

/** ワールド名の表示用設定を扱うユーティリティ。 */
public final class WorldNameUtil {

    private WorldNameUtil() {
    }

    /**
     * config.yml の world.display-names で設定された表示名を返す。未設定時はBukkit上のワールド名を返す。
     */
    public static String displayName(ConfigManager config, World world) {
        String worldName = world.getName();
        String displayName = config.getString("world.display-names." + worldName, "", true);
        return displayName.isEmpty() ? worldName : displayName;
    }
}
