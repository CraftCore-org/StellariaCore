package org.craftcore.stellaria.managers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.file.YamlConfiguration;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.AdvancementJson;
import org.craftcore.stellaria.utils.ColorUtil;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 独自進捗をバニラの進捗として登録する。登録内容はメインワールドのデータパック（bukkit）に保存されて
 * 再起動後も残るため、前回登録した内容のハッシュを advancements-registered.yml に保存し、
 * 変わった進捗（とその子孫）だけを登録し直す。登録し直すと、達成済みの人には次のログインで
 * もう一度トーストが出るため、変更のない進捗は触らない。
 */
public class AdvancementRegistrar {

    private static final String HASH_FILE = "advancements-registered.yml";
    private static final Function<String, JsonElement> TEXT =
            text -> JsonParser.parseString(GsonComponentSerializer.gson().serialize(ColorUtil.component(text)));

    private final StellariaCore plugin;

    public AdvancementRegistrar(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("deprecation")
    public void register(AdvancementDefinitions.Parsed parsed, Predicate<AdvancementDefinitions.Difficulty> announce) {
        LinkedHashMap<String, JsonObject> desired = new LinkedHashMap<>();
        Map<String, String> parentOf = new HashMap<>();
        for (AdvancementDefinitions.Tab tab : parsed.tabs().values()) {
            desired.put(AdvancementJson.rootPath(tab.id()), AdvancementJson.root(tab, TEXT));
        }
        for (AdvancementDefinitions.Definition def : parsed.definitions()) {
            String path = AdvancementJson.path(def.tab(), def.id());
            desired.put(path, AdvancementJson.advancement(def, announce.test(def.difficulty()), TEXT));
            parentOf.put(path, AdvancementJson.parentPath(def));
        }
        LinkedHashMap<String, String> desiredHashes = new LinkedHashMap<>();
        desired.forEach((path, json) -> desiredHashes.put(path, AdvancementJson.hash(json)));

        File hashFile = new File(plugin.getDataFolder(), HASH_FILE);
        YamlConfiguration stored = YamlConfiguration.loadConfiguration(hashFile);
        Map<String, String> storedHashes = new HashMap<>();
        for (String path : stored.getKeys(true)) {
            if (stored.isString(path)) {
                storedHashes.put(path.replace('.', '/'), stored.getString(path));
            }
        }

        AdvancementJson.Plan plan = AdvancementJson.plan(desiredHashes, parentOf, storedHashes, existingPaths());
        for (String path : plan.remove()) {
            try {
                Bukkit.getUnsafe().removeAdvancement(key(path));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("進捗 " + path + " の削除に失敗しました: " + e.getMessage());
            }
        }
        int loaded = 0;
        for (String path : plan.load()) {
            try {
                Bukkit.getUnsafe().loadAdvancement(key(path), desired.get(path).toString());
                loaded++;
            } catch (RuntimeException e) {
                plugin.getLogger().warning("進捗 " + path + " の登録に失敗しました（GUI と報酬は動きます）: " + e.getMessage());
            }
        }

        YamlConfiguration out = new YamlConfiguration();
        for (Map.Entry<String, String> entry : desiredHashes.entrySet()) {
            if (Bukkit.getAdvancement(key(entry.getKey())) != null) {
                out.set(entry.getKey().replace('/', '.'), entry.getValue());
            }
        }
        try {
            out.save(hashFile);
        } catch (IOException e) {
            plugin.getLogger().warning(HASH_FILE + " を保存できませんでした: " + e.getMessage());
        }
        if (!plan.remove().isEmpty() || loaded > 0) {
            plugin.getLogger().info("独自進捗: " + loaded + " 件を登録し、" + plan.remove().size() + " 件を削除しました。");
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
