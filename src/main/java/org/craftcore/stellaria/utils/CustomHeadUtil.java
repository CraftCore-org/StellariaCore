package org.craftcore.stellaria.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.craftcore.stellaria.StellariaCore;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/** customhead.ymlで定義したbase64テクスチャから、GUI用のプレイヤーヘッドを生成する。 */
public final class CustomHeadUtil {

    private CustomHeadUtil() {
    }

    /** 指定IDのテクスチャ値を返す。未登録・空値は空として扱う。 */
    public static Optional<String> resolveTexture(@Nullable ConfigurationSection heads, String id) {
        if (heads == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        String texture = heads.getString(id + ".texture");
        if (texture == null || texture.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(texture.trim());
    }

    /** 指定IDのカスタムヘッドを作る。定義が無効な場合はnullを返す。 */
    public static @Nullable ItemStack create(StellariaCore plugin, String id) {
        ConfigurationSection heads = plugin.getConfigManager().get("customhead.yml").get()
                .getConfigurationSection("heads");
        Optional<String> texture = resolveTexture(heads, id);
        if (texture.isEmpty()) {
            return null;
        }

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        UUID profileId = UUID.nameUUIDFromBytes(("stellaria:customhead:" + id + ":" + texture.get())
                .getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(profileId);
        profile.setProperty(new ProfileProperty("textures", texture.get()));
        meta.setPlayerProfile(profile);
        item.setItemMeta(meta);
        return item;
    }
}
