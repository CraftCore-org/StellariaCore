package org.craftcore.stellaria.utils;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * advancements.yml の読み込みと検証。間違った定義はその進捗だけを無効にして警告し、
 * 無効になった進捗を親や all_of で参照している進捗も連鎖して無効にする。
 * Bukkit のサーバーには依存しない（アイコンの検証は呼び出し側から述語で受け取る）。
 */
public final class AdvancementDefinitions {

    public enum Difficulty {
        EASY("task"), NORMAL("task"), HARD("goal"), CHALLENGE("challenge");

        private final String frame;

        Difficulty(String frame) {
            this.frame = frame;
        }

        /** バニラ進捗の枠の種類。 */
        public String frame() {
            return frame;
        }

        /** config.yml の advancements.difficulties.<ここ> に使うキー。 */
        public String configKey() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum TriggerType { COUNTER, DISTINCT, EVENT, STAT, ALL_OF, COMPLETED }

    public record Tab(String id, String title, String description, String icon, String background) {
    }

    public record Trigger(TriggerType type, String key, long goal, List<String> ids) {
    }

    public record Definition(String id, String tab, @Nullable String parent, String icon, String title,
                             String description, Difficulty difficulty, boolean hidden, @Nullable Long reward,
                             Trigger trigger) {
    }

    public record Parsed(Map<String, Tab> tabs, List<Definition> definitions) {
        public Optional<Definition> find(String id) {
            return definitions.stream().filter(d -> d.id().equals(id)).findFirst();
        }
    }

    /** stat 型で使えるキー。統計ランキングのキーと playtime（秒）。 */
    public static final Set<String> STAT_KEYS = Stream.concat(
            StatDefinitions.all().stream().map(StatDefinitions.Definition::key), Stream.of("playtime"))
            .collect(Collectors.toUnmodifiableSet());

    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final String DEFAULT_BACKGROUND = "minecraft:block/stone";

    private AdvancementDefinitions() {
    }

    public static Parsed parse(ConfigurationSection root, Predicate<String> validIcon, Consumer<String> warn) {
        Map<String, Tab> tabs = parseTabs(root.getConfigurationSection("tabs"), validIcon, warn);
        Map<String, Definition> candidates = new LinkedHashMap<>();
        ConfigurationSection section = root.getConfigurationSection("advancements");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection entry = section.getConfigurationSection(id);
                String error = entry == null ? "設定がセクションになっていません" : null;
                Definition def = null;
                if (error == null) {
                    try {
                        def = parseDefinition(id, entry, tabs, validIcon);
                    } catch (IllegalArgumentException e) {
                        error = e.getMessage();
                    }
                }
                if (def == null) {
                    warn.accept(disabled(id, error));
                } else {
                    candidates.put(id, def);
                }
            }
        }
        dropBrokenReferences(candidates, warn);
        return new Parsed(Map.copyOf(tabs), parentsFirst(candidates));
    }

    private static Map<String, Tab> parseTabs(@Nullable ConfigurationSection section, Predicate<String> validIcon,
                                              Consumer<String> warn) {
        Map<String, Tab> tabs = new LinkedHashMap<>();
        if (section == null) {
            return tabs;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            String title = entry == null ? null : entry.getString("title");
            String description = entry == null ? null : entry.getString("description");
            String icon = entry == null ? null : entry.getString("icon");
            if (!ID.matcher(id).matches() || isBlank(title) || isBlank(description) || icon == null
                    || !validIcon.test(icon)) {
                warn.accept("advancements.yml: タブ " + id + " を無効にしました: ID・title・description・icon のいずれかが不正です");
                continue;
            }
            tabs.put(id, new Tab(id, title, description, icon, entry.getString("background", DEFAULT_BACKGROUND)));
        }
        return tabs;
    }

    private static Definition parseDefinition(String id, ConfigurationSection entry, Map<String, Tab> tabs,
                                              Predicate<String> validIcon) {
        require(ID.matcher(id).matches() && !id.equals("root"), "ID は [a-z0-9_]+ で、root 以外にしてください");
        String tab = entry.getString("tab");
        require(tab != null && tabs.containsKey(tab), "tab が存在しません: " + tab);
        String icon = entry.getString("icon");
        require(icon != null && validIcon.test(icon), "icon が不正です: " + icon);
        String title = entry.getString("title");
        String description = entry.getString("description");
        require(!isBlank(title) && !isBlank(description), "title と description は必須です");
        Difficulty difficulty = parseEnum(Difficulty.class, entry.getString("difficulty"), "difficulty");
        Long reward = entry.contains("reward") ? entry.getLong("reward") : null;
        require(reward == null || reward >= 0, "reward は 0 以上にしてください");
        String parent = entry.getString("parent");
        ConfigurationSection triggerSection = entry.getConfigurationSection("trigger");
        require(triggerSection != null, "trigger は必須です");
        return new Definition(id, tab, parent, icon, title, description, difficulty,
                entry.getBoolean("hidden", false), reward, parseTrigger(triggerSection));
    }

    private static Trigger parseTrigger(ConfigurationSection section) {
        TriggerType type = parseEnum(TriggerType.class, section.getString("type"), "trigger.type");
        String key = section.getString("key", "");
        long goal = section.getLong("goal", 0L);
        return switch (type) {
            case COUNTER, DISTINCT -> {
                require(!key.isBlank(), "trigger.key は必須です");
                require(goal > 0, "trigger.goal は 1 以上にしてください");
                yield new Trigger(type, key, goal, List.of());
            }
            case EVENT -> {
                require(!key.isBlank(), "trigger.key は必須です");
                yield new Trigger(type, key, 1L, List.of());
            }
            case STAT -> {
                require(STAT_KEYS.contains(key), "trigger.key に使える統計ではありません: " + key);
                require(goal > 0, "trigger.goal は 1 以上にしてください");
                yield new Trigger(type, key, goal, List.of());
            }
            case ALL_OF -> {
                List<String> ids = List.copyOf(new LinkedHashSet<>(section.getStringList("ids")));
                require(!ids.isEmpty(), "trigger.ids は 1 つ以上必要です");
                yield new Trigger(type, "", ids.size(), ids);
            }
            case COMPLETED -> {
                require(goal > 0, "trigger.goal は 1 以上にしてください");
                yield new Trigger(type, "", goal, List.of());
            }
        };
    }

    /** 参照先がない・別タブの親・循環している進捗を、変化がなくなるまで取り除く。 */
    private static void dropBrokenReferences(Map<String, Definition> candidates, Consumer<String> warn) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Definition def : List.copyOf(candidates.values())) {
                String reason = brokenReference(def, candidates);
                if (reason != null) {
                    candidates.remove(def.id());
                    warn.accept(disabled(def.id(), reason));
                    changed = true;
                }
            }
            if (!changed) {
                Set<String> cyclic = cyclicIds(candidates);
                for (String id : cyclic) {
                    candidates.remove(id);
                    warn.accept(disabled(id, "parent か all_of が循環しています"));
                    changed = true;
                }
            }
        }
    }

    private static @Nullable String brokenReference(Definition def, Map<String, Definition> candidates) {
        if (def.parent() != null) {
            Definition parent = candidates.get(def.parent());
            if (parent == null) {
                return "parent が存在しません: " + def.parent();
            }
            if (!parent.tab().equals(def.tab())) {
                return "parent は同じタブの進捗にしてください: " + def.parent();
            }
        }
        for (String id : def.trigger().ids()) {
            if (!candidates.containsKey(id)) {
                return "trigger.ids の進捗が存在しません: " + id;
            }
        }
        return null;
    }

    /** parent と all_of の辺をたどって、循環に含まれる進捗の ID を返す。 */
    private static Set<String> cyclicIds(Map<String, Definition> candidates) {
        Map<String, List<String>> edges = new HashMap<>();
        for (Definition def : candidates.values()) {
            List<String> targets = new ArrayList<>(def.trigger().ids());
            if (def.parent() != null) {
                targets.add(def.parent());
            }
            edges.put(def.id(), targets);
        }
        Set<String> cyclic = new HashSet<>();
        for (String start : candidates.keySet()) {
            if (reaches(start, start, edges, new HashSet<>())) {
                cyclic.add(start);
            }
        }
        return cyclic;
    }

    private static boolean reaches(String from, String target, Map<String, List<String>> edges, Set<String> visited) {
        for (String next : edges.getOrDefault(from, List.of())) {
            if (next.equals(target)) {
                return true;
            }
            if (visited.add(next) && reaches(next, target, edges, visited)) {
                return true;
            }
        }
        return false;
    }

    private static List<Definition> parentsFirst(Map<String, Definition> candidates) {
        Map<String, Definition> ordered = new LinkedHashMap<>();
        for (Definition def : candidates.values()) {
            addWithParents(def, candidates, ordered);
        }
        return List.copyOf(ordered.values());
    }

    private static void addWithParents(Definition def, Map<String, Definition> all, Map<String, Definition> ordered) {
        if (ordered.containsKey(def.id())) {
            return;
        }
        if (def.parent() != null) {
            addWithParents(all.get(def.parent()), all, ordered);
        }
        ordered.put(def.id(), def);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, @Nullable String value, String field) {
        require(value != null, field + " は必須です");
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " が不正です: " + value);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static String disabled(String id, @Nullable String reason) {
        return "advancements.yml: 進捗 " + id + " を無効にしました: " + reason;
    }
}
