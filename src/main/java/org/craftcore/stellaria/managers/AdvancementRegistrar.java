package org.craftcore.stellaria.managers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.AdvancementJson;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 独自進捗をバニラの進捗としてメモリ上に登録する。
 * Paper 1.21.11 の loadAdvancement は datapacks/bukkit/data/&lt;ns&gt;/advancements/（複数形）に JSON を書くが、
 * 1.21 以降のバニラは単数形の advancement/ しか読まないため、登録は再起動やデータパックの再読み込みで消える。
 * そのため起動時とデータパックの再読み込み後に、まだ登録されていないものをすべて登録する。
 * removeAdvancement もファイルを消すだけでメモリ上の進捗は消えないので使わない。
 */
public class AdvancementRegistrar {

    private static final Function<String, JsonElement> TEXT =
            text -> JsonParser.parseString(GsonComponentSerializer.gson().serialize(ColorUtil.component(text)));

    private final StellariaCore plugin;

    public AdvancementRegistrar(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("deprecation")
    public void register(AdvancementDefinitions.Parsed parsed, Predicate<AdvancementDefinitions.Difficulty> announce) {
        LinkedHashMap<String, JsonObject> desired = new LinkedHashMap<>();
        for (AdvancementDefinitions.Tab tab : parsed.tabs().values()) {
            desired.put(AdvancementJson.rootPath(tab.id()), AdvancementJson.root(tab, TEXT));
        }
        for (AdvancementDefinitions.Definition def : parsed.definitions()) {
            desired.put(AdvancementJson.path(def.tab(), def.id()),
                    AdvancementJson.advancement(def, announce.test(def.difficulty()), TEXT));
        }
        List<String> toLoad = AdvancementJson.missing(List.copyOf(desired.keySet()), existingPaths());
        int loaded = 0;
        for (String path : toLoad) {
            try {
                Bukkit.getUnsafe().loadAdvancement(key(path), desired.get(path).toString());
                loaded++;
            } catch (RuntimeException e) {
                plugin.getLogger().warning("進捗 " + path + " の登録に失敗しました（GUI と報酬は動きます）: " + e.getMessage());
            }
        }
        if (loaded > 0) {
            plugin.getLogger().info("独自進捗: " + loaded + " 件をバニラの進捗画面に登録しました。");
        }
    }

    private static Set<String> existingPaths() {
        Set<String> paths = new HashSet<>();
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            NamespacedKey key = it.next().getKey();
            if (key.getNamespace().equals(AdvancementJson.NAMESPACE)) {
                paths.add(key.getKey());
            }
        }
        return paths;
    }

    public static NamespacedKey key(String path) {
        return new NamespacedKey(AdvancementJson.NAMESPACE, path);
    }
}
