# 統計ランキング拡張 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/ranking` にバニラ統計 11 種類のランキングと「自分の順位」表示を追加し、`/settings` で統計ランキングへの掲載を切り替えられるようにする。

**Architecture:** バニラ統計を `player_stat_snapshots` テーブル（uuid × stat_key → value）に書き出し、`/ranking` はこのテーブルを並べ替えて表示する。書き出しはログアウト時・定期実行・終了時に Bukkit API から行い、導入前の記録は起動時に `stats/<uuid>.json` を非同期で解析して取り込む。統計の定義・JSON 解析・表示形式は Bukkit に依存しない純粋なクラスに切り出して単体テストする。

**Tech Stack:** Java 21, Paper API 1.21.11, SQLite（`DatabaseManager`）, Gson（Paper API に同梱）, JUnit 5

**Spec:** `docs/superpowers/specs/2026-09-26-ranking-stats-design.md`

## Global Constraints

- 設定ファイルの文言は `&%<char>` の独自パレットで書く（`&l`, `&r` などの書式コードはそのまま）。
- プレイヤー向けの文言はすべて `messages.yml`、挙動の設定は `config.yml` に置く。`plugin.getConfig()` は使わず `ConfigManager` を経由する。
- ファイルに書く文章（コメント、コミットメッセージ、設定ファイルのコメント）は標準語で書く。
- ルートプロジェクトのテストは `./gradlew :test --tests <クラス名>` で実行する（`:` なしだとサブプロジェクトで "No tests found" になる）。
- `build.gradle.kts` の `version` と `plugin.yml` の `version` は編集しない。
- 統計キーは `mobkills`, `pvpkills`, `deaths`, `fishing`, `mined`, `placed`, `distance`, `jumps`, `trades`, `bred`, `cake` の 11 種類。
- `ranking.snapshot-interval-minutes` の既定値は 5。
- `money` ランキングの公開判定は従来どおり `hide_balance` のみ。`playtime` と統計ランキングは `hide_stats_ranking` で判定する。

## Review Focus

1. **統計ファイルが壊れている・空・存在しない** — 取り込みはそのプレイヤーを 0 件として飛ばし、他のプレイヤーの取り込みと起動は続行する（Task 1 のテストと Task 3 の実装で担保）。
2. **Bukkit API が受け付けない `Material`**（空気、レガシー、アイテム化できないブロック） — `getStatistic` が例外を投げても合計処理が止まらない（Task 3 のキャッシュ構築で除外）。
3. **サーバーに存在しない `Statistic`**（`HAPPY_GHAST_ONE_CM` など、API 側に定数がない場合） — その項目だけ加算対象から外し、警告は 1 回だけ出す（Task 3）。
4. **`config.yml` の `ranking.stats` に未知のキーや重複** — 警告して無視し、既知のキーだけを順序を保って使う（Task 1 のテスト）。
5. **非公開のプレイヤーが `/ranking` を開く** — 一覧には自分が出ず、本人にだけ「参考順位」が出る。総数は公開人数 + 自分 1 人になる（Task 4 のテストと実装）。

---

## File Structure

| ファイル | 役割 |
|---|---|
| Create `src/main/java/org/craftcore/stellaria/utils/StatDefinitions.java` | 11 種類の統計キーと、それぞれが合計するバニラ統計 ID の定義。`ranking.stats` の検証 |
| Create `src/main/java/org/craftcore/stellaria/utils/StatFileParser.java` | `stats/<uuid>.json` を解析して統計キーごとの値を返す |
| Create `src/main/java/org/craftcore/stellaria/utils/RankingFormat.java` | 値の表示形式（3 桁区切り、距離の m/km）と順位計算 |
| Create `src/main/java/org/craftcore/stellaria/managers/StatSnapshotManager.java` | オンライン統計の読み取り、DB への書き出し、起動時の取り込み、ランキング用クエリ、非公開フラグ |
| Modify `src/main/java/org/craftcore/stellaria/StellariaCore.java` | テーブル作成、manager の生成・定期実行・終了時の書き出し・リロード |
| Modify `src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java` | ログアウト時の書き出し |
| Modify `src/main/java/org/craftcore/stellaria/commands/RankingCommand.java` | 統計ランキングの表示と自分の順位 |
| Modify `src/main/java/org/craftcore/stellaria/managers/EconomyManager.java` | 所持金の順位計算用クエリ |
| Modify `src/main/java/org/craftcore/stellaria/managers/PlaytimeManager.java` | プレイ時間ランキングへの非公開フィルタと順位計算用クエリ |
| Modify `src/main/java/org/craftcore/stellaria/gui/SettingsGui.java` | 統計ランキング掲載のトグル |
| Modify `src/main/resources/config.yml`, `src/main/resources/messages.yml` | 設定と文言 |
| Test `src/test/java/org/craftcore/stellaria/utils/StatDefinitionsTest.java`, `StatFileParserTest.java`, `RankingFormatTest.java` | 純粋ロジックのテスト |

---

### Task 1: 統計の定義と統計ファイルの解析

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/StatDefinitions.java`
- Create: `src/main/java/org/craftcore/stellaria/utils/StatFileParser.java`
- Test: `src/test/java/org/craftcore/stellaria/utils/StatDefinitionsTest.java`
- Test: `src/test/java/org/craftcore/stellaria/utils/StatFileParserTest.java`

**Interfaces:**
- Produces:
  - `StatDefinitions.Kind` enum: `CUSTOM_SUM`, `MINED_ALL`, `PLACED_BLOCKS`
  - `record StatDefinitions.Definition(String key, Kind kind, List<String> customIds)` — `customIds` はバニラ統計 ID（`minecraft:` を除いた `mob_kills` など）。`CUSTOM_SUM` 以外では空リスト。
  - `static List<Definition> StatDefinitions.all()` — 11 件、仕様書の表の順。
  - `static Optional<Definition> StatDefinitions.find(String key)`
  - `static List<String> StatDefinitions.filterConfigured(List<String> configured, Consumer<String> warn)` — 小文字化・前後空白除去・未知キーと重複を除外（それぞれ `warn` に 1 回通知）、順序は維持。
  - `static Map<String, Long> StatFileParser.parse(String json, Predicate<String> isBlockItem)` — 全定義のキーを必ず含む Map（該当データがなければ 0）。`isBlockItem` は `minecraft:stone` のような名前空間付き ID を受け取る。JSON が不正なら `IllegalArgumentException`。

- [ ] **Step 1: 定義のテストを書く**

```java
package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatDefinitionsTest {

    @Test
    void definesElevenKeysInSpecOrder() {
        List<String> keys = StatDefinitions.all().stream().map(StatDefinitions.Definition::key).toList();
        assertEquals(List.of("mobkills", "pvpkills", "deaths", "fishing", "mined", "placed",
                "distance", "jumps", "trades", "bred", "cake"), keys);
    }

    @Test
    void distanceSumsMovementStatistics() {
        StatDefinitions.Definition distance = StatDefinitions.find("distance").orElseThrow();
        assertEquals(StatDefinitions.Kind.CUSTOM_SUM, distance.kind());
        assertTrue(distance.customIds().contains("walk_one_cm"));
        assertTrue(distance.customIds().contains("aviate_one_cm"));
        assertTrue(distance.customIds().contains("happy_ghast_one_cm"));
    }

    @Test
    void filterConfiguredDropsUnknownAndDuplicateKeysKeepingOrder() {
        List<String> warnings = new ArrayList<>();
        List<String> result = StatDefinitions.filterConfigured(
                List.of(" Deaths", "mobkills", "unknown", "deaths", "cake"), warnings::add);
        assertEquals(List.of("deaths", "mobkills", "cake"), result);
        assertEquals(2, warnings.size());
    }
}
```

- [ ] **Step 2: 解析のテストを書く**

```java
package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatFileParserTest {

    private static final Set<String> BLOCK_ITEMS = Set.of("minecraft:stone", "minecraft:oak_planks");

    @Test
    void sumsVanillaStatisticsPerKey() {
        String json = """
                {"stats":{
                  "minecraft:custom":{"minecraft:mob_kills":12,"minecraft:deaths":3,
                    "minecraft:walk_one_cm":1000,"minecraft:sprint_one_cm":500,"minecraft:jump":7},
                  "minecraft:mined":{"minecraft:stone":40,"minecraft:dirt":2},
                  "minecraft:used":{"minecraft:stone":5,"minecraft:oak_planks":6,"minecraft:diamond_pickaxe":99}
                },"DataVersion":4440}
                """;
        Map<String, Long> values = StatFileParser.parse(json, BLOCK_ITEMS::contains);
        assertEquals(12L, values.get("mobkills"));
        assertEquals(3L, values.get("deaths"));
        assertEquals(1500L, values.get("distance"));
        assertEquals(7L, values.get("jumps"));
        assertEquals(42L, values.get("mined"));
        assertEquals(11L, values.get("placed"));
        assertEquals(0L, values.get("cake"));
        assertEquals(11, values.size());
    }

    @Test
    void missingSectionsBecomeZero() {
        Map<String, Long> values = StatFileParser.parse("{\"DataVersion\":4440}", BLOCK_ITEMS::contains);
        assertEquals(11, values.size());
        values.values().forEach(v -> assertEquals(0L, v));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> StatFileParser.parse("{not json", BLOCK_ITEMS::contains));
        assertThrows(IllegalArgumentException.class, () -> StatFileParser.parse("[]", BLOCK_ITEMS::contains));
    }
}
```

- [ ] **Step 3: テストが失敗することを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.StatDefinitionsTest' --tests 'org.craftcore.stellaria.utils.StatFileParserTest'`
Expected: コンパイルエラー（`StatDefinitions` と `StatFileParser` が存在しない）

- [ ] **Step 4: `StatDefinitions` を実装する**

```java
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
```

- [ ] **Step 5: `StatFileParser` を実装する**

```java
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
```

- [ ] **Step 6: テストが通ることを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.StatDefinitionsTest' --tests 'org.craftcore.stellaria.utils.StatFileParserTest'`
Expected: PASS（6 件）

- [ ] **Step 7: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/utils/StatDefinitions.java src/main/java/org/craftcore/stellaria/utils/StatFileParser.java src/test/java/org/craftcore/stellaria/utils/StatDefinitionsTest.java src/test/java/org/craftcore/stellaria/utils/StatFileParserTest.java
git commit -m "feat(ranking): 統計ランキングの定義と統計ファイルの解析を追加する"
```

---

### Task 2: 表示形式と順位計算

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/RankingFormat.java`
- Test: `src/test/java/org/craftcore/stellaria/utils/RankingFormatTest.java`

**Interfaces:**
- Produces:
  - `static String RankingFormat.value(String statKey, long value)` — `distance` は cm を受け取り `850 m` / `12.3 km`、それ以外は `12,345`。
  - `static long RankingFormat.rank(long publicCountAbove)` — `publicCountAbove + 1`。
  - `static long RankingFormat.total(long publicCount, boolean selfHidden)` — 非公開なら `publicCount + 1`。

- [ ] **Step 1: テストを書く**

```java
package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankingFormatTest {

    @Test
    void formatsCountsWithThousandsSeparator() {
        assertEquals("0", RankingFormat.value("deaths", 0));
        assertEquals("12,345", RankingFormat.value("mobkills", 12_345));
    }

    @Test
    void formatsDistanceInMetersBelowOneKilometer() {
        assertEquals("0 m", RankingFormat.value("distance", 0));
        assertEquals("850 m", RankingFormat.value("distance", 85_099));
        assertEquals("999 m", RankingFormat.value("distance", 99_999));
    }

    @Test
    void formatsDistanceInKilometersFromOneKilometer() {
        assertEquals("1.0 km", RankingFormat.value("distance", 100_000));
        assertEquals("12.3 km", RankingFormat.value("distance", 1_234_567));
        assertEquals("1,234.6 km", RankingFormat.value("distance", 123_456_789));
    }

    @Test
    void rankCountsOnlyPlayersStrictlyAbove() {
        assertEquals(1, RankingFormat.rank(0));
        assertEquals(4, RankingFormat.rank(3));
    }

    @Test
    void hiddenSelfIsAddedToTotal() {
        assertEquals(10, RankingFormat.total(10, false));
        assertEquals(11, RankingFormat.total(10, true));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.RankingFormatTest'`
Expected: コンパイルエラー（`RankingFormat` が存在しない）

- [ ] **Step 3: 実装する**

```java
package org.craftcore.stellaria.utils;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** /ranking の値の表示形式と、自分の順位の計算。 */
public final class RankingFormat {

    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.ROOT);

    private RankingFormat() {
    }

    /** distance は cm で受け取り、1 km 未満は m（切り捨て）、それ以上は km（小数 1 桁）で返す。 */
    public static String value(String statKey, long value) {
        if ("distance".equals(statKey)) {
            long meters = value / 100L;
            if (meters < 1_000L) {
                return new DecimalFormat("#,##0", SYMBOLS).format(meters) + " m";
            }
            return new DecimalFormat("#,##0.0", SYMBOLS).format(value / 100_000.0) + " km";
        }
        return new DecimalFormat("#,##0", SYMBOLS).format(value);
    }

    /** 自分より値が大きい公開プレイヤーの数から順位を求める。同じ値のプレイヤーは同じ順位になる。 */
    public static long rank(long publicCountAbove) {
        return publicCountAbove + 1L;
    }

    /** 母数。自分が非公開なら公開人数に自分を足す。 */
    public static long total(long publicCount, boolean selfHidden) {
        return selfHidden ? publicCount + 1L : publicCount;
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.RankingFormatTest'`
Expected: PASS（5 件）

- [ ] **Step 5: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/utils/RankingFormat.java src/test/java/org/craftcore/stellaria/utils/RankingFormatTest.java
git commit -m "feat(ranking): ランキングの値の表示形式と順位計算を追加する"
```

---

### Task 3: 統計スナップショットの保存と取り込み

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/StatSnapshotManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`（テーブル作成ブロック、manager 生成、getter、`restartConfigScheduledTasks`、`onDisable`、`reloadFeatureManagers`）
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java:32-47`
- Modify: `src/main/resources/config.yml:164-165`

**Interfaces:**
- Consumes: `StatDefinitions.all()`, `StatDefinitions.filterConfigured(...)`, `StatFileParser.parse(...)`（Task 1）
- Produces:
  - `new StatSnapshotManager(StellariaCore plugin)`
  - `void reload()` — `ranking.stats` を読み直して `enabledKeys` をキャッシュ
  - `List<String> getEnabledKeys()`
  - `Map<String, Long> readLive(Player player)` — メインスレッドで呼ぶ
  - `void snapshotAsync(Player player)` / `void snapshotAllOnlineAsync()` / `void flushAllSync()`
  - `void backfillAsync()`
  - `record Entry(String name, long value)`
  - `List<Entry> getTop(String statKey, int limit, int offset)`
  - `int getPublicCount(String statKey)`
  - `int countPublicAbove(String statKey, long value)`
  - `boolean isHidden(OfflinePlayer player)` / `boolean setHidden(Player player, boolean hidden)`
  - `StellariaCore#getStatSnapshotManager()`

- [ ] **Step 1: DB のテーブルと列を追加する**

`StellariaCore#onEnable` の `addColumnIfNotExists("players", "mine_enabled ...")` の直後に追加する。

```java
        DatabaseManager.addColumnIfNotExists("players", "hide_stats_ranking INTEGER NOT NULL DEFAULT 0");
```

`shop_sale_notifications` のテーブル作成の直後（manager 生成の前）に追加する。

```java
        DatabaseManager.createTableIfNotExists("player_stat_snapshots",
            "uuid TEXT NOT NULL", "stat_key TEXT NOT NULL", "value INTEGER NOT NULL",
            "updated_at INTEGER NOT NULL", "PRIMARY KEY (uuid, stat_key)");
        DatabaseManager.execute("CREATE INDEX IF NOT EXISTS idx_player_stat_snapshots_key_value "
            + "ON player_stat_snapshots (stat_key, value DESC)");
```

- [ ] **Step 2: `StatSnapshotManager` を実装する**

```java
package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.StatDefinitions;
import org.craftcore.stellaria.utils.StatFileParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * バニラの統計を player_stat_snapshots に書き出し、/ranking 用に並べ替えて返す。
 * オンラインのプレイヤーは Bukkit API から読み（メインスレッド）、書き込みだけ非同期にする。
 * 導入前の記録は、起動時に stats/&lt;uuid&gt;.json を非同期で解析して取り込む。
 */
public class StatSnapshotManager {

    private final StellariaCore plugin;
    private List<String> enabledKeys = List.of();
    /** customIds から解決した Statistic。サーバーに存在しない ID は含まれない。 */
    private Map<String, Statistic> customStatistics;
    private Material[] minedMaterials;
    private Material[] placedMaterials;

    public StatSnapshotManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        enabledKeys = List.copyOf(StatDefinitions.filterConfigured(
                plugin.getConfigManager().getStringList("ranking.stats"),
                message -> plugin.getLogger().warning(message)));
    }

    public List<String> getEnabledKeys() {
        return enabledKeys;
    }

    // ------------------------------------------------------------------
    // 読み取り
    // ------------------------------------------------------------------

    /** オンラインのプレイヤーの統計を読む。メインスレッドから呼ぶこと。 */
    public Map<String, Long> readLive(Player player) {
        ensureCaches();
        Map<String, Long> values = new LinkedHashMap<>();
        for (StatDefinitions.Definition def : StatDefinitions.all()) {
            long sum = 0L;
            switch (def.kind()) {
                case CUSTOM_SUM -> {
                    for (String id : def.customIds()) {
                        Statistic statistic = customStatistics.get(id);
                        if (statistic != null) {
                            sum += player.getStatistic(statistic);
                        }
                    }
                }
                case MINED_ALL -> {
                    for (Material material : minedMaterials) {
                        sum += player.getStatistic(Statistic.MINE_BLOCK, material);
                    }
                }
                case PLACED_BLOCKS -> {
                    for (Material material : placedMaterials) {
                        sum += player.getStatistic(Statistic.USE_ITEM, material);
                    }
                }
            }
            values.put(def.key(), sum);
        }
        return values;
    }

    /**
     * 初回だけ Statistic と Material の対応を作る。getStatistic が例外を投げる種類
     * （空気・レガシー・アイテム化できないブロックなど）はここで除外しておく。
     */
    private void ensureCaches() {
        if (customStatistics != null) {
            return;
        }
        Map<String, Statistic> resolved = new LinkedHashMap<>();
        for (StatDefinitions.Definition def : StatDefinitions.all()) {
            for (String id : def.customIds()) {
                try {
                    resolved.put(id, Statistic.valueOf(id.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("統計 " + id + " はこのサーバーに存在しないため、ランキングの集計から除外します。");
                }
            }
        }
        Player probe = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        List<Material> mined = new ArrayList<>();
        List<Material> placed = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isBlock() || material.isAir()) {
                continue;
            }
            if (accepts(probe, Statistic.MINE_BLOCK, material)) {
                mined.add(material);
            }
            if (material.isItem() && accepts(probe, Statistic.USE_ITEM, material)) {
                placed.add(material);
            }
        }
        customStatistics = resolved;
        minedMaterials = mined.toArray(Material[]::new);
        placedMaterials = placed.toArray(Material[]::new);
    }

    private static boolean accepts(Player probe, Statistic statistic, Material material) {
        if (probe == null) {
            return true;
        }
        try {
            probe.getStatistic(statistic, material);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 書き出し
    // ------------------------------------------------------------------

    public void snapshotAsync(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, Long> values = readLive(player);
        Bukkit.getAsyncScheduler().runNow(plugin, task -> write(uuid, values));
    }

    public void snapshotAllOnlineAsync() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            snapshotAsync(player);
        }
    }

    /** シャットダウン時用。DB 切断前に終わらせる必要があるため同期で書く。 */
    public void flushAllSync() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            write(player.getUniqueId(), readLive(player));
        }
    }

    private void write(UUID uuid, Map<String, Long> values) {
        long now = System.currentTimeMillis();
        DatabaseManager.transaction(conn -> {
            for (Map.Entry<String, Long> entry : values.entrySet()) {
                DatabaseManager.execute(
                    "INSERT INTO player_stat_snapshots (uuid, stat_key, value, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (uuid, stat_key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                    uuid.toString(), entry.getKey(), entry.getValue(), now);
            }
        });
    }

    // ------------------------------------------------------------------
    // 起動時の取り込み
    // ------------------------------------------------------------------

    /** スナップショットが 1 件もないプレイヤーの統計ファイルを非同期で取り込む。 */
    public void backfillAsync() {
        Path statsDir = Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("stats");
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            List<String> targets = DatabaseManager.query(
                "SELECT uuid FROM players p WHERE NOT EXISTS "
                    + "(SELECT 1 FROM player_stat_snapshots s WHERE s.uuid = p.uuid)",
                rs -> rs.getString("uuid"));
            int imported = 0;
            for (String uuid : targets) {
                Path file = statsDir.resolve(uuid + ".json");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    write(UUID.fromString(uuid), StatFileParser.parse(json, StatSnapshotManager::isBlockItem));
                    imported++;
                } catch (IOException | IllegalArgumentException e) {
                    plugin.getLogger().warning("統計ファイルを取り込めませんでした: " + file.getFileName() + " / " + e.getMessage());
                }
            }
            if (imported > 0) {
                plugin.getLogger().info("統計ランキング: " + imported + " 人分の過去の統計を取り込みました。");
            }
        });
    }

    private static boolean isBlockItem(String namespacedId) {
        Material material = Material.matchMaterial(namespacedId);
        return material != null && !material.isLegacy() && material.isBlock() && material.isItem();
    }

    // ------------------------------------------------------------------
    // ランキング
    // ------------------------------------------------------------------

    public record Entry(String name, long value) {
    }

    public List<Entry> getTop(String statKey, int limit, int offset) {
        return DatabaseManager.query(
            "SELECT p.name AS name, s.value AS value FROM player_stat_snapshots s "
                + "JOIN players p ON p.uuid = s.uuid "
                + "WHERE s.stat_key = ? AND p.hide_stats_ranking = 0 "
                + "ORDER BY s.value DESC, p.name ASC LIMIT ? OFFSET ?",
            rs -> new Entry(rs.getString("name"), rs.getLong("value")),
            statKey, limit, offset);
    }

    public int getPublicCount(String statKey) {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) AS cnt FROM player_stat_snapshots s JOIN players p ON p.uuid = s.uuid "
                + "WHERE s.stat_key = ? AND p.hide_stats_ranking = 0",
            rs -> rs.getInt("cnt"), statKey);
        return count != null ? count : 0;
    }

    public int countPublicAbove(String statKey, long value) {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) AS cnt FROM player_stat_snapshots s JOIN players p ON p.uuid = s.uuid "
                + "WHERE s.stat_key = ? AND p.hide_stats_ranking = 0 AND s.value > ?",
            rs -> rs.getInt("cnt"), statKey, value);
        return count != null ? count : 0;
    }

    // ------------------------------------------------------------------
    // 公開設定
    // ------------------------------------------------------------------

    public boolean isHidden(OfflinePlayer player) {
        if (player == null) {
            return false;
        }
        Integer hidden = DatabaseManager.queryOne(
            "SELECT hide_stats_ranking FROM players WHERE uuid = ?",
            rs -> rs.getInt("hide_stats_ranking"), player.getUniqueId().toString());
        return hidden != null && hidden != 0;
    }

    /** 連打時の順序逆転を避けるため同期で書き込み、成否を返す（EconomyManager#setHideBalance と同じ方針）。 */
    public boolean setHidden(Player player, boolean hidden) {
        int affected = DatabaseManager.update("players", Map.of("hide_stats_ranking", hidden ? 1 : 0),
            "uuid = ?", player.getUniqueId().toString());
        return affected > 0;
    }
}
```

`ensureCaches()` はオンラインのプレイヤーが 1 人もいないときに呼ばれることはない（`readLive` の引数が必ずオンラインのプレイヤーであるため、`probe` は常に取得できる）。`probe == null` の分岐は防御用である。

- [ ] **Step 3: `StellariaCore` に組み込む**

フィールドを追加する（`private PlaytimeManager playtimeManager;` の直後）。

```java
    private StatSnapshotManager statSnapshotManager;
    private ScheduledTask statSnapshotTask;
```

`this.playtimeManager = new PlaytimeManager(this);` の直後に追加する。

```java
        this.statSnapshotManager = new StatSnapshotManager(this);
        this.statSnapshotManager.reload();
        this.statSnapshotManager.backfillAsync();
```

getter を `getPlaytimeManager()` の直後に追加する。

```java
    public StatSnapshotManager getStatSnapshotManager() {
        return this.statSnapshotManager;
    }
```

`restartConfigScheduledTasks()` の `cancelTask(mineTask);` の直後に追加し、同メソッド末尾でタスクを開始する。

```java
        cancelTask(statSnapshotTask);
```

```java
        long statSnapshotInterval = SchedulerIntervalUtil.minutesToTicks(configManager.getInt("ranking.snapshot-interval-minutes", 5));
        statSnapshotTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,
            task -> statSnapshotManager.snapshotAllOnlineAsync(), statSnapshotInterval, statSnapshotInterval);
```

`onDisable()` の `playtimeManager.flushAll();` の直後に追加する。

```java
        statSnapshotManager.flushAllSync();
```

`reloadFeatureManagers()` の `rankManager.reload();` の直後に追加する。

```java
        statSnapshotManager.reload();
```

import に `org.craftcore.stellaria.managers.StatSnapshotManager` を追加する（既存の import 形式に合わせる）。

- [ ] **Step 4: ログアウト時に書き出す**

`PlayerListener#onPlayerLeave` の先頭（`TpaCore.resetPlayerTeleportRequests` の前）に追加する。

```java
        plugin.getStatSnapshotManager().snapshotAsync(event.getPlayer());
```

- [ ] **Step 5: `config.yml` に設定を追加する**

`ranking:` ブロックを次のように置き換える。

```yaml
ranking:
  page-size: 10
  snapshot-interval-minutes: 5 # オンライン中のプレイヤーの統計をDBへ書き出す間隔（分）
  # /ranking に出す統計ランキング。並び順がタブ補完の順になる。
  # 使えるキー: mobkills, pvpkills, deaths, fishing, mined, placed, distance, jumps, trades, bred, cake
  stats:
    - mobkills
    - pvpkills
    - deaths
    - fishing
    - mined
    - placed
    - distance
    - jumps
    - trades
    - bred
    - cake
```

- [ ] **Step 6: ビルドが通ることを確認する**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（Task 1・2 のテストも含めて通る）

- [ ] **Step 7: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/managers/StatSnapshotManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java src/main/resources/config.yml
git commit -m "feat(ranking): バニラ統計をDBへ書き出すスナップショット機能を追加する"
```

注意: `StellariaCore.java` には作業ツリーに別の未コミット変更（`FarmlandTrampleListener` の登録）がある。`git add -p` で本タスクの差分だけをステージするか、先にユーザーへ扱いを確認する。

---

### Task 4: `/ranking` の統計ランキングと自分の順位

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/commands/RankingCommand.java`（全体）
- Modify: `src/main/java/org/craftcore/stellaria/managers/EconomyManager.java:350-354` 付近（メソッド追加）
- Modify: `src/main/java/org/craftcore/stellaria/managers/PlaytimeManager.java:170-185`（クエリ変更・メソッド追加）
- Modify: `src/main/resources/messages.yml:329-342`

**Interfaces:**
- Consumes: `StatSnapshotManager#getEnabledKeys`, `readLive`, `getTop`, `getPublicCount`, `countPublicAbove`, `isHidden`（Task 3）、`RankingFormat.value/rank/total`（Task 2）
- Produces:
  - `int EconomyManager#countPublicAbove(double coins)`
  - `int PlaytimeManager#countPublicAbove(long seconds)`、`getTopPlaytimes` と `getPlayerCount` は `hide_stats_ranking = 0` で絞り込む

- [ ] **Step 1: `EconomyManager` に順位計算用クエリを追加する**

`getPublicPlayerCount()` の直後に追加する。

```java
    /** 自分より所持金が多い公開プレイヤーの数（/ranking money の自分の順位用）。 */
    public int countPublicAbove(double coins) {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) as cnt FROM players WHERE hide_balance = 0 AND coins > ?",
            rs -> rs.getInt("cnt"), coins);
        return count != null ? count : 0;
    }
```

- [ ] **Step 2: `PlaytimeManager` のランキングに非公開フィルタと順位計算を追加する**

`getTopPlaytimes` と `getPlayerCount` を置き換え、`countPublicAbove` を追加する。

```java
    /** プレイ時間降順で limit 件、offset 件スキップして取得する（/ranking playtime のページング用）。
     * player_stats には名前を持たないので players テーブルと uuid で突き合わせる。
     * 統計ランキングを非公開にしているプレイヤー（players.hide_stats_ranking）は除外する。 */
    public List<PlaytimeEntry> getTopPlaytimes(int limit, int offset) {
        return DatabaseManager.query(
            "SELECT p.name AS name, ps.playtime_seconds AS seconds FROM player_stats ps " +
                "JOIN players p ON p.uuid = ps.uuid WHERE p.hide_stats_ranking = 0 " +
                "ORDER BY ps.playtime_seconds DESC LIMIT ? OFFSET ?",
            rs -> new PlaytimeEntry(rs.getString("name"), rs.getLong("seconds")),
            limit, offset
        );
    }

    /** 公開プレイヤーの件数（/ranking playtime のページ数計算用）。 */
    public int getPlayerCount() {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) as cnt FROM player_stats ps JOIN players p ON p.uuid = ps.uuid WHERE p.hide_stats_ranking = 0",
            rs -> rs.getInt("cnt"));
        return count != null ? count : 0;
    }

    /** 自分より累計プレイ時間が長い公開プレイヤーの数（/ranking playtime の自分の順位用）。 */
    public int countPublicAbove(long seconds) {
        Integer count = DatabaseManager.queryOne(
            "SELECT COUNT(*) as cnt FROM player_stats ps JOIN players p ON p.uuid = ps.uuid " +
                "WHERE p.hide_stats_ranking = 0 AND ps.playtime_seconds > ?",
            rs -> rs.getInt("cnt"), seconds);
        return count != null ? count : 0;
    }
```

`getPlayerCount()` を他で使っていないか確認する: `rg -n "getPlaytimeManager\(\)\.getPlayerCount" src/main/java`。`RankingCommand` 以外で使っていれば、元のクエリを `getAllPlayerCount()` として残し、呼び出し元をそちらに変える。

- [ ] **Step 3: `messages.yml` の `ranking` を更新する**

`ranking:` ブロックの `usage`・`invalid_type` を置き換え、末尾に追加する。

```yaml
  usage: "&%c使用方法: /ranking <種類> [ページ]"
  invalid_type: "&%c不明な種類です。使える種類: &%f%types%"
```

```yaml
  stat_header: "&%6&l--- %stat%ランキング ---"
  stat_entry: "&%7#%rank% &%f%player% &%7- %value%"
  self_rank: "&%eあなたの順位: &%f%rank%位 &%7/ %total%人中（%value%）"
  self_rank_hidden: "&%8あなたの順位（非公開中のため参考）: &%7%rank%位 / %total%人中（%value%）"
  stat_names:
    mobkills: "モンスター討伐数"
    pvpkills: "プレイヤーキル数"
    deaths: "死亡回数"
    fishing: "釣り回数"
    mined: "掘ったブロック数"
    placed: "置いたブロック数"
    distance: "移動距離"
    jumps: "ジャンプ回数"
    trades: "村人との取引数"
    bred: "動物の繁殖数"
    cake: "ケーキを食べた数"
```

- [ ] **Step 4: `RankingCommand` を書き換える**

`onCommand` の種類判定・件数・表示の部分と、タブ補完を次のように置き換える。`showMoney` と `showPlaytime` と `sendPager` はそのまま残す。

```java
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("stellaria.ranking")) {
            sender.sendMessage(config.getMessage("ranking.no_permission", null));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(config.getUsageMessage("ranking.usage", null));
            return true;
        }

        String type = args[0].toLowerCase();
        boolean isStat = plugin.getStatSnapshotManager().getEnabledKeys().contains(type);
        if (!type.equals("money") && !type.equals("playtime") && !isStat) {
            sender.sendMessage(config.getMessage("ranking.invalid_type", null)
                    .replace("%types%", String.join(", ", availableTypes())));
            return true;
        }

        // page-size: 0や負数の設定ミスでも total/0.0 => Infinity にならないよう、最低1にクランプする。
        int pageSize = Math.max(1, config.getInt("ranking.page-size", 10));
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                page = 1;
            }
        }

        int totalPlayers = switch (type) {
            case "money" -> plugin.getEconomyManager().getPublicPlayerCount();
            case "playtime" -> plugin.getPlaytimeManager().getPlayerCount();
            default -> plugin.getStatSnapshotManager().getPublicCount(type);
        };
        int maxPage = Math.max(1, (int) Math.ceil(totalPlayers / (double) pageSize));
        page = Math.min(page, maxPage);
        // (page - 1) * pageSize はint同士だとpageが極端な値のときoverflowし得るのでlongで計算する。
        long offsetLong = (long) (page - 1) * pageSize;
        int offset = (int) Math.min(offsetLong, Integer.MAX_VALUE);

        if (isStat) {
            sender.sendMessage(config.getMessage("ranking.stat_header", null).replace("%stat%", statName(type)));
        } else {
            String headerKey = type.equals("money") ? "ranking.money_header" : "ranking.playtime_header";
            sender.sendMessage(config.getMessage(headerKey, null));
        }

        boolean hasEntries = switch (type) {
            case "money" -> showMoney(sender, pageSize, offset);
            case "playtime" -> showPlaytime(sender, pageSize, offset);
            default -> showStat(sender, type, pageSize, offset);
        };

        if (hasEntries) {
            sendPager(sender, type, page, maxPage);
        }
        if (sender instanceof Player player) {
            sendSelfRank(player, type, totalPlayers);
        }
        return true;
    }

    private boolean showStat(CommandSender sender, String type, int pageSize, int offset) {
        List<StatSnapshotManager.Entry> entries = plugin.getStatSnapshotManager().getTop(type, pageSize, offset);
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.empty", null));
            return false;
        }
        int rank = offset + 1;
        for (StatSnapshotManager.Entry entry : entries) {
            sender.sendMessage(plugin.getConfigManager().getMessage("ranking.stat_entry", null)
                    .replace("%rank%", String.valueOf(rank))
                    .replace("%player%", entry.name())
                    .replace("%value%", RankingFormat.value(type, entry.value())));
            rank++;
        }
        return true;
    }

    /** 一覧の下に、実行したプレイヤー自身の順位を 1 行出す。値は DB ではなくその場の最新値を使う。 */
    private void sendSelfRank(Player player, String type, int publicCount) {
        boolean hidden;
        long above;
        String value;
        switch (type) {
            case "money" -> {
                double coins = plugin.getEconomyManager().getBalance(player);
                hidden = plugin.getEconomyManager().isHideBalance(player);
                above = plugin.getEconomyManager().countPublicAbove(coins);
                value = plugin.getEconomyManager().formatExact(coins);
            }
            case "playtime" -> {
                long seconds = plugin.getPlaytimeManager().getPlaytimeSeconds(player.getUniqueId());
                hidden = plugin.getStatSnapshotManager().isHidden(player);
                above = plugin.getPlaytimeManager().countPublicAbove(seconds);
                value = DurationParser.formatDuration(seconds);
            }
            default -> {
                long live = plugin.getStatSnapshotManager().readLive(player).getOrDefault(type, 0L);
                hidden = plugin.getStatSnapshotManager().isHidden(player);
                above = plugin.getStatSnapshotManager().countPublicAbove(type, live);
                value = RankingFormat.value(type, live);
            }
        }
        String key = hidden ? "ranking.self_rank_hidden" : "ranking.self_rank";
        player.sendMessage(plugin.getConfigManager().getMessage(key, player)
                .replace("%rank%", String.valueOf(RankingFormat.rank(above)))
                .replace("%total%", String.valueOf(RankingFormat.total(publicCount, hidden)))
                .replace("%value%", value));
    }

    private String statName(String type) {
        return plugin.getConfigManager().getRawMessage("ranking.stat_names." + type);
    }

    private List<String> availableTypes() {
        List<String> types = new ArrayList<>(List.of("money", "playtime"));
        types.addAll(plugin.getStatSnapshotManager().getEnabledKeys());
        return types;
    }
```

タブ補完を置き換える。

```java
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(availableTypes(), args[0]);
        }
        return List.of();
    }
```

import を追加する: `org.bukkit.entity.Player`, `org.craftcore.stellaria.managers.StatSnapshotManager`, `org.craftcore.stellaria.utils.RankingFormat`, `java.util.ArrayList`。クラスの Javadoc の `/ranking <money|playtime> [page]` を `/ranking <種類> [page]` にし、統計ランキングと自分の順位の説明を 1 文足す。

`getRawMessage` がメッセージ未設定時に `null` を返す場合は、`statName` で `type` をそのまま返すようにする（`ConfigManager#getRawMessage` の実装を確認して合わせる）。

- [ ] **Step 5: ビルドが通ることを確認する**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/commands/RankingCommand.java src/main/java/org/craftcore/stellaria/managers/EconomyManager.java src/main/java/org/craftcore/stellaria/managers/PlaytimeManager.java src/main/resources/messages.yml
git commit -m "feat(ranking): /ranking に統計ランキングと自分の順位を追加する"
```

---

### Task 5: 設定画面のトグル

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/gui/SettingsGui.java`（全体）
- Modify: `src/main/resources/messages.yml:317-327`

**Interfaces:**
- Consumes: `StatSnapshotManager#isHidden`, `setHidden`（Task 3）

- [ ] **Step 1: `messages.yml` の `settings` に文言を追加する**

`database_error` の直前に追加する。

```yaml
  stats_ranking_visibility: "&%e統計ランキングへの掲載"
  stats_ranking_visible: "&%a現在: 掲載する"
  stats_ranking_hidden: "&%8現在: 掲載しない"
  stats_ranking_visible_enabled: "&%a統計ランキングに掲載するようにしました。"
  stats_ranking_hidden_enabled: "&%a統計ランキングに掲載しないようにしました。"
```

- [ ] **Step 2: `SettingsGui` にトグルを追加する**

スロット定数とフィールドを次のようにする（所持金を左、統計を右に並べる）。

```java
    private static final int BALANCE_VISIBILITY_SLOT = 11;
    private static final int STATS_RANKING_VISIBILITY_SLOT = 15;

    private final StellariaCore plugin;
    private boolean hideBalance;
    private boolean hideStatsRanking;
```

コンストラクタの `this.hideBalance = ...` の直後に追加する。

```java
        this.hideStatsRanking = plugin.getStatSnapshotManager().isHidden(player);
```

`onClick` の `if (event.getRawSlot() != BALANCE_VISIBILITY_SLOT) { return; }` 以降を次に置き換える。

```java
        if (event.getRawSlot() == BALANCE_VISIBILITY_SLOT) {
            boolean newValue = !hideBalance;
            if (!plugin.getEconomyManager().setHideBalance(player, newValue)) {
                player.sendMessage(plugin.getConfigManager().getMessage("settings.database_error", player));
                return;
            }
            hideBalance = newValue;
            player.sendMessage(plugin.getConfigManager().getMessage(
                    hideBalance ? "settings.balance_hidden_enabled" : "settings.balance_visible_enabled", player));
        } else if (event.getRawSlot() == STATS_RANKING_VISIBILITY_SLOT) {
            boolean newValue = !hideStatsRanking;
            if (!plugin.getStatSnapshotManager().setHidden(player, newValue)) {
                player.sendMessage(plugin.getConfigManager().getMessage("settings.database_error", player));
                return;
            }
            hideStatsRanking = newValue;
            player.sendMessage(plugin.getConfigManager().getMessage(
                    hideStatsRanking ? "settings.stats_ranking_hidden_enabled" : "settings.stats_ranking_visible_enabled", player));
        } else {
            return;
        }
        populate(player);
```

`populate` を次に置き換える。

```java
    private void populate(Player player) {
        getInventory().setItem(BALANCE_VISIBILITY_SLOT, toggleItem(player, hideBalance,
                "settings.balance_visibility", "settings.balance_visible", "settings.balance_hidden"));
        getInventory().setItem(STATS_RANKING_VISIBILITY_SLOT, toggleItem(player, hideStatsRanking,
                "settings.stats_ranking_visibility", "settings.stats_ranking_visible", "settings.stats_ranking_hidden"));
    }

    private ItemStack toggleItem(Player player, boolean hidden, String nameKey, String visibleKey, String hiddenKey) {
        ItemStack item = new ItemStack(hidden ? Material.GRAY_DYE : Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messageComponent(plugin, nameKey, player));
        meta.lore(List.of(
                messageComponent(plugin, hidden ? hiddenKey : visibleKey, player),
                messageComponent(plugin, "settings.toggle_hint", player)
        ));
        item.setItemMeta(meta);
        return item;
    }
```

クラスの Javadoc を「所持金の公開設定と統計ランキングへの掲載を切り替える個人設定画面。」に変える。

`Gui` の戻るボタンのスロットが 11 または 15 と重ならないか確認する: `rg -n "BACK|back" src/main/java/org/craftcore/stellaria/gui/Gui.java`。重なる場合は重ならない中段のスロット（10 と 16 など）に変える。

- [ ] **Step 3: ビルドが通ることを確認する**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/gui/SettingsGui.java src/main/resources/messages.yml
git commit -m "feat(settings): 統計ランキングへの掲載を切り替えるトグルを追加する"
```

---

### Task 6: ドキュメント更新と実機確認

**Files:**
- Modify: `CLAUDE.md`（テーブル一覧、`ranking` の説明、`StatSnapshotManager`）
- Modify: `docs/superpowers/specs/2026-09-26-ranking-stats-design.md`（状態を「実装済み」に）

- [ ] **Step 1: `CLAUDE.md` を更新する**

- 「Persistence」段落のテーブル一覧に `player_stat_snapshots`（`uuid`+`stat_key` 複合キー、`value`, `updated_at`）を追加し、`players` の列に `hide_balance`, `hide_stats_ranking` を追加する。
- `managers/` の説明に 1 段落追加する: 「`StatSnapshotManager` はバニラ統計を `player_stat_snapshots` に書き出す（ログアウト時・`ranking.snapshot-interval-minutes` ごと・`onDisable`）。起動時に、スナップショットのないプレイヤーの `stats/<uuid>.json` を `StatFileParser` で非同期に取り込む。`/ranking` の統計ランキングはこのテーブルを読む。キーと値の汎用テーブルなので、今後の独自進捗のカウンターもここに置く想定。」

- [ ] **Step 2: 全テストとビルドを実行する**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL、`StatDefinitionsTest`・`StatFileParserTest`・`RankingFormatTest` を含む全テストが PASS

- [ ] **Step 3: ローカルサーバーで確認する**

Run: `./gradlew runServer`（ユーザーに実行してもらう）

確認項目:
1. 初回起動のログに「統計ランキング: N 人分の過去の統計を取り込みました。」が出る（既存の `stats/*.json` がある場合）。
2. `/ranking mined` で一覧と「あなたの順位」が出る。数ブロック掘ってから再実行すると、自分の順位の値が増えている。
3. ログアウトしてログインし直すと、一覧の値も更新されている。
4. `/mine` か木こりで壊した分が `mined` に加算されている。
5. `/settings` で統計ランキングを非公開にすると、`/ranking mobkills` と `/ranking playtime` の一覧から自分が消え、「参考」の順位が出る。`/ranking money` には影響しない。
6. `/ranking foo` で使える種類の一覧が出る。タブ補完に 13 種類が出る。
7. `config.yml` の `ranking.stats` から `cake` を消して `/stellariareload` すると、`/ranking cake` が不明な種類になる。

- [ ] **Step 4: コミットする**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-09-26-ranking-stats-design.md
git commit -m "docs: 統計ランキングの仕組みを CLAUDE.md に追記する"
```
