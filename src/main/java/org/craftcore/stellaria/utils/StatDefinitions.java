package org.craftcore.stellaria.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * /ranking に出す統計ランキングの定義。キーごとに、どのバニラ統計をどう合計するかを持つ。
 * customIds はバニラの統計 ID（stats/*.json の "minecraft:custom" 内のキーから名前空間を除いたもの）で、
 * Bukkit の Statistic 定数名を小文字にしたものと一致する。
 */
public final class StatDefinitions {

    public enum Kind {
        /** customIds の統計をすべて足す。 */
        CUSTOM_SUM,
        /** 全ブロック種類の MINE_BLOCK を足す。 */
        MINED_ALL,
        /** ブロックとして置ける種類の USE_ITEM を足す（ブロックを置いた回数）。 */
        PLACED_BLOCKS
    }

    public record Definition(String key, Kind kind, List<String> customIds) {
    }

    private static final List<Definition> ALL = List.of(
            custom("mobkills", "mob_kills"),
            custom("pvpkills", "player_kills"),
            custom("deaths", "deaths"),
            custom("fishing", "fish_caught"),
            new Definition("mined", Kind.MINED_ALL, List.of()),
            new Definition("placed", Kind.PLACED_BLOCKS, List.of()),
            custom("distance", "walk_one_cm", "sprint_one_cm", "crouch_one_cm", "swim_one_cm",
                    "walk_on_water_one_cm", "walk_under_water_one_cm", "climb_one_cm", "fall_one_cm",
                    "fly_one_cm", "aviate_one_cm", "boat_one_cm", "minecart_one_cm", "horse_one_cm",
                    "pig_one_cm", "strider_one_cm", "happy_ghast_one_cm"),
            custom("jumps", "jump"),
            custom("trades", "traded_with_villager"),
            custom("bred", "animals_bred"),
            custom("cake", "eat_cake_slice")
    );

    private StatDefinitions() {
    }

    private static Definition custom(String key, String... ids) {
        return new Definition(key, Kind.CUSTOM_SUM, List.of(ids));
    }

    public static List<Definition> all() {
        return ALL;
    }

    public static Optional<Definition> find(String key) {
        return ALL.stream().filter(d -> d.key().equals(key)).findFirst();
    }

    /** config.yml の ranking.stats を検証する。未知のキーと重複は warn に通知して除外し、順序は保つ。 */
    public static List<String> filterConfigured(List<String> configured, Consumer<String> warn) {
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : configured) {
            String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (find(key).isEmpty()) {
                warn.accept("ranking.stats に未知の統計キーがあります: " + raw);
            } else if (!seen.add(key)) {
                warn.accept("ranking.stats に統計キーが重複しています: " + raw);
            }
        }
        return new ArrayList<>(seen);
    }
}
