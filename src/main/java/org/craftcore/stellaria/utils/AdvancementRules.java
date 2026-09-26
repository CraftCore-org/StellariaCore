package org.craftcore.stellaria.utils;

import org.craftcore.stellaria.utils.AdvancementDefinitions.Definition;
import org.craftcore.stellaria.utils.AdvancementDefinitions.TriggerType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/** 独自進捗の達成判定と進み具合。Bukkit に依存しない。 */
public final class AdvancementRules {

    /** プレイヤー 1 人分の状態。distinctCounts は distinct 型のキーごとの異なる値の数。 */
    public record State(Map<String, Long> counters, Map<String, Long> distinctCounts, Set<String> completed) {
    }

    private AdvancementRules() {
    }

    public static long goal(Definition def) {
        return def.trigger().goal();
    }

    /**
     * 現在の進み具合。goal を上限にはしない。
     *
     * @param stat stat 型のキー（統計ランキングのキーか playtime）から現在値を返す関数
     */
    public static long progress(Definition def, State state, ToLongFunction<String> stat, List<Definition> all) {
        String key = def.trigger().key();
        return switch (def.trigger().type()) {
            case COUNTER -> state.counters().getOrDefault(key, 0L);
            case EVENT -> Math.min(1L, state.counters().getOrDefault(key, 0L));
            case DISTINCT -> state.distinctCounts().getOrDefault(key, 0L);
            case STAT -> stat.applyAsLong(key);
            case ALL_OF -> def.trigger().ids().stream().filter(state.completed()::contains).count();
            case COMPLETED -> all.stream()
                    .filter(other -> other.trigger().type() != TriggerType.COMPLETED)
                    .filter(other -> state.completed().contains(other.id()))
                    .count();
        };
    }

    public static boolean isMet(Definition def, State state, ToLongFunction<String> stat, List<Definition> all) {
        return progress(def, state, stat, all) >= goal(def);
    }

    /** counter / event / distinct / stat の進捗を、見ているキーで引けるようにする。 */
    public static Map<String, List<Definition>> indexByKey(List<Definition> all) {
        Map<String, List<Definition>> index = new LinkedHashMap<>();
        for (Definition def : all) {
            TriggerType type = def.trigger().type();
            if (type == TriggerType.ALL_OF || type == TriggerType.COMPLETED) {
                continue;
            }
            index.computeIfAbsent(def.trigger().key(), k -> new ArrayList<>()).add(def);
        }
        return index;
    }

    /** 他の進捗の達成で判定が変わる進捗（all_of と completed）。 */
    public static List<Definition> dependents(List<Definition> all) {
        return all.stream()
                .filter(def -> def.trigger().type() == TriggerType.ALL_OF || def.trigger().type() == TriggerType.COMPLETED)
                .toList();
    }

    public static List<Definition> ofType(List<Definition> all, TriggerType type) {
        return all.stream().filter(def -> def.trigger().type() == type).toList();
    }

    /** GUI で中身を伏せるか（隠し進捗で未達成）。 */
    public static boolean isConcealed(Definition def, boolean completed) {
        return def.hidden() && !completed;
    }
}
