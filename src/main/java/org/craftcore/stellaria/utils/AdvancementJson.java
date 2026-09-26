package org.craftcore.stellaria.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 独自進捗をバニラの進捗として登録するための JSON。Bukkit に依存しない。
 * 条件は minecraft:impossible の done を 1 つだけ持たせ、コードからのみ達成させる。
 */
public final class AdvancementJson {

    public static final String NAMESPACE = "stellaria";
    public static final String CRITERION = "done";

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

    /** まだ登録されていない進捗のパスを、desired の順（親が先）のまま返す。 */
    public static List<String> missing(List<String> desired, Set<String> existing) {
        return desired.stream().filter(path -> !existing.contains(path)).toList();
    }

}
