# 独自進捗の基盤 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `advancements.yml` に書いた独自進捗を、バニラの進捗画面と `/advancements` の GUI に表示し、カウンター・統計・他の進捗の達成で判定して報酬を支払う基盤を作る。

**Architecture:** 定義の読み込み・判定・JSON 生成・登録差分の計算は Bukkit に依存しない `utils/` の純粋クラスにして単体テストする。DB 操作は `managers/AdvancementStore`（静的メソッド、一時ディレクトリの SQLite でテスト）にまとめる。`AdvancementRegistrar` が `Bukkit.getUnsafe().loadAdvancement` でバニラに登録し、`AdvancementManager` がカウンターの受け口・キャッシュ・達成処理・報酬・バニラとの同期を受け持つ。DB を正とし、バニラ進捗は表示係とする。

**Tech Stack:** Java 21, Paper API 1.21.11, SQLite（`DatabaseManager`）, Gson, Adventure（`GsonComponentSerializer`）, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-09-26-custom-advancements-design.md`

## Global Constraints

- 設定ファイルの色は `&%<char>` の独自パレットで書く（`&l` などの書式コードはそのまま）。
- 進捗の表示名・説明は `advancements.yml`、挙動の設定は `config.yml` の `advancements.*`、それ以外のプレイヤー向け文言は `messages.yml` の `advancements.*` に置く。
- ファイルに書く文章（コメント、設定ファイル、コミットメッセージ）は標準語で書く。
- ルートプロジェクトのテストは `./gradlew :test --tests <クラス名>` で実行する。
- バニラ進捗の名前空間は `stellaria`、キーは `stellaria:<タブ>/<進捗ID>`、タブのルートは `stellaria:<タブ>/root`。
- 進捗 ID とタブ ID は `[a-z0-9_]+`。`root` は進捗 ID に使えない。
- 難易度と既定値: `easy`（task・50 円・通知なし）, `normal`（task・300 円・通知なし）, `hard`（goal・1,500 円・通知あり）, `challenge`（challenge・5,000 円・通知あり）。
- 条件の種類: `counter`, `distinct`, `event`, `stat`, `all_of`, `completed`。`stat` のキーは統計ランキングの 11 キーか `playtime`（秒）。
- コマンドは `/advancements`（別名 `/adv`）。管理者権限は `stellaria.advancements.admin`（既定 op）。
- `build.gradle.kts` と `plugin.yml` の `version` は編集しない。

## Review Focus

1. **`advancements.yml` の間違い**（存在しない親・タブ・アイコン、未知の type、`all_of` や親の循環、無効になった進捗を参照する進捗） — その進捗だけが無効になり、連鎖して無効になるものも含めて警告が出て、プラグインは起動する（Task 1 のテスト）。
2. **同じ進捗の二重達成・二重報酬**（`increment` の連打、`all_of` と `completed` の連鎖、`revoke` 後の再達成） — 達成の記録と報酬の支払いはそれぞれ 1 回だけ（Task 4 のテスト）。
3. **定義を変えて再起動したとき** — 変わった進捗とその子孫だけが登録し直され、消えた進捗は削除される（Task 3 のテスト）。
4. **バニラ側だけが食い違う状態**（ワールドのデータパックが消えた、`/advancement grant` で付けられた） — ログイン時に DB に合わせて直り、報酬は払われない（Task 6 の実装と手動確認）。
5. **ログイン処理の前後に届くカウント**（キャッシュがない） — DB にだけ加算され、例外にならない（Task 6 の実装）。

---

## File Structure

| ファイル | 役割 |
|---|---|
| Create `src/main/java/org/craftcore/stellaria/utils/AdvancementDefinitions.java` | 定義の型、`advancements.yml` の読み込みと検証、親が先に来る並び替え |
| Create `src/main/java/org/craftcore/stellaria/utils/AdvancementRules.java` | 目標値・進み具合・達成判定、キー索引、隠し進捗の判定 |
| Create `src/main/java/org/craftcore/stellaria/utils/AdvancementJson.java` | バニラ進捗の JSON、キー、ハッシュ、登録差分の計算 |
| Create `src/main/java/org/craftcore/stellaria/managers/AdvancementStore.java` | 3 テーブルの作成と読み書き（静的） |
| Create `src/main/java/org/craftcore/stellaria/managers/AdvancementRegistrar.java` | バニラへの登録・削除、ハッシュの保存 |
| Create `src/main/java/org/craftcore/stellaria/managers/AdvancementManager.java` | カウンターの受け口、キャッシュ、達成処理、報酬、同期、GUI 用の問い合わせ |
| Create `src/main/java/org/craftcore/stellaria/gui/AdvancementGui.java` | トップ画面（タブ一覧と達成状況） |
| Create `src/main/java/org/craftcore/stellaria/gui/AdvancementCategoryGui.java` | カテゴリ画面（進捗一覧とページ送り） |
| Create `src/main/java/org/craftcore/stellaria/commands/AdvancementCommand.java` | `/advancements` と管理者用サブコマンド |
| Create `src/main/resources/advancements.yml` | 10 タブとサンプル進捗 |
| Modify `StellariaCore.java`, `PlayerJoinListener.java`, `PlayerListener.java`, `StatSnapshotManager.java`, `MenuGui.java`, `plugin.yml`, `config.yml`, `messages.yml`, `CLAUDE.md` | 組み込み |
| Test `src/test/java/org/craftcore/stellaria/utils/AdvancementDefinitionsTest.java`, `AdvancementRulesTest.java`, `AdvancementJsonTest.java`, `src/test/java/org/craftcore/stellaria/managers/AdvancementStoreTest.java` | 純粋ロジックと DB のテスト |

---

### Task 1: 定義の読み込みと検証

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/AdvancementDefinitions.java`
- Test: `src/test/java/org/craftcore/stellaria/utils/AdvancementDefinitionsTest.java`

**Interfaces:**
- Consumes: `StatDefinitions.all()`（統計キーの検証）
- Produces:
  - `enum AdvancementDefinitions.Difficulty { EASY, NORMAL, HARD, CHALLENGE }` — `String frame()`（`task`/`task`/`goal`/`challenge`）、`String configKey()`（小文字名）
  - `enum AdvancementDefinitions.TriggerType { COUNTER, DISTINCT, EVENT, STAT, ALL_OF, COMPLETED }`
  - `record Tab(String id, String title, String description, String icon, String background)`
  - `record Trigger(TriggerType type, String key, long goal, List<String> ids)` — `key` は不要な種類では `""`、`ids` は `ALL_OF` 以外では空。`EVENT` の `goal` は 1、`ALL_OF` の `goal` は `ids.size()`。
  - `record Definition(String id, String tab, @Nullable String parent, String icon, String title, String description, Difficulty difficulty, boolean hidden, @Nullable Long reward, Trigger trigger)`
  - `record Parsed(Map<String, Tab> tabs, List<Definition> definitions)` — `definitions` は親が子より先。`Optional<Definition> find(String id)`。
  - `static Parsed parse(ConfigurationSection root, Predicate<String> validIcon, Consumer<String> warn)`
  - `static final Set<String> STAT_KEYS`

- [ ] **Step 1: テストを書く**

File: src/test/java/org/craftcore/stellaria/utils/AdvancementDefinitionsTest.java
```java
package org.craftcore.stellaria.utils;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementDefinitionsTest {

    private static final Set<String> ICONS = Set.of("NETHER_STAR", "WOODEN_AXE", "IRON_AXE", "CLOCK");

    private final List<String> warnings = new ArrayList<>();

    private AdvancementDefinitions.Parsed parse(String advancementsYaml) {
        String yaml = """
                tabs:
                  mining:
                    title: "採掘"
                    description: "掘る"
                    icon: WOODEN_AXE
                  stellaria:
                    title: "すてらりあ"
                    description: "総合"
                    icon: NETHER_STAR
                    background: "minecraft:block/amethyst_block"
                advancements:
                """ + advancementsYaml.indent(2);
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return AdvancementDefinitions.parse(config, ICONS::contains, warnings::add);
    }

    private static List<String> ids(AdvancementDefinitions.Parsed parsed) {
        return parsed.definitions().stream().map(AdvancementDefinitions.Definition::id).toList();
    }

    @Test
    void parsesTabsWithDefaultBackground() {
        AdvancementDefinitions.Parsed parsed = parse("");
        assertEquals("minecraft:block/stone", parsed.tabs().get("mining").background());
        assertEquals("minecraft:block/amethyst_block", parsed.tabs().get("stellaria").background());
    }

    @Test
    void parsesCounterDefinition() {
        AdvancementDefinitions.Parsed parsed = parse("""
                kikori_100:
                  tab: mining
                  icon: WOODEN_AXE
                  title: "見習い木こり"
                  description: "100本"
                  difficulty: normal
                  reward: 400
                  trigger: { type: counter, key: kikori.logs, goal: 100 }
                """);
        AdvancementDefinitions.Definition def = parsed.find("kikori_100").orElseThrow();
        assertEquals(AdvancementDefinitions.Difficulty.NORMAL, def.difficulty());
        assertEquals(AdvancementDefinitions.TriggerType.COUNTER, def.trigger().type());
        assertEquals("kikori.logs", def.trigger().key());
        assertEquals(100L, def.trigger().goal());
        assertEquals(400L, def.reward());
        assertFalse(def.hidden());
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void eventGoalIsOneAndAllOfGoalIsIdCount() {
        AdvancementDefinitions.Parsed parsed = parse("""
                a:
                  tab: stellaria
                  icon: CLOCK
                  title: "A"
                  description: "a"
                  difficulty: easy
                  trigger: { type: event, key: join.count }
                b:
                  tab: stellaria
                  icon: CLOCK
                  title: "B"
                  description: "b"
                  difficulty: easy
                  trigger: { type: stat, key: playtime, goal: 3600 }
                both:
                  tab: stellaria
                  icon: NETHER_STAR
                  title: "AB"
                  description: "ab"
                  difficulty: hard
                  trigger: { type: all_of, ids: [a, b] }
                """);
        assertEquals(1L, parsed.find("a").orElseThrow().trigger().goal());
        assertEquals(2L, parsed.find("both").orElseThrow().trigger().goal());
        assertEquals(List.of("a", "b"), parsed.find("both").orElseThrow().trigger().ids());
    }

    @Test
    void invalidEntriesAreDroppedWithWarnings() {
        AdvancementDefinitions.Parsed parsed = parse("""
                bad_tab:
                  tab: nowhere
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                bad_icon:
                  tab: mining
                  icon: NOT_A_THING
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                bad_type:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: teleport, key: k }
                bad_difficulty:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: impossible
                  trigger: { type: event, key: k }
                bad_goal:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: counter, key: k, goal: 0 }
                bad_stat:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: stat, key: nothing, goal: 5 }
                Bad-Id:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                root:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                ok:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                """);
        assertEquals(List.of("ok"), ids(parsed));
        assertEquals(8, warnings.size(), warnings::toString);
    }

    @Test
    void referencesToDroppedOrMissingEntriesCascade() {
        AdvancementDefinitions.Parsed parsed = parse("""
                broken:
                  tab: mining
                  icon: NOT_A_THING
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                child:
                  tab: mining
                  parent: broken
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                grandchild:
                  tab: mining
                  parent: child
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                needs_missing:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: all_of, ids: [grandchild] }
                other_tab_parent:
                  tab: stellaria
                  parent: fine
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                fine:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                """);
        assertEquals(List.of("fine"), ids(parsed));
    }

    @Test
    void cyclesAreDropped() {
        AdvancementDefinitions.Parsed parsed = parse("""
                a:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: all_of, ids: [b] }
                b:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: all_of, ids: [a] }
                p:
                  tab: mining
                  parent: q
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                q:
                  tab: mining
                  parent: p
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                self:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: all_of, ids: [self] }
                ok:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                """);
        assertEquals(List.of("ok"), ids(parsed));
    }

    @Test
    void parentsComeBeforeChildren() {
        AdvancementDefinitions.Parsed parsed = parse("""
                child:
                  tab: mining
                  parent: base
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                base:
                  tab: mining
                  icon: CLOCK
                  title: "x"
                  description: "x"
                  difficulty: easy
                  trigger: { type: event, key: k }
                """);
        assertEquals(List.of("base", "child"), ids(parsed));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.AdvancementDefinitionsTest'`
Expected: コンパイルエラー（`AdvancementDefinitions` が存在しない）

- [ ] **Step 3: 実装する**

File: src/main/java/org/craftcore/stellaria/utils/AdvancementDefinitions.java
```java
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
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.AdvancementDefinitionsTest'`
Expected: PASS（7 件）

- [ ] **Step 5: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/utils/AdvancementDefinitions.java src/test/java/org/craftcore/stellaria/utils/AdvancementDefinitionsTest.java
git commit -m "feat(advancements): 独自進捗の定義の読み込みと検証を追加する"
```

---

### Task 2: 判定ロジック

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/AdvancementRules.java`
- Test: `src/test/java/org/craftcore/stellaria/utils/AdvancementRulesTest.java`

**Interfaces:**
- Consumes: Task 1 の `Definition`, `Trigger`, `TriggerType`
- Produces:
  - `record AdvancementRules.State(Map<String, Long> counters, Map<String, Long> distinctCounts, Set<String> completed)`
  - `static long goal(Definition def)`
  - `static long progress(Definition def, State state, ToLongFunction<String> stat, List<Definition> all)` — 値は `goal` を上限にしない（GUI 側で表示を切る）
  - `static boolean isMet(Definition def, State state, ToLongFunction<String> stat, List<Definition> all)`
  - `static Map<String, List<Definition>> indexByKey(List<Definition> all)` — `COUNTER`/`EVENT`/`DISTINCT`/`STAT` を `trigger.key` で索引
  - `static List<Definition> dependents(List<Definition> all)` — `ALL_OF` と `COMPLETED`
  - `static List<Definition> ofType(List<Definition> all, TriggerType type)`
  - `static boolean isConcealed(Definition def, boolean completed)`

- [ ] **Step 1: テストを書く**

File: src/test/java/org/craftcore/stellaria/utils/AdvancementRulesTest.java
```java
package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementRulesTest {

    private static AdvancementDefinitions.Definition def(String id, AdvancementDefinitions.TriggerType type,
                                                         String key, long goal, List<String> ids, boolean hidden) {
        return new AdvancementDefinitions.Definition(id, "tab", null, "CLOCK", id, id,
                AdvancementDefinitions.Difficulty.EASY, hidden, null,
                new AdvancementDefinitions.Trigger(type, key, goal, ids));
    }

    private static final AdvancementDefinitions.Definition COUNTER =
            def("logs", AdvancementDefinitions.TriggerType.COUNTER, "kikori.logs", 100, List.of(), false);
    private static final AdvancementDefinitions.Definition EVENT =
            def("reply", AdvancementDefinitions.TriggerType.EVENT, "msg.reply", 1, List.of(), false);
    private static final AdvancementDefinitions.Definition DISTINCT =
            def("friends", AdvancementDefinitions.TriggerType.DISTINCT, "pay.recipients", 5, List.of(), false);
    private static final AdvancementDefinitions.Definition STAT =
            def("hour", AdvancementDefinitions.TriggerType.STAT, "playtime", 3600, List.of(), false);
    private static final AdvancementDefinitions.Definition ALL_OF =
            def("both", AdvancementDefinitions.TriggerType.ALL_OF, "", 2, List.of("logs", "reply"), false);
    private static final AdvancementDefinitions.Definition COMPLETED_TWO =
            def("two", AdvancementDefinitions.TriggerType.COMPLETED, "", 2, List.of(), false);
    private static final AdvancementDefinitions.Definition COMPLETED_ONE =
            def("one", AdvancementDefinitions.TriggerType.COMPLETED, "", 1, List.of(), true);
    private static final List<AdvancementDefinitions.Definition> ALL =
            List.of(COUNTER, EVENT, DISTINCT, STAT, ALL_OF, COMPLETED_TWO, COMPLETED_ONE);

    private static AdvancementRules.State state(Map<String, Long> counters, Map<String, Long> distinct, Set<String> completed) {
        return new AdvancementRules.State(counters, distinct, completed);
    }

    private static final AdvancementRules.State EMPTY = state(Map.of(), Map.of(), Set.of());

    @Test
    void counterMeetsGoalAtThreshold() {
        assertFalse(AdvancementRules.isMet(COUNTER, state(Map.of("kikori.logs", 99L), Map.of(), Set.of()), k -> 0, ALL));
        assertTrue(AdvancementRules.isMet(COUNTER, state(Map.of("kikori.logs", 100L), Map.of(), Set.of()), k -> 0, ALL));
        assertEquals(99L, AdvancementRules.progress(COUNTER, state(Map.of("kikori.logs", 99L), Map.of(), Set.of()), k -> 0, ALL));
    }

    @Test
    void eventNeedsOneOccurrence() {
        assertEquals(1L, AdvancementRules.goal(EVENT));
        assertFalse(AdvancementRules.isMet(EVENT, EMPTY, k -> 0, ALL));
        assertTrue(AdvancementRules.isMet(EVENT, state(Map.of("msg.reply", 3L), Map.of(), Set.of()), k -> 0, ALL));
        assertEquals(1L, AdvancementRules.progress(EVENT, state(Map.of("msg.reply", 3L), Map.of(), Set.of()), k -> 0, ALL));
    }

    @Test
    void distinctUsesDistinctCounts() {
        assertTrue(AdvancementRules.isMet(DISTINCT, state(Map.of(), Map.of("pay.recipients", 5L), Set.of()), k -> 0, ALL));
        assertFalse(AdvancementRules.isMet(DISTINCT, state(Map.of("pay.recipients", 9L), Map.of(), Set.of()), k -> 0, ALL));
    }

    @Test
    void statReadsStatFunction() {
        assertTrue(AdvancementRules.isMet(STAT, EMPTY, k -> k.equals("playtime") ? 3600 : 0, ALL));
        assertFalse(AdvancementRules.isMet(STAT, EMPTY, k -> 3599, ALL));
    }

    @Test
    void allOfCountsCompletedIds() {
        assertEquals(1L, AdvancementRules.progress(ALL_OF, state(Map.of(), Map.of(), Set.of("logs")), k -> 0, ALL));
        assertTrue(AdvancementRules.isMet(ALL_OF, state(Map.of(), Map.of(), Set.of("logs", "reply")), k -> 0, ALL));
    }

    @Test
    void completedIgnoresItselfAndOtherCompletedTypes() {
        AdvancementRules.State s = state(Map.of(), Map.of(), Set.of("logs", "one", "two"));
        assertEquals(1L, AdvancementRules.progress(COMPLETED_TWO, s, k -> 0, ALL));
        assertFalse(AdvancementRules.isMet(COMPLETED_TWO, s, k -> 0, ALL));
        assertTrue(AdvancementRules.isMet(COMPLETED_TWO, state(Map.of(), Map.of(), Set.of("logs", "reply")), k -> 0, ALL));
    }

    @Test
    void indexesByKeyAndListsDependents() {
        Map<String, List<AdvancementDefinitions.Definition>> index = AdvancementRules.indexByKey(ALL);
        assertEquals(List.of(COUNTER), index.get("kikori.logs"));
        assertEquals(List.of(STAT), index.get("playtime"));
        assertEquals(List.of(ALL_OF, COMPLETED_TWO, COMPLETED_ONE), AdvancementRules.dependents(ALL));
        assertEquals(List.of(STAT), AdvancementRules.ofType(ALL, AdvancementDefinitions.TriggerType.STAT));
    }

    @Test
    void hiddenIsConcealedUntilCompleted() {
        assertTrue(AdvancementRules.isConcealed(COMPLETED_ONE, false));
        assertFalse(AdvancementRules.isConcealed(COMPLETED_ONE, true));
        assertFalse(AdvancementRules.isConcealed(COUNTER, false));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.AdvancementRulesTest'`
Expected: コンパイルエラー（`AdvancementRules` が存在しない）

- [ ] **Step 3: 実装する**

File: src/main/java/org/craftcore/stellaria/utils/AdvancementRules.java
```java
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
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.AdvancementRulesTest'`
Expected: PASS（8 件）

- [ ] **Step 5: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/utils/AdvancementRules.java src/test/java/org/craftcore/stellaria/utils/AdvancementRulesTest.java
git commit -m "feat(advancements): 独自進捗の達成判定と進み具合の計算を追加する"
```

---

### Task 3: バニラ進捗の JSON と登録差分

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/AdvancementJson.java`
- Test: `src/test/java/org/craftcore/stellaria/utils/AdvancementJsonTest.java`

**Interfaces:**
- Consumes: Task 1 の `Tab`, `Definition`, `Difficulty`
- Produces:
  - `static final String NAMESPACE = "stellaria"`, `static final String CRITERION = "done"`
  - `static String path(String tab, String id)` → `tab/id`、`static String rootPath(String tab)` → `tab/root`
  - `static String parentPath(Definition def)`
  - `static JsonObject root(Tab tab, Function<String, JsonElement> text)`
  - `static JsonObject advancement(Definition def, boolean announce, Function<String, JsonElement> text)`
  - `static String hash(JsonObject json)` — SHA-256 の 16 進
  - `record Plan(List<String> remove, List<String> load)`
  - `static Plan plan(LinkedHashMap<String, String> desiredHashes, Map<String, String> parentOf, Map<String, String> storedHashes, Set<String> existing)` — `desiredHashes` は親が先の順。`parentOf` はルートなら値なし。

- [ ] **Step 1: テストを書く**

File: src/test/java/org/craftcore/stellaria/utils/AdvancementJsonTest.java
```java
package org.craftcore.stellaria.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementJsonTest {

    private static AdvancementDefinitions.Definition def(String id, String parent,
                                                         AdvancementDefinitions.Difficulty difficulty, boolean hidden) {
        return new AdvancementDefinitions.Definition(id, "mining", parent, "IRON_AXE", "T-" + id, "D-" + id,
                difficulty, hidden, null,
                new AdvancementDefinitions.Trigger(AdvancementDefinitions.TriggerType.EVENT, "k", 1, List.of()));
    }

    private static JsonObject display(JsonObject json) {
        return json.getAsJsonObject("display");
    }

    @Test
    void advancementUsesFrameAnnounceParentAndImpossibleCriterion() {
        JsonObject json = AdvancementJson.advancement(
                def("kikori_1000", "kikori_100", AdvancementDefinitions.Difficulty.HARD, true), true, JsonPrimitive::new);
        assertEquals("stellaria:mining/kikori_100", json.get("parent").getAsString());
        assertEquals("goal", display(json).get("frame").getAsString());
        assertTrue(display(json).get("announce_to_chat").getAsBoolean());
        assertTrue(display(json).get("show_toast").getAsBoolean());
        assertTrue(display(json).get("hidden").getAsBoolean());
        assertEquals("minecraft:iron_axe", display(json).getAsJsonObject("icon").get("id").getAsString());
        assertEquals("T-kikori_1000", display(json).get("title").getAsString());
        assertEquals("minecraft:impossible",
                json.getAsJsonObject("criteria").getAsJsonObject("done").get("trigger").getAsString());
        assertEquals("[[\"done\"]]", json.get("requirements").toString());
        assertFalse(display(json).has("background"));
    }

    @Test
    void topLevelAdvancementHangsFromTabRoot() {
        JsonObject json = AdvancementJson.advancement(
                def("first", null, AdvancementDefinitions.Difficulty.EASY, false), false, JsonPrimitive::new);
        assertEquals("stellaria:mining/root", json.get("parent").getAsString());
        assertEquals("task", display(json).get("frame").getAsString());
        assertFalse(display(json).get("announce_to_chat").getAsBoolean());
    }

    @Test
    void rootHasBackgroundAndNoToastOrParent() {
        JsonObject json = AdvancementJson.root(new AdvancementDefinitions.Tab("mining", "採掘", "掘る", "DIAMOND_PICKAXE",
                "minecraft:block/stone"), JsonPrimitive::new);
        assertFalse(json.has("parent"));
        assertEquals("minecraft:block/stone", display(json).get("background").getAsString());
        assertFalse(display(json).get("show_toast").getAsBoolean());
        assertFalse(display(json).get("announce_to_chat").getAsBoolean());
    }

    @Test
    void hashChangesWithContent() {
        JsonObject a = AdvancementJson.advancement(def("x", null, AdvancementDefinitions.Difficulty.EASY, false), false, JsonPrimitive::new);
        JsonObject b = AdvancementJson.advancement(def("x", null, AdvancementDefinitions.Difficulty.HARD, false), false, JsonPrimitive::new);
        assertEquals(AdvancementJson.hash(a), AdvancementJson.hash(a.deepCopy()));
        assertNotEquals(AdvancementJson.hash(a), AdvancementJson.hash(b));
        assertEquals(64, AdvancementJson.hash(a).length());
    }

    private static LinkedHashMap<String, String> desired(String... pathsAndHashes) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndHashes.length; i += 2) {
            map.put(pathsAndHashes[i], pathsAndHashes[i + 1]);
        }
        return map;
    }

    private static final Map<String, String> PARENTS = Map.of("m/a", "m/root", "m/b", "m/a", "m/c", "m/root");

    @Test
    void unchangedRegisteredAdvancementsAreLeftAlone() {
        LinkedHashMap<String, String> want = desired("m/root", "r", "m/a", "1", "m/b", "2", "m/c", "3");
        AdvancementJson.Plan plan = AdvancementJson.plan(want, PARENTS, Map.copyOf(want), Set.copyOf(want.keySet()));
        assertEquals(List.of(), plan.remove());
        assertEquals(List.of(), plan.load());
    }

    @Test
    void changedAdvancementIsReloadedWithDescendants() {
        LinkedHashMap<String, String> want = desired("m/root", "r", "m/a", "1-new", "m/b", "2", "m/c", "3");
        Map<String, String> stored = Map.of("m/root", "r", "m/a", "1", "m/b", "2", "m/c", "3");
        AdvancementJson.Plan plan = AdvancementJson.plan(want, PARENTS, stored, Set.copyOf(want.keySet()));
        assertEquals(List.of("m/b", "m/a"), plan.remove());
        assertEquals(List.of("m/a", "m/b"), plan.load());
    }

    @Test
    void missingAdvancementIsLoadedAndStaleOnesRemoved() {
        LinkedHashMap<String, String> want = desired("m/root", "r", "m/a", "1", "m/b", "2", "m/c", "3");
        Map<String, String> stored = Map.of("m/root", "r", "m/a", "1", "m/b", "2", "m/c", "3", "m/old", "9");
        AdvancementJson.Plan plan = AdvancementJson.plan(want, PARENTS, stored, Set.of("m/root", "m/a", "m/b", "m/old"));
        assertEquals(List.of("m/old"), plan.remove());
        assertEquals(List.of("m/c"), plan.load());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.AdvancementJsonTest'`
Expected: コンパイルエラー（`AdvancementJson` が存在しない）

- [ ] **Step 3: 実装する**

File: src/main/java/org/craftcore/stellaria/utils/AdvancementJson.java
```java
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
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.AdvancementJsonTest'`
Expected: PASS（7 件）

- [ ] **Step 5: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/utils/AdvancementJson.java src/test/java/org/craftcore/stellaria/utils/AdvancementJsonTest.java
git commit -m "feat(advancements): バニラ進捗の JSON 生成と登録し直す範囲の計算を追加する"
```

---

### Task 4: DB の読み書き

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/AdvancementStore.java`
- Test: `src/test/java/org/craftcore/stellaria/managers/AdvancementStoreTest.java`

**Interfaces:**
- Produces（すべて static）:
  - `void createTables()`
  - `record Loaded(Map<String, Long> counters, Map<String, Long> distinctCounts, Set<String> completed)`
  - `Loaded load(UUID uuid)`
  - `boolean addCounter(UUID uuid, String key, long amount)`、`void addCounterAsync(UUID uuid, String key, long amount)`
  - `boolean addMember(UUID uuid, String key, String member)` — 新しい値なら true
  - `boolean recordCompletion(UUID uuid, String id, long now)` — 新たに達成（または revoke 後の再達成）なら true
  - `boolean claimReward(UUID uuid, String id, long amount)` — 未払いで達成済みなら true
  - `boolean revoke(UUID uuid, String id)` — 達成済みを未達成に戻したら true
  - `Map<String, Long> completedAt(UUID uuid)`、`long rewardTotal(UUID uuid)`

- [ ] **Step 1: テストを書く**

File: src/test/java/org/craftcore/stellaria/managers/AdvancementStoreTest.java
```java
package org.craftcore.stellaria.managers;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdvancementStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dataFolder;

    @BeforeEach
    void connect() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AdvancementStoreTest"));
        DatabaseManager.connect(plugin, "test.db");
        AdvancementStore.createTables();
    }

    @AfterEach
    void disconnect() {
        DatabaseManager.disconnect();
    }

    @Test
    void countersAccumulate() {
        assertTrue(AdvancementStore.addCounter(PLAYER, "kikori.logs", 3));
        assertTrue(AdvancementStore.addCounter(PLAYER, "kikori.logs", 4));
        assertEquals(Map.of("kikori.logs", 7L), AdvancementStore.load(PLAYER).counters());
    }

    @Test
    void distinctMembersAreCountedOnce() {
        assertTrue(AdvancementStore.addMember(PLAYER, "pay.recipients", "alice"));
        assertFalse(AdvancementStore.addMember(PLAYER, "pay.recipients", "alice"));
        assertTrue(AdvancementStore.addMember(PLAYER, "pay.recipients", "bob"));
        assertEquals(Map.of("pay.recipients", 2L), AdvancementStore.load(PLAYER).distinctCounts());
    }

    @Test
    void completionIsRecordedOnce() {
        assertTrue(AdvancementStore.recordCompletion(PLAYER, "kikori_100", 1000L));
        assertFalse(AdvancementStore.recordCompletion(PLAYER, "kikori_100", 2000L));
        assertEquals(Set.of("kikori_100"), AdvancementStore.load(PLAYER).completed());
        assertEquals(Map.of("kikori_100", 1000L), AdvancementStore.completedAt(PLAYER));
    }

    @Test
    void rewardIsClaimedOnceAndOnlyWhenCompleted() {
        assertFalse(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
        AdvancementStore.recordCompletion(PLAYER, "kikori_100", 1000L);
        assertTrue(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
        assertFalse(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
        assertEquals(300L, AdvancementStore.rewardTotal(PLAYER));
    }

    @Test
    void revokedAdvancementCanBeCompletedAgainWithoutSecondReward() {
        AdvancementStore.recordCompletion(PLAYER, "kikori_100", 1000L);
        AdvancementStore.claimReward(PLAYER, "kikori_100", 300);
        assertTrue(AdvancementStore.revoke(PLAYER, "kikori_100"));
        assertFalse(AdvancementStore.revoke(PLAYER, "kikori_100"));
        assertEquals(Set.of(), AdvancementStore.load(PLAYER).completed());
        assertTrue(AdvancementStore.recordCompletion(PLAYER, "kikori_100", 3000L));
        assertFalse(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
        assertEquals(300L, AdvancementStore.rewardTotal(PLAYER));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.managers.AdvancementStoreTest'`
Expected: コンパイルエラー（`AdvancementStore` が存在しない）

- [ ] **Step 3: 実装する**

File: src/main/java/org/craftcore/stellaria/managers/AdvancementStore.java
```java
package org.craftcore.stellaria.managers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 独自進捗の DB 操作。DB が達成状況の正であり、バニラ進捗は表示係として扱う。
 * player_advancements の completed_at = 0 は「revoke で未達成に戻した」行を表し、reward_paid はそのまま残す
 * （再び達成しても報酬を二重に払わないため）。
 */
public final class AdvancementStore {

    public record Loaded(Map<String, Long> counters, Map<String, Long> distinctCounts, Set<String> completed) {
    }

    private AdvancementStore() {
    }

    public static void createTables() {
        DatabaseManager.createTableIfNotExists("player_counters",
            "uuid TEXT NOT NULL", "counter_key TEXT NOT NULL", "value INTEGER NOT NULL",
            "PRIMARY KEY (uuid, counter_key)");
        DatabaseManager.createTableIfNotExists("player_counter_members",
            "uuid TEXT NOT NULL", "counter_key TEXT NOT NULL", "member TEXT NOT NULL",
            "PRIMARY KEY (uuid, counter_key, member)");
        DatabaseManager.createTableIfNotExists("player_advancements",
            "uuid TEXT NOT NULL", "advancement_id TEXT NOT NULL", "completed_at INTEGER NOT NULL",
            "reward_paid INTEGER NOT NULL DEFAULT 0", "reward_amount INTEGER NOT NULL DEFAULT 0",
            "PRIMARY KEY (uuid, advancement_id)");
    }

    public static Loaded load(UUID uuid) {
        String id = uuid.toString();
        Map<String, Long> counters = new HashMap<>();
        for (Map.Entry<String, Long> e : DatabaseManager.query(
                "SELECT counter_key, value FROM player_counters WHERE uuid = ?",
                rs -> Map.entry(rs.getString("counter_key"), rs.getLong("value")), id)) {
            counters.put(e.getKey(), e.getValue());
        }
        Map<String, Long> distinct = new HashMap<>();
        for (Map.Entry<String, Long> e : DatabaseManager.query(
                "SELECT counter_key, COUNT(*) AS cnt FROM player_counter_members WHERE uuid = ? GROUP BY counter_key",
                rs -> Map.entry(rs.getString("counter_key"), rs.getLong("cnt")), id)) {
            distinct.put(e.getKey(), e.getValue());
        }
        Set<String> completed = new HashSet<>(completedAt(uuid).keySet());
        return new Loaded(counters, distinct, completed);
    }

    public static boolean addCounter(UUID uuid, String key, long amount) {
        return DatabaseManager.execute(
            "INSERT INTO player_counters (uuid, counter_key, value) VALUES (?, ?, ?) "
                + "ON CONFLICT (uuid, counter_key) DO UPDATE SET value = value + excluded.value",
            uuid.toString(), key, amount) > 0;
    }

    public static void addCounterAsync(UUID uuid, String key, long amount) {
        DatabaseManager.executeAsync(
            "INSERT INTO player_counters (uuid, counter_key, value) VALUES (?, ?, ?) "
                + "ON CONFLICT (uuid, counter_key) DO UPDATE SET value = value + excluded.value",
            uuid.toString(), key, amount);
    }

    public static boolean addMember(UUID uuid, String key, String member) {
        return DatabaseManager.execute(
            "INSERT OR IGNORE INTO player_counter_members (uuid, counter_key, member) VALUES (?, ?, ?)",
            uuid.toString(), key, member) == 1;
    }

    public static boolean recordCompletion(UUID uuid, String id, long now) {
        return DatabaseManager.execute(
            "INSERT INTO player_advancements (uuid, advancement_id, completed_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (uuid, advancement_id) DO UPDATE SET completed_at = excluded.completed_at "
                + "WHERE player_advancements.completed_at = 0",
            uuid.toString(), id, now) == 1;
    }

    public static boolean claimReward(UUID uuid, String id, long amount) {
        return DatabaseManager.execute(
            "UPDATE player_advancements SET reward_paid = 1, reward_amount = ? "
                + "WHERE uuid = ? AND advancement_id = ? AND reward_paid = 0 AND completed_at > 0",
            amount, uuid.toString(), id) == 1;
    }

    public static boolean revoke(UUID uuid, String id) {
        return DatabaseManager.execute(
            "UPDATE player_advancements SET completed_at = 0 WHERE uuid = ? AND advancement_id = ? AND completed_at > 0",
            uuid.toString(), id) == 1;
    }

    /** 達成済みの進捗と達成日時（epoch millis）。 */
    public static Map<String, Long> completedAt(UUID uuid) {
        Map<String, Long> result = new HashMap<>();
        List<Map.Entry<String, Long>> rows = DatabaseManager.query(
            "SELECT advancement_id, completed_at FROM player_advancements WHERE uuid = ? AND completed_at > 0",
            rs -> Map.entry(rs.getString("advancement_id"), rs.getLong("completed_at")), uuid.toString());
        for (Map.Entry<String, Long> row : rows) {
            result.put(row.getKey(), row.getValue());
        }
        return result;
    }

    public static long rewardTotal(UUID uuid) {
        Long total = DatabaseManager.queryOne(
            "SELECT COALESCE(SUM(reward_amount), 0) AS total FROM player_advancements WHERE uuid = ? AND reward_paid = 1",
            rs -> rs.getLong("total"), uuid.toString());
        return total != null ? total : 0L;
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew :test --tests 'org.craftcore.stellaria.managers.AdvancementStoreTest'`
Expected: PASS（5 件）

- [ ] **Step 5: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/managers/AdvancementStore.java src/test/java/org/craftcore/stellaria/managers/AdvancementStoreTest.java
git commit -m "feat(advancements): 独自進捗のカウンターと達成記録の DB 操作を追加する"
```

---

### Task 5: バニラへの登録

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/AdvancementRegistrar.java`

**Interfaces:**
- Consumes: `AdvancementDefinitions.Parsed`（Task 1）、`AdvancementJson.root/advancement/hash/plan/path/rootPath/parentPath/NAMESPACE`（Task 3）
- Produces: `new AdvancementRegistrar(StellariaCore plugin)`、`void register(AdvancementDefinitions.Parsed parsed, Predicate<AdvancementDefinitions.Difficulty> announce)`

Bukkit の API だけで構成されるため単体テストはなく、ビルドと Task 8 の実機確認で検証する（差分の計算は Task 3 でテスト済み）。

- [ ] **Step 1: 実装する**

File: src/main/java/org/craftcore/stellaria/managers/AdvancementRegistrar.java
```java
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
```

- [ ] **Step 2: ビルドが通ることを確認する**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/managers/AdvancementRegistrar.java
git commit -m "feat(advancements): 独自進捗をバニラの進捗として登録する処理を追加する"
```

---

### Task 6: 進捗マネージャーと組み込み

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/AdvancementManager.java`
- Create: `src/main/resources/advancements.yml`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`（ファイル登録、テーブル作成、生成、有効化、getter）
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerJoinListener.java`（`playtimeManager.onJoin` の直後）
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java`（`onPlayerLeave`）
- Modify: `src/main/java/org/craftcore/stellaria/managers/StatSnapshotManager.java`（`snapshotAsync`）
- Modify: `src/main/resources/config.yml`（`advancements:` を追加）
- Modify: `src/main/resources/messages.yml`（`advancements.reward`）

**Interfaces:**
- Consumes: Task 1〜5 の全部、`StatSnapshotManager#readLive(Player, String)`、`PlaytimeManager#getPlaytimeSeconds(UUID)`、`EconomyManager#depositPlayer`, `formatExact`
- Produces:
  - `void enable()`、`boolean isEnabled()`
  - `void increment(Player player, String key, long amount)`、`void addDistinct(Player player, String key, String member)`
  - `void checkStats(Player player)`、`void onStatsRead(Player player, Map<String, Long> values)`
  - `void onJoin(Player player)`、`void onQuit(Player player)`
  - `AdvancementDefinitions.Parsed getParsed()`
  - `boolean isCompleted(Player player, String id)`、`long progress(Player player, AdvancementDefinitions.Definition def)`
  - `long rewardFor(AdvancementDefinitions.Definition def)`
  - `boolean grant(Player player, String id)`、`boolean revoke(Player player, String id)`
  - 組み込みカウンター `join.count`（ログインごとに 1 加算）

- [ ] **Step 1: `AdvancementManager` を実装する**

File: src/main/java/org/craftcore/stellaria/managers/AdvancementManager.java
```java
package org.craftcore.stellaria.managers;

import org.bukkit.Material;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.AdvancementDefinitions.Definition;
import org.craftcore.stellaria.utils.AdvancementDefinitions.TriggerType;
import org.craftcore.stellaria.utils.AdvancementJson;
import org.craftcore.stellaria.utils.AdvancementRules;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * 独自進捗の受け口。各機能は increment / addDistinct を呼ぶだけでよく、どの進捗がそのキーを見ているかは
 * ここで判断する。DB（AdvancementStore）が正で、バニラの進捗は表示係。オンラインのプレイヤーの状態は
 * ログイン時に同期で読み込んでキャッシュし、ログアウトで破棄する。
 */
public class AdvancementManager {

    private final StellariaCore plugin;
    private final AdvancementRegistrar registrar;
    private final Map<UUID, AdvancementRules.State> cache = new ConcurrentHashMap<>();
    private boolean enabled;
    private AdvancementDefinitions.Parsed parsed = new AdvancementDefinitions.Parsed(Map.of(), List.of());
    private Map<String, List<Definition>> byKey = Map.of();
    private List<Definition> dependents = List.of();
    private List<Definition> statDefinitions = List.of();

    public AdvancementManager(StellariaCore plugin) {
        this.plugin = plugin;
        this.registrar = new AdvancementRegistrar(plugin);
    }

    /** 起動時に 1 回呼ぶ。advancements.yml の変更は再起動で反映する（登録し直しは重いため）。 */
    public void enable() {
        enabled = plugin.getConfigManager().getBoolean("advancements.enabled", true, true);
        if (!enabled) {
            return;
        }
        parsed = AdvancementDefinitions.parse(plugin.getConfigManager().get("advancements.yml").get(),
                AdvancementManager::isItemIcon, message -> plugin.getLogger().warning(message));
        byKey = AdvancementRules.indexByKey(parsed.definitions());
        dependents = AdvancementRules.dependents(parsed.definitions());
        statDefinitions = AdvancementRules.ofType(parsed.definitions(), TriggerType.STAT);
        registrar.register(parsed, this::announces);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            onJoin(online);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public AdvancementDefinitions.Parsed getParsed() {
        return parsed;
    }

    private static boolean isItemIcon(String name) {
        Material material = Material.matchMaterial(name);
        return material != null && material.isItem() && !material.isAir();
    }

    private boolean announces(AdvancementDefinitions.Difficulty difficulty) {
        boolean fallback = difficulty == AdvancementDefinitions.Difficulty.HARD
                || difficulty == AdvancementDefinitions.Difficulty.CHALLENGE;
        return plugin.getConfigManager().getBoolean(
                "advancements.difficulties." + difficulty.configKey() + ".announce", fallback, true);
    }

    public long rewardFor(Definition def) {
        if (def.reward() != null) {
            return def.reward();
        }
        long fallback = switch (def.difficulty()) {
            case EASY -> 50L;
            case NORMAL -> 300L;
            case HARD -> 1500L;
            case CHALLENGE -> 5000L;
        };
        return plugin.getConfigManager().getInt(
                "advancements.difficulties." + def.difficulty().configKey() + ".reward", (int) fallback, true);
    }

    // ------------------------------------------------------------------
    // ログイン・ログアウト
    // ------------------------------------------------------------------

    public void onJoin(Player player) {
        if (!enabled) {
            return;
        }
        AdvancementStore.Loaded loaded = AdvancementStore.load(player.getUniqueId());
        cache.put(player.getUniqueId(), new AdvancementRules.State(
                new HashMap<>(loaded.counters()), new HashMap<>(loaded.distinctCounts()), new HashSet<>(loaded.completed())));
        syncVanilla(player);
        increment(player, "join.count", 1);
        checkStats(player);
        evaluate(player, parsed.definitions(), key -> 0L);
    }

    public void onQuit(Player player) {
        cache.remove(player.getUniqueId());
    }

    /** DB を正として、バニラ側の達成状況を合わせる。ここでは報酬を払わない。 */
    private void syncVanilla(Player player) {
        for (AdvancementDefinitions.Tab tab : parsed.tabs().values()) {
            setVanilla(player, AdvancementJson.rootPath(tab.id()), true);
        }
        Set<String> completed = cache.get(player.getUniqueId()).completed();
        for (Definition def : parsed.definitions()) {
            setVanilla(player, AdvancementJson.path(def.tab(), def.id()), completed.contains(def.id()));
        }
    }

    private void setVanilla(Player player, String path, boolean done) {
        Advancement advancement = plugin.getServer().getAdvancement(AdvancementRegistrar.key(path));
        if (advancement == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (done && !progress.isDone()) {
            progress.awardCriteria(AdvancementJson.CRITERION);
        } else if (!done && progress.isDone()) {
            progress.revokeCriteria(AdvancementJson.CRITERION);
        }
    }

    // ------------------------------------------------------------------
    // カウンターの受け口
    // ------------------------------------------------------------------

    public void increment(Player player, String key, long amount) {
        if (!enabled || amount <= 0) {
            return;
        }
        AdvancementStore.addCounterAsync(player.getUniqueId(), key, amount);
        AdvancementRules.State state = cache.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.counters().merge(key, amount, Long::sum);
        evaluate(player, byKey.getOrDefault(key, List.of()), k -> 0L);
    }

    public void addDistinct(Player player, String key, String member) {
        if (!enabled || !AdvancementStore.addMember(player.getUniqueId(), key, member)) {
            return;
        }
        AdvancementRules.State state = cache.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.distinctCounts().merge(key, 1L, Long::sum);
        evaluate(player, byKey.getOrDefault(key, List.of()), k -> 0L);
    }

    /** stat 型の進捗を、その場の統計で判定する（ログイン時）。 */
    public void checkStats(Player player) {
        if (!enabled || statDefinitions.isEmpty()) {
            return;
        }
        Map<String, Long> values = new HashMap<>();
        for (Definition def : statDefinitions) {
            values.computeIfAbsent(def.trigger().key(), key -> statValue(player, key));
        }
        evaluate(player, statDefinitions, key -> values.getOrDefault(key, 0L));
    }

    /** 統計スナップショットの書き出しで読んだ値を使って stat 型を判定する（重い統計を読み直さないため）。 */
    public void onStatsRead(Player player, Map<String, Long> values) {
        if (!enabled || statDefinitions.isEmpty() || !cache.containsKey(player.getUniqueId())) {
            return;
        }
        long playtime = plugin.getPlaytimeManager().getPlaytimeSeconds(player.getUniqueId());
        evaluate(player, statDefinitions, key -> key.equals("playtime") ? playtime : values.getOrDefault(key, 0L));
    }

    private long statValue(Player player, String key) {
        return key.equals("playtime")
                ? plugin.getPlaytimeManager().getPlaytimeSeconds(player.getUniqueId())
                : plugin.getStatSnapshotManager().readLive(player, key);
    }

    // ------------------------------------------------------------------
    // 判定と達成
    // ------------------------------------------------------------------

    private void evaluate(Player player, List<Definition> candidates, ToLongFunction<String> stat) {
        AdvancementRules.State state = cache.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        for (Definition def : candidates) {
            if (!state.completed().contains(def.id())
                    && AdvancementRules.isMet(def, state, stat, parsed.definitions())) {
                complete(player, def);
            }
        }
    }

    /** 達成を記録し、バニラに反映し、報酬を払い、依存する進捗を判定する。二重処理は DB の結果で防ぐ。 */
    private void complete(Player player, Definition def) {
        UUID uuid = player.getUniqueId();
        if (!AdvancementStore.recordCompletion(uuid, def.id(), System.currentTimeMillis())) {
            return;
        }
        AdvancementRules.State state = cache.get(uuid);
        if (state != null) {
            state.completed().add(def.id());
        }
        setVanilla(player, AdvancementJson.path(def.tab(), def.id()), true);
        long reward = rewardFor(def);
        if (AdvancementStore.claimReward(uuid, def.id(), reward) && reward > 0) {
            plugin.getEconomyManager().depositPlayer(player, reward);
            player.sendMessage(FormatUtil.replace(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("advancements.reward", player),
                    "%title%", def.title()),
                    "%amount%", plugin.getEconomyManager().formatExact(reward)));
        }
        evaluate(player, dependents, key -> 0L);
    }

    // ------------------------------------------------------------------
    // GUI・管理者用
    // ------------------------------------------------------------------

    public boolean isCompleted(Player player, String id) {
        AdvancementRules.State state = cache.get(player.getUniqueId());
        return state != null && state.completed().contains(id);
    }

    public long progress(Player player, Definition def) {
        AdvancementRules.State state = cache.get(player.getUniqueId());
        if (state == null) {
            return 0L;
        }
        return AdvancementRules.progress(def, state, key -> statValue(player, key), parsed.definitions());
    }

    /** 管理者用。通常の達成処理（報酬を含む）を行う。すでに達成済みなら false。 */
    public boolean grant(Player player, String id) {
        Definition def = parsed.find(id).orElse(null);
        if (def == null || isCompleted(player, id)) {
            return false;
        }
        complete(player, def);
        return true;
    }

    /** 管理者用。未達成に戻す。支払済みの報酬は回収せず、再達成しても二重には払わない。 */
    public boolean revoke(Player player, String id) {
        Definition def = parsed.find(id).orElse(null);
        if (def == null || !AdvancementStore.revoke(player.getUniqueId(), id)) {
            return false;
        }
        AdvancementRules.State state = cache.get(player.getUniqueId());
        if (state != null) {
            state.completed().remove(id);
        }
        setVanilla(player, AdvancementJson.path(def.tab(), def.id()), false);
        return true;
    }
}
```

`ConfigManager#getBoolean(path, def, ignoreWarn)` と `getInt(path, def, ignoreWarn)` の第 3 引数は `Boolean`（既存 API）。`advancements` が `config.yml` に無い既存サーバーでも警告を出さずに既定値で動かすため、`true` を渡す。

- [ ] **Step 2: `advancements.yml` を追加する**

File: src/main/resources/advancements.yml
```yaml
# 独自進捗の定義。変更は再起動で反映される（/stellariareload では反映されない）。
# 書式は docs/superpowers/specs/2026-09-26-custom-advancements-design.md の 3 章・4 章を参照。
#
# trigger.type:
#   counter   … key のカウンターが goal 以上
#   distinct  … key に記録された異なる値の数が goal 以上
#   event     … key のカウンターが 1 以上（1 回起きた）
#   stat      … 統計（mobkills, pvpkills, deaths, fishing, mined, placed, distance, jumps, trades, bred, cake）か
#               playtime（秒）が goal 以上
#   all_of    … ids の進捗をすべて達成
#   completed … 進捗を goal 個達成（completed 型の進捗は数えない）
# difficulty: easy / normal / hard / challenge（報酬と通知は config.yml の advancements.difficulties）

tabs:
  stellaria:
    title: "&%dすてらりあ"
    description: "サーバーを遊び尽くそう"
    icon: NETHER_STAR
    background: "minecraft:block/amethyst_block"
  economy:
    title: "&%a経済・ショップ"
    description: "お金を稼いで、使って、回そう"
    icon: EMERALD
    background: "minecraft:block/emerald_block"
  social:
    title: "&%b交流"
    description: "ほかのプレイヤーと関わろう"
    icon: WRITABLE_BOOK
    background: "minecraft:block/bookshelf"
  land:
    title: "&%2土地・建築"
    description: "自分の土地を持とう"
    icon: GRASS_BLOCK
    background: "minecraft:block/dirt"
  transport:
    title: "&%6移動・鉄道"
    description: "遠くまで旅をしよう"
    icon: POWERED_RAIL
    background: "minecraft:block/smooth_stone"
  mining:
    title: "&%a木こり・採掘"
    description: "木を切り、岩を掘る"
    icon: DIAMOND_PICKAXE
    background: "minecraft:block/stone"
  playtime:
    title: "&%eプレイ時間"
    description: "すてらりあで過ごした時間"
    icon: CLOCK
    background: "minecraft:block/quartz_block_side"
  warp_home:
    title: "&%9Warp・Home"
    description: "拠点を作って行き来しよう"
    icon: ENDER_PEARL
    background: "minecraft:block/end_stone"
  vote:
    title: "&%c投票"
    description: "みんなで決めよう"
    icon: BELL
    background: "minecraft:block/spruce_planks"
  headshop:
    title: "&%dHeadShop"
    description: "頭を集めよう"
    icon: PLAYER_HEAD
    background: "minecraft:block/black_wool"

advancements:
  welcome:
    tab: stellaria
    icon: OAK_SAPLING
    title: "&%dはじめまして"
    description: "すてらりあにログインする"
    difficulty: easy
    trigger: { type: event, key: join.count }
  playtime_1h:
    tab: playtime
    icon: CLOCK
    title: "&%eひとやすみ"
    description: "累計1時間プレイする"
    difficulty: easy
    trigger: { type: stat, key: playtime, goal: 3600 }
  playtime_10h:
    tab: playtime
    parent: playtime_1h
    icon: CLOCK
    title: "&%eすっかり常連"
    description: "累計10時間プレイする"
    difficulty: normal
    trigger: { type: stat, key: playtime, goal: 36000 }
  kikori_100:
    tab: mining
    icon: WOODEN_AXE
    title: "&%a見習い木こり"
    description: "木こりで100本伐採する"
    difficulty: normal
    trigger: { type: counter, key: kikori.logs, goal: 100 }
  first_steps:
    tab: stellaria
    parent: welcome
    icon: BOOK
    title: "&%dはじめの一歩"
    description: "「はじめまして」と「ひとやすみ」を達成する"
    difficulty: normal
    trigger: { type: all_of, ids: [welcome, playtime_1h] }
```

`kikori.logs` のカウントは第 3 段で `KikoriManager` に追加するため、この段階では「見習い木こり」は達成されない（タブとツリーの表示確認用）。

- [ ] **Step 3: `StellariaCore` に組み込む**

1. `this.configManager.register("customhead.yml");` の直後に `this.configManager.register("advancements.yml");` を追加する。
2. テーブル作成ブロックの末尾（`player_stat_snapshots` のインデックス作成の直後）に `AdvancementStore.createTables();` を追加する。
3. フィールド `private AdvancementManager advancementManager;` を `statSnapshotManager` の直後に追加し、getter `getAdvancementManager()` を `getStatSnapshotManager()` の直後に追加する。
4. `this.statSnapshotManager.backfillAsync();` の直後に `this.advancementManager = new AdvancementManager(this);` を追加する。
5. `autoBroadcastManager.start();` の直前に `advancementManager.enable();` を追加する（経済・統計・コマンドの準備がすべて終わった後で、オンラインのプレイヤーがいれば同期するため）。

import は既存の `org.craftcore.stellaria.managers.*` で足りる。

- [ ] **Step 4: ログイン・ログアウト・統計書き出しに組み込む**

`PlayerJoinListener#onJoin` の `plugin.getPlaytimeManager().onJoin(player);` の直後に追加する。

```java
        plugin.getAdvancementManager().onJoin(player);
```

`PlayerListener#onPlayerLeave` の `plugin.getStatSnapshotManager().snapshotAsync(event.getPlayer());` の直後に追加する（統計の書き出しで stat 型を判定してからキャッシュを捨てる順にする）。

```java
        plugin.getAdvancementManager().onQuit(event.getPlayer());
```

`PlayerListener.java` は CRLF のため、改行コードを保ったまま編集する。

`StatSnapshotManager#snapshotAsync` を次に置き換える。

```java
    public void snapshotAsync(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, Long> values = readLive(player);
        AdvancementManager advancements = plugin.getAdvancementManager();
        if (advancements != null) {
            advancements.onStatsRead(player, values);
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> write(uuid, values));
    }
```

- [ ] **Step 5: 設定とメッセージを追加する**

`config.yml` の `ranking:` ブロックの直後に追加する。

```yaml
# 独自進捗。進捗そのものの定義は advancements.yml に書く。
advancements:
  enabled: true
  # 難易度ごとの報酬（円）と、達成を全員のチャットに流すか。
  # announce の変更は再起動で反映される。gamerule announceAdvancements が false の場合は流れない。
  difficulties:
    easy:
      reward: 50
      announce: false
    normal:
      reward: 300
      announce: false
    hard:
      reward: 1500
      announce: true
    challenge:
      reward: 5000
      announce: true
```

`messages.yml` の末尾に追加する（GUI とコマンドの文言は Task 7 で同じブロックに足す）。

```yaml
advancements:
  reward: "&%e進捗「%title%&%e」の報酬として &%f%amount% &%eを受け取りました。"
```

- [ ] **Step 6: ビルドが通ることを確認する**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（Task 1〜4 のテストを含む全テストが PASS）

- [ ] **Step 7: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/managers/AdvancementManager.java src/main/resources/advancements.yml src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/listeners/PlayerJoinListener.java src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java src/main/java/org/craftcore/stellaria/managers/StatSnapshotManager.java src/main/resources/config.yml src/main/resources/messages.yml
git commit -m "feat(advancements): 独自進捗の判定・達成・報酬・バニラとの同期を組み込む"
```

---

### Task 7: GUI・コマンド・メニュー

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/gui/AdvancementGui.java`
- Create: `src/main/java/org/craftcore/stellaria/gui/AdvancementCategoryGui.java`
- Create: `src/main/java/org/craftcore/stellaria/commands/AdvancementCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`（コマンド登録）
- Modify: `src/main/java/org/craftcore/stellaria/gui/MenuGui.java`（`case "advancements"`）
- Modify: `src/main/resources/plugin.yml`（コマンドと権限）
- Modify: `src/main/resources/config.yml`（`menu.items` に slot 4）
- Modify: `src/main/resources/messages.yml`（`advancements.*`）

**Interfaces:**
- Consumes: `AdvancementManager#isEnabled/getParsed/isCompleted/progress/rewardFor/grant/revoke`（Task 6）、`AdvancementStore.completedAt/rewardTotal`（Task 4）、`AdvancementRules.goal/isConcealed`（Task 2）
- Produces: `new AdvancementGui(StellariaCore, Player, @Nullable Gui parent)`、`new AdvancementCategoryGui(StellariaCore, Player, Gui parent, String tabId, int page)`

- [ ] **Step 1: メッセージを追加する**

`messages.yml` の `advancements:` ブロックに追加する。

```yaml
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  disabled: "&%c独自進捗は現在無効になっています。"
  usage: "&%c使用方法: /advancements [admin <プレイヤー> <grant|revoke> <進捗ID>]"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  player_not_found: "&%cオンラインのプレイヤーを指定してください。"
  unknown_advancement: "&%c進捗 %id% は存在しません。"
  granted: "&%a%player% に進捗 %id% を付与しました。"
  already_completed: "&%7%player% は進捗 %id% を達成済みです。"
  revoked: "&%a%player% の進捗 %id% を未達成に戻しました。"
  not_completed: "&%7%player% は進捗 %id% を達成していません。"
  gui_title: "&%9&l進捗"
  gui_category_title: "&%9&l進捗 &%8- %tab%"
  gui_tab_lore: "&%7達成: &%f%done% &%7/ %total%"
  gui_summary_name: "&%e達成状況"
  gui_summary_lore:
    - "&%7達成した進捗: &%f%done% &%7/ %total%"
    - "&%7受け取った報酬: &%f%reward%"
  gui_completed_lore: "&%a達成済み &%8(%date%)"
  gui_not_completed_lore: "&%8未達成"
  gui_progress_lore: "&%7進み具合: &%f%progress% &%7/ %goal%"
  gui_reward_lore: "&%7報酬: &%f%amount%"
  gui_hidden_name: "&%8？？？"
  gui_hidden_lore: "&%8隠し進捗"
  gui_previous_page: "&%f前のページ"
  gui_next_page: "&%f次のページ"
  gui_page: "&%7%page% &%8/ &%7%max_page%"
```

- [ ] **Step 2: トップ画面を実装する**

File: src/main/java/org/craftcore/stellaria/gui/AdvancementGui.java
```java
package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.AdvancementManager;
import org.craftcore.stellaria.managers.AdvancementStore;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.GuiItemUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /advancements のトップ画面。タブごとの達成数と、全体の達成数・受け取った報酬の合計を出す。 */
public final class AdvancementGui extends Gui {

    private static final int[] TAB_SLOTS = {1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15, 16};
    private static final int SUMMARY_SLOT = 22;

    private final StellariaCore plugin;
    private final Map<Integer, String> tabBySlot = new HashMap<>();

    public AdvancementGui(StellariaCore plugin, Player player, @Nullable Gui parent) {
        super(27, FormatUtil.component(plugin.getConfigManager().getMessage("advancements.gui_title", player)), parent);
        this.plugin = plugin;
        populate(player);
    }

    private void populate(Player player) {
        AdvancementManager manager = plugin.getAdvancementManager();
        List<AdvancementDefinitions.Definition> all = manager.getParsed().definitions();
        int index = 0;
        for (AdvancementDefinitions.Tab tab : manager.getParsed().tabs().values()) {
            if (index >= TAB_SLOTS.length) {
                break;
            }
            List<AdvancementDefinitions.Definition> inTab = all.stream().filter(d -> d.tab().equals(tab.id())).toList();
            long done = inTab.stream().filter(d -> manager.isCompleted(player, d.id())).count();
            Material icon = Material.matchMaterial(tab.icon());
            int slot = TAB_SLOTS[index++];
            getInventory().setItem(slot, item(icon != null ? icon : Material.BOOK, FormatUtil.component(FormatUtil.color(tab.title())),
                    List.of(FormatUtil.component(FormatUtil.color(tab.description())),
                            message(player, "advancements.gui_tab_lore", "%done%", String.valueOf(done),
                                    "%total%", String.valueOf(inTab.size())))));
            tabBySlot.put(slot, tab.id());
        }
        long doneAll = all.stream().filter(d -> manager.isCompleted(player, d.id())).count();
        List<Component> summary = new ArrayList<>();
        for (String line : plugin.getConfigManager().getMessageList("advancements.gui_summary_lore")) {
            summary.add(FormatUtil.component(FormatUtil.text(player, line
                    .replace("%done%", String.valueOf(doneAll))
                    .replace("%total%", String.valueOf(all.size()))
                    .replace("%reward%", plugin.getEconomyManager().formatExact(AdvancementStore.rewardTotal(player.getUniqueId()))))));
        }
        getInventory().setItem(SUMMARY_SLOT, item(Material.NETHER_STAR, message(player, "advancements.gui_summary_name"), summary));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }
        if (handleBackButton(event, player)) {
            return;
        }
        String tab = tabBySlot.get(event.getRawSlot());
        if (tab != null) {
            new AdvancementCategoryGui(plugin, player, this, tab, 0).open(player);
        }
    }

    private Component message(Player player, String path, String... replacements) {
        String value = plugin.getConfigManager().getMessage(path, player);
        for (int i = 0; i < replacements.length; i += 2) {
            value = FormatUtil.replace(value, replacements[i], replacements[i + 1]);
        }
        return FormatUtil.component(value);
    }

    static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = GuiItemUtil.cleanIcon(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(GuiItemUtil.text(name));
        meta.lore(GuiItemUtil.lore(lore));
        item.setItemMeta(meta);
        return item;
    }
}
```

`FormatUtil.color` と `FormatUtil.component` の存在とシグネチャを実装時に確認する（`rg -n "public static" src/main/java/org/craftcore/stellaria/utils/FormatUtil.java`）。`FormatUtil.color(String)` が無い場合は `ColorUtil.component(String)` を直接使う。

- [ ] **Step 3: カテゴリ画面を実装する**

File: src/main/java/org/craftcore/stellaria/gui/AdvancementCategoryGui.java
```java
package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.AdvancementManager;
import org.craftcore.stellaria.managers.AdvancementStore;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.AdvancementRules;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.RankingFormat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /advancements のカテゴリ画面。タブ内の進捗を定義順に並べ、未達成は進み具合を出す。 */
public final class AdvancementCategoryGui extends Gui {

    private static final int CONTENT_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int BACK_BUTTON_SLOT = 48;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.of("Asia/Tokyo"));

    private final StellariaCore plugin;
    private final Gui parent;
    private final String tabId;
    private final int page;
    private final int maxPage;

    public AdvancementCategoryGui(StellariaCore plugin, Player player, Gui parent, String tabId, int page) {
        super(54, title(plugin, player, tabId), parent, BACK_BUTTON_SLOT);
        this.plugin = plugin;
        this.parent = parent;
        this.tabId = tabId;
        List<AdvancementDefinitions.Definition> defs = definitions(plugin, tabId);
        this.maxPage = Math.max(0, (defs.size() - 1) / CONTENT_SLOTS);
        this.page = Math.clamp(page, 0, maxPage);
        populate(player, defs);
    }

    private static List<AdvancementDefinitions.Definition> definitions(StellariaCore plugin, String tabId) {
        return plugin.getAdvancementManager().getParsed().definitions().stream()
                .filter(d -> d.tab().equals(tabId)).toList();
    }

    private static Component title(StellariaCore plugin, Player player, String tabId) {
        AdvancementDefinitions.Tab tab = plugin.getAdvancementManager().getParsed().tabs().get(tabId);
        String name = tab != null ? tab.title() : tabId;
        return FormatUtil.component(FormatUtil.replace(
                plugin.getConfigManager().getMessage("advancements.gui_category_title", player), "%tab%", FormatUtil.color(name)));
    }

    private void populate(Player player, List<AdvancementDefinitions.Definition> defs) {
        AdvancementManager manager = plugin.getAdvancementManager();
        Map<String, Long> completedAt = AdvancementStore.completedAt(player.getUniqueId());
        int first = page * CONTENT_SLOTS;
        for (int slot = 0; slot < CONTENT_SLOTS && first + slot < defs.size(); slot++) {
            AdvancementDefinitions.Definition def = defs.get(first + slot);
            boolean done = manager.isCompleted(player, def.id());
            getInventory().setItem(slot, done || !AdvancementRules.isConcealed(def, false)
                    ? entry(player, manager, def, done, completedAt.get(def.id()))
                    : AdvancementGui.item(Material.GRAY_STAINED_GLASS_PANE, message(player, "advancements.gui_hidden_name"),
                            List.of(message(player, "advancements.gui_hidden_lore"))));
        }
        if (page > 0) {
            getInventory().setItem(PREVIOUS_SLOT, AdvancementGui.item(Material.ARROW, message(player, "advancements.gui_previous_page"), List.of()));
        }
        getInventory().setItem(PAGE_SLOT, AdvancementGui.item(Material.PAPER, message(player, "advancements.gui_page",
                "%page%", String.valueOf(page + 1), "%max_page%", String.valueOf(maxPage + 1)), List.of()));
        if (page < maxPage) {
            getInventory().setItem(NEXT_SLOT, AdvancementGui.item(Material.ARROW, message(player, "advancements.gui_next_page"), List.of()));
        }
    }

    private ItemStack entry(Player player, AdvancementManager manager, AdvancementDefinitions.Definition def,
                            boolean done, Long completedAt) {
        List<Component> lore = new ArrayList<>();
        lore.add(FormatUtil.component(FormatUtil.color(def.description())));
        if (done) {
            lore.add(message(player, "advancements.gui_completed_lore", "%date%",
                    completedAt != null ? DATE.format(Instant.ofEpochMilli(completedAt)) : "-"));
        } else {
            lore.add(message(player, "advancements.gui_not_completed_lore"));
            long goal = AdvancementRules.goal(def);
            if (goal > 1) {
                long progress = Math.min(goal, manager.progress(player, def));
                lore.add(message(player, "advancements.gui_progress_lore",
                        "%progress%", RankingFormat.value("", progress), "%goal%", RankingFormat.value("", goal)));
            }
        }
        lore.add(message(player, "advancements.gui_reward_lore", "%amount%",
                plugin.getEconomyManager().formatExact(manager.rewardFor(def))));
        Material icon = done ? Material.matchMaterial(def.icon()) : Material.GRAY_DYE;
        ItemStack item = AdvancementGui.item(icon != null ? icon : Material.BOOK,
                FormatUtil.component(FormatUtil.color(def.title())), lore);
        if (done) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }
        if (handleBackButton(event, player)) {
            return;
        }
        if (event.getRawSlot() == PREVIOUS_SLOT && page > 0) {
            new AdvancementCategoryGui(plugin, player, parent, tabId, page - 1).open(player);
        } else if (event.getRawSlot() == NEXT_SLOT && page < maxPage) {
            new AdvancementCategoryGui(plugin, player, parent, tabId, page + 1).open(player);
        }
    }

    private Component message(Player player, String path, String... replacements) {
        String value = plugin.getConfigManager().getMessage(path, player);
        for (int i = 0; i < replacements.length; i += 2) {
            value = FormatUtil.replace(value, replacements[i], replacements[i + 1]);
        }
        return FormatUtil.component(value);
    }
}
```

- [ ] **Step 4: コマンドを実装する**

File: src/main/java/org/craftcore/stellaria/commands/AdvancementCommand.java
```java
package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.AdvancementGui;
import org.craftcore.stellaria.managers.AdvancementManager;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /advancements: 独自進捗の GUI を開く。admin サブコマンドで進捗の付与・取り消しを行う。 */
public class AdvancementCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "stellaria.advancements.admin";

    private final StellariaCore plugin;

    public AdvancementCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        AdvancementManager manager = plugin.getAdvancementManager();
        if (!manager.isEnabled()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("advancements.disabled", null));
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.getConfigManager().getMessage("advancements.must_be_player", null));
                return true;
            }
            new AdvancementGui(plugin, player, null).open(player);
            return true;
        }
        if (!args[0].equalsIgnoreCase("admin") || args.length != 4
                || !(args[2].equalsIgnoreCase("grant") || args[2].equalsIgnoreCase("revoke"))) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("advancements.usage", null));
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("advancements.no_permission", null));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.getConfigManager().getMessage("advancements.player_not_found", null));
            return true;
        }
        String id = args[3];
        if (manager.getParsed().find(id).isEmpty()) {
            sender.sendMessage(reply("advancements.unknown_advancement", target, id));
            return true;
        }
        boolean grant = args[2].equalsIgnoreCase("grant");
        boolean changed = grant ? manager.grant(target, id) : manager.revoke(target, id);
        String key = grant
                ? (changed ? "advancements.granted" : "advancements.already_completed")
                : (changed ? "advancements.revoked" : "advancements.not_completed");
        sender.sendMessage(reply(key, target, id));
        return true;
    }

    private String reply(String path, Player target, String id) {
        return FormatUtil.replace(plugin.getConfigManager().getMessage(path, target), "%id%", id);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        return switch (args.length) {
            case 1 -> TabCompleteUtil.filterStartsWith(List.of("admin"), args[0]);
            case 2 -> TabCompleteUtil.filterStartsWith(
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            case 3 -> TabCompleteUtil.filterStartsWith(List.of("grant", "revoke"), args[2]);
            case 4 -> TabCompleteUtil.filterStartsWith(plugin.getAdvancementManager().getParsed().definitions().stream()
                    .map(AdvancementDefinitions.Definition::id).toList(), args[3]);
            default -> List.of();
        };
    }
}
```

`%player%` は `getMessage(path, target)` で対象プレイヤーの名前に置き換わる（TPA の修正で確認した `ConfigManager#getMessage` の挙動）。

- [ ] **Step 5: 登録とメニューに組み込む**

`plugin.yml` の `commands:` に追加する（`ranking:` の直後）。

```yaml
  advancements:
    aliases: [adv]
```

`plugin.yml` の `permissions:` に追加する（`stellaria.ranking:` の直前）。

```yaml
  stellaria.advancements.admin:
    default: op
```

`StellariaCore` のコマンド登録（`getCommand("ranking").setTabCompleter(rankingCommand);` の直後）に追加する。

```java
        AdvancementCommand advancementCommand = new AdvancementCommand(this);
        getCommand("advancements").setExecutor(advancementCommand);
        getCommand("advancements").setTabCompleter(advancementCommand);
```

import に `org.craftcore.stellaria.commands.AdvancementCommand` を追加する（既存の commands の import 形式に合わせる）。

`MenuGui` の `switch (entry.action())` に追加する（`case "headshop"` の直後）。

```java
            case "advancements" -> new AdvancementGui(plugin, player, this).open(player);
```

`config.yml` の `menu.items` の `slot: 0` の項目の直後に追加する。

```yaml
    - slot: 4
      material: NETHER_STAR
      name: "&%d進捗"
      action: "advancements"
```

- [ ] **Step 6: ビルドが通ることを確認する**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/gui/AdvancementGui.java src/main/java/org/craftcore/stellaria/gui/AdvancementCategoryGui.java src/main/java/org/craftcore/stellaria/commands/AdvancementCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/gui/MenuGui.java src/main/resources/plugin.yml src/main/resources/config.yml src/main/resources/messages.yml
git commit -m "feat(advancements): /advancements の GUI と管理者用コマンドを追加する"
```

---

### Task 8: ドキュメント更新と実機確認

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-09-26-custom-advancements-design.md`（状態を「実装済み」に）

- [ ] **Step 1: `CLAUDE.md` を更新する**

- 「Persistence」段落のテーブル一覧に `player_counters`（`uuid`+`counter_key`）, `player_counter_members`（`uuid`+`counter_key`+`member`）, `player_advancements`（`uuid`+`advancement_id`, `completed_at`（0 は revoke 済み）, `reward_paid`, `reward_amount`）を追加する。
- 「All user-facing text lives in messages.yml」の段落に、例外として `advancements.yml`（進捗の表示名と説明を定義と同じ場所に置くため）を追記する。
- 次の段落を追加する: 「**Custom advancements are DB-first; vanilla advancements are only the display.** `advancements.yml` defines tabs and advancements (`utils/AdvancementDefinitions` validates and drops broken entries with warnings). `AdvancementRegistrar` registers them via the deprecated `Bukkit.getUnsafe().loadAdvancement` under `stellaria:<tab>/<id>` and only re-registers entries whose JSON hash changed (stored in `advancements-registered.yml`), because a re-registered advancement re-toasts for players who already had it. Feature code only calls `AdvancementManager#increment(player, key, n)` / `addDistinct(player, key, member)`; which advancements watch a key is decided from the definitions. Completion, reward (`EconomyManager`, once per advancement via `reward_paid`) and `all_of`/`completed` chaining happen in `AdvancementManager#complete`; on join the vanilla state is re-synced from the DB without paying rewards. `stat` triggers are evaluated on join and whenever `StatSnapshotManager` snapshots a player. Built-in counter: `join.count`. Changes to `advancements.yml` take effect on restart, not `/stellariareload`.」

- [ ] **Step 2: 全テストとビルドを実行する**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL、全テスト PASS

- [ ] **Step 3: ローカルサーバーで確認する**

Run: `./gradlew runServer`（ユーザーに実行してもらう。`run/plugins/` に古い StellariaCore の jar が残っていれば先に削除する）

確認項目:
1. 起動ログに「独自進捗: N 件を登録し…」が出る。2 回目の起動では出ない（変更なし）。
2. L キーで 10 個のタブが出て、背景が表示される。
3. 初回ログインで「はじめまして」のトーストが出て、50 円が入金され、報酬のメッセージが出る。チャット通知は出ない（easy）。
4. `/advancements admin <自分> grant playtime_1h` で「ひとやすみ」が付き、続けて「はじめの一歩」（all_of）も自動で達成される。
5. `/advancements` のトップ画面にタブと達成数が出て、カテゴリ画面で進み具合（`playtime_10h` なら `x / 36,000`）が出る。
6. `/advancements admin <自分> revoke playtime_1h` のあと再び grant しても、報酬が二重に払われない。
7. `/advancement grant <自分> only stellaria:mining/kikori_100` で付けてから再ログインすると、未達成に戻る。
8. `advancements.yml` のサンプル 1 件の title を変えて再起動すると、その 1 件（と子孫）だけが登録し直される。

- [ ] **Step 4: コミットする**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-09-26-custom-advancements-design.md
git commit -m "docs: 独自進捗の基盤を CLAUDE.md に追記する"
```
