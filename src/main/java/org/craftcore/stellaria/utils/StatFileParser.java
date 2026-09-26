package org.craftcore.stellaria.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * メインワールドの stats/&lt;uuid&gt;.json を解析して、StatDefinitions の各キーの値を求める。
 * OfflinePlayer#getStatistic は呼ぶたびにファイルを読み直すため、起動時の取り込みではこちらを使う。
 */
public final class StatFileParser {

    private static final String NAMESPACE = "minecraft:";

    private StatFileParser() {
    }

    /**
     * @param isBlockItem "minecraft:stone" のような ID を受け取り、置けるブロックなら true を返す
     * @return 全定義のキーを含む Map（データが無ければ 0）
     * @throws IllegalArgumentException JSON として不正、またはルートがオブジェクトでない場合
     */
    public static Map<String, Long> parse(String json, Predicate<String> isBlockItem) {
        JsonObject stats;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("統計ファイルのルートがオブジェクトではありません");
            }
            stats = root.getAsJsonObject().has("stats")
                    ? root.getAsJsonObject().getAsJsonObject("stats")
                    : new JsonObject();
        } catch (JsonParseException | ClassCastException | IllegalStateException e) {
            throw new IllegalArgumentException("統計ファイルを解析できません: " + e.getMessage(), e);
        }

        JsonObject custom = section(stats, "custom");
        JsonObject mined = section(stats, "mined");
        JsonObject used = section(stats, "used");

        Map<String, Long> values = new LinkedHashMap<>();
        for (StatDefinitions.Definition def : StatDefinitions.all()) {
            long sum = switch (def.kind()) {
                case CUSTOM_SUM -> def.customIds().stream().mapToLong(id -> number(custom, NAMESPACE + id)).sum();
                case MINED_ALL -> mined.keySet().stream().mapToLong(id -> number(mined, id)).sum();
                case PLACED_BLOCKS -> used.keySet().stream().filter(isBlockItem).mapToLong(id -> number(used, id)).sum();
            };
            values.put(def.key(), sum);
        }
        return values;
    }

    private static JsonObject section(JsonObject stats, String name) {
        JsonElement element = stats.get(NAMESPACE + name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static long number(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0L;
        }
        return element.getAsLong();
    }
}
