package org.craftcore.stellaria.utils;

import org.bukkit.World;
import org.craftcore.stellaria.managers.ConfigManager;

import java.util.List;
import java.util.Map;

/** ワールド名の表示用設定を扱うユーティリティ。 */
public final class WorldNameUtil {

    private WorldNameUtil() {
    }

    /**
     * config.yml の world.display-names または world.worlds / world.items で設定された表示名を返す。
     * 未設定時はBukkit上のワールド名を返す。
     */
    public static String displayName(ConfigManager config, World world) {
        return displayName(config, world.getName());
    }

    /**
     * config.yml の world.display-names または world.worlds / world.items で設定された表示名を返す。
     * 未設定時は指定されたワールド名を返す。
     */
    public static String displayName(ConfigManager config, String worldName) {
        String displayName = config.getString("world.display-names." + worldName, "", true);
        if (!displayName.isEmpty()) {
            return displayName;
        }

        List<?> rawList = config.get("config.yml").get().getList("world.worlds");
        if (rawList == null || rawList.isEmpty()) {
            rawList = config.get("config.yml").get().getList("world.items");
        }
        if (rawList != null) {
            for (Object obj : rawList) {
                if (obj instanceof Map<?, ?> map) {
                    Object w = map.get("world");
                    if (worldName.equals(w)) {
                        Object n = map.get("name");
                        if (n == null) {
                            n = map.get("display-name");
                        }
                        if (n != null && !String.valueOf(n).isBlank()) {
                            return String.valueOf(n);
                        }
                    }
                }
            }
        }

        return worldName;
    }
}
