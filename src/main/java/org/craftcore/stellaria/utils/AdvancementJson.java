package org.craftcore.stellaria.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 独自進捗をバニラの進捗として登録するための JSON と、登録し直す範囲の計算。Bukkit に依存しない。
 * 条件は minecraft:impossible の done を 1 つだけ持たせ、コードからのみ達成させる。
 */
public final class AdvancementJson {

    public static final String NAMESPACE = "stellaria";
    public static final String CRITERION = "done";

    public record Plan(List<String> remove, List<String> load) {
    }

    private AdvancementJson() {
    }

    public static String path(String tab, String id) {
        return tab + "/" + id;
    }

    public static String rootPath(String tab) {
        return path(tab, "root");
    }

    public static String parentPath(AdvancementDefinitions.Definition def) {
        return def.parent() == null ? rootPath(def.tab()) : path(def.tab(), def.parent());
    }

    /** タブのルート。トースト・通知なしで、ログイン時に自動で達成させる。 */
    public static JsonObject root(AdvancementDefinitions.Tab tab, Function<String, JsonElement> text) {
        JsonObject display = display(tab.icon(), text.apply(tab.title()), text.apply(tab.description()),
                "task", false, false, false);
        display.addProperty("background", tab.background());
        return withCriterion(null, display);
    }

    public static JsonObject advancement(AdvancementDefinitions.Definition def, boolean announce,
                                         Function<String, JsonElement> text) {
        JsonObject display = display(def.icon(), text.apply(def.title()), text.apply(def.description()),
                def.difficulty().frame(), true, announce, def.hidden());
        return withCriterion(NAMESPACE + ":" + parentPath(def), display);
    }

    private static JsonObject display(String icon, JsonElement title, JsonElement description, String frame,
                                      boolean toast, boolean announce, boolean hidden) {
        JsonObject iconJson = new JsonObject();
        iconJson.addProperty("id", "minecraft:" + icon.toLowerCase(Locale.ROOT));
        JsonObject display = new JsonObject();
        display.add("icon", iconJson);
        display.add("title", title);
        display.add("description", description);
        display.addProperty("frame", frame);
        display.addProperty("show_toast", toast);
        display.addProperty("announce_to_chat", announce);
        display.addProperty("hidden", hidden);
        return display;
    }

    private static JsonObject withCriterion(String parent, JsonObject display) {
        JsonObject trigger = new JsonObject();
        trigger.addProperty("trigger", "minecraft:impossible");
        JsonObject criteria = new JsonObject();
        criteria.add(CRITERION, trigger);
        JsonArray inner = new JsonArray();
        inner.add(CRITERION);
        JsonArray requirements = new JsonArray();
        requirements.add(inner);
        JsonObject json = new JsonObject();
        if (parent != null) {
            json.addProperty("parent", parent);
        }
        json.add("display", display);
        json.add("criteria", criteria);
        json.add("requirements", requirements);
        return json;
    }

    public static String hash(JsonObject json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 登録し直す範囲を求める。登録されていない・前回と内容が違う進捗と、その子孫を登録し直す。
     * 定義から消えた進捗は削除する。削除は子から、登録は親から行う順で返す。
     */
    public static Plan plan(LinkedHashMap<String, String> desiredHashes, Map<String, String> parentOf,
                            Map<String, String> storedHashes, Set<String> existing) {
        Set<String> reload = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : desiredHashes.entrySet()) {
            String path = entry.getKey();
            boolean changed = !existing.contains(path) || !Objects.equals(storedHashes.get(path), entry.getValue());
            String parent = parentOf.get(path);
            if (changed || (parent != null && reload.contains(parent))) {
                reload.add(path);
            }
        }
        List<String> remove = new ArrayList<>();
        for (String path : existing) {
            if (!desiredHashes.containsKey(path)) {
                remove.add(path);
            }
        }
        remove.sort(null);
        List<String> reloadExisting = new ArrayList<>(reload.stream().filter(existing::contains).toList());
        java.util.Collections.reverse(reloadExisting);
        remove.addAll(reloadExisting);
        return new Plan(remove, List.copyOf(reload));
    }
}
