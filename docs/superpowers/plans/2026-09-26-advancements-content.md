# 独自進捗の中身（第 3 段） Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 承認済みの 116 個の進捗を `advancements.yml` に定義し、各機能から判定用のカウンターを記録する。

**Architecture:** 条件の種類は増やさず、各機能から `AdvancementManager` の受け口（UUID 版を追加し、どのスレッドからでも呼べるようにする）へ `event` / `counter` / `distinct` を記録する。新しい仕組み（ログイン日、日ごとの売上、乗車記録、日ごとのチャット数）は、計算部分を純粋関数（`LoginDays`, `RailRideRecord`）と静的 DB クラス（`LoginDaysStore`, `AdvancementStore.addCounterAndGet`）に切り出して単体テストする。

**Tech Stack:** Java 21, Paper API 1.21.11, SQLite, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-09-26-advancements-content-design.md`

## Global Constraints

- キー名は設計書 3 章の表のとおり。ただし木こりは `kikori.trees`（伐採が最後まで終わった木の本数）とする（Ruling: 元の案が「100 本の木」で、原木の個数だと 1 本の木で 5〜30 増えてしまうため）。
- 日付はすべて日本時間（`Asia/Tokyo`）の `yyyy-MM-dd`（`LocalDate`）。
- 自分のショップでの購入・自分の Warp の利用は、他人向けのカウンター（`shop.sold_*`, `shop.customers`, `shop.bought_other`, `warp.my_used` など）に数えない。
- 記録は処理が成功した後（DB のトランザクション確定後）に行う。
- 既存ファイルの改行コード（CRLF のファイルがある）を保つ。
- ファイルに書く文章は標準語。テストは `./gradlew :test --tests <クラス>`。

## Review Focus

1. **非同期スレッドからの記録**（`AsyncChatEvent`、Votifier、Home/Warp の非同期コールバック） — Player API とキャッシュの操作はメインスレッドで行われ、例外にならない（Task 3）。
2. **オフラインの相手への記録**（売れたショップのオーナー、送金の受取人、土地のメンバーに追加された人、Warp のオーナー） — DB に記録され、次のログインで判定される（Task 3 と各フック）。
3. **自分自身との取引・自分の Warp** — 他人向けの進捗が進まない（Task 5, 9）。
4. **乗車が到着以外で終わった場合**（降車、脱線、切断） — `rail.*` は記録されない（Task 8）。
5. **日付の境目**（連続ログイン、日ごとの売上・チャット） — 日本時間の 0 時で区切られる（Task 1, 3）。

---

### Task 1: ログイン日の計算と保存

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/LoginDays.java`
- Create: `src/main/java/org/craftcore/stellaria/managers/LoginDaysStore.java`
- Test: `src/test/java/org/craftcore/stellaria/utils/LoginDaysTest.java`
- Test: `src/test/java/org/craftcore/stellaria/managers/LoginDaysStoreTest.java`

**Interfaces:**
- Produces: `static int LoginDays.streak(Set<LocalDate> days, LocalDate today)`、`static int LoginDays.countWithin(Set<LocalDate> days, LocalDate today, int windowDays)`、`static LocalDate LoginDays.today()`（日本時間）
- Produces: `static void LoginDaysStore.createTable()`、`static void LoginDaysStore.record(UUID, LocalDate)`、`static Set<LocalDate> LoginDaysStore.since(UUID, LocalDate from)`

- [ ] **Step 1: テストを書く**

File: src/test/java/org/craftcore/stellaria/utils/LoginDaysTest.java
```java
package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoginDaysTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

    private static Set<LocalDate> daysBack(int... offsets) {
        Set<LocalDate> days = new HashSet<>();
        for (int offset : offsets) {
            days.add(TODAY.minusDays(offset));
        }
        return days;
    }

    @Test
    void streakCountsConsecutiveDaysEndingToday() {
        assertEquals(3, LoginDays.streak(daysBack(0, 1, 2, 4), TODAY));
        assertEquals(0, LoginDays.streak(daysBack(1, 2), TODAY));
        assertEquals(7, LoginDays.streak(daysBack(0, 1, 2, 3, 4, 5, 6), TODAY));
    }

    @Test
    void streakCrossesMonthBoundary() {
        assertEquals(5, LoginDays.streak(daysBack(0, 1, 2, 3, 4), TODAY)); // 9/29〜10/3
    }

    @Test
    void countWithinIncludesTodayAndExcludesOlderDays() {
        assertEquals(2, LoginDays.countWithin(daysBack(0, 29, 30), TODAY, 30));
        assertEquals(20, LoginDays.countWithin(daysBack(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19), TODAY, 30));
    }
}
```

File: src/test/java/org/craftcore/stellaria/managers/LoginDaysStoreTest.java
```java
package org.craftcore.stellaria.managers;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginDaysStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dataFolder;

    @BeforeEach
    void connect() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LoginDaysStoreTest"));
        DatabaseManager.connect(plugin, "test.db");
        LoginDaysStore.createTable();
    }

    @AfterEach
    void disconnect() {
        DatabaseManager.disconnect();
    }

    @Test
    void recordsEachDayOnceAndReadsSince() {
        LocalDate d1 = LocalDate.of(2026, 9, 1);
        LocalDate d2 = LocalDate.of(2026, 9, 20);
        LocalDate d3 = LocalDate.of(2026, 9, 21);
        LoginDaysStore.record(PLAYER, d1);
        LoginDaysStore.record(PLAYER, d2);
        LoginDaysStore.record(PLAYER, d2);
        LoginDaysStore.record(PLAYER, d3);
        assertEquals(Set.of(d2, d3), LoginDaysStore.since(PLAYER, d2));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する** — Run: `./gradlew :test --tests 'org.craftcore.stellaria.utils.LoginDaysTest' --tests 'org.craftcore.stellaria.managers.LoginDaysStoreTest'` / Expected: コンパイルエラー

- [ ] **Step 3: 実装する**

File: src/main/java/org/craftcore/stellaria/utils/LoginDays.java
```java
package org.craftcore.stellaria.utils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/** 連続ログイン日数と、直近 N 日のうちログインした日数の計算。日付は日本時間。 */
public final class LoginDays {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private LoginDays() {
    }

    public static LocalDate today() {
        return LocalDate.now(JAPAN);
    }

    /** today を含めて、さかのぼって連続している日数。today にログインしていなければ 0。 */
    public static int streak(Set<LocalDate> days, LocalDate today) {
        int count = 0;
        LocalDate day = today;
        while (days.contains(day)) {
            count++;
            day = day.minusDays(1);
        }
        return count;
    }

    /** today を含む直近 windowDays 日のうち、ログインした日数。 */
    public static int countWithin(Set<LocalDate> days, LocalDate today, int windowDays) {
        LocalDate from = today.minusDays(windowDays - 1L);
        return (int) days.stream().filter(day -> !day.isBefore(from) && !day.isAfter(today)).count();
    }
}
```

File: src/main/java/org/craftcore/stellaria/managers/LoginDaysStore.java
```java
package org.craftcore.stellaria.managers;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** ログインした日（日本時間）の記録。連続ログインと直近 30 日の進捗に使う。 */
public final class LoginDaysStore {

    private LoginDaysStore() {
    }

    public static void createTable() {
        DatabaseManager.createTableIfNotExists("player_login_days",
            "uuid TEXT NOT NULL", "day TEXT NOT NULL", "PRIMARY KEY (uuid, day)");
    }

    public static void record(UUID uuid, LocalDate day) {
        DatabaseManager.execute("INSERT OR IGNORE INTO player_login_days (uuid, day) VALUES (?, ?)",
            uuid.toString(), day.toString());
    }

    public static Set<LocalDate> since(UUID uuid, LocalDate from) {
        return new HashSet<>(DatabaseManager.query(
            "SELECT day FROM player_login_days WHERE uuid = ? AND day >= ?",
            rs -> LocalDate.parse(rs.getString("day")), uuid.toString(), from.toString()));
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**（同じコマンド / Expected: PASS 4 件）
- [ ] **Step 5: コミットする** — `feat(advancements): ログイン日の記録と連続日数の計算を追加する`

---

### Task 2: 高速鉄道の乗車判定

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/RailRideRecord.java`
- Test: `src/test/java/org/craftcore/stellaria/utils/RailRideRecordTest.java`

**Interfaces:**
- Produces: `static boolean RailRideRecord.isFullLine(List<String> stationsInOrder, boolean oneWay, @Nullable String departure, String arrival)`（大文字小文字は区別しない）

- [ ] **Step 1: テストを書く**

File: src/test/java/org/craftcore/stellaria/utils/RailRideRecordTest.java
```java
package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailRideRecordTest {

    private static final List<String> LINE = List.of("Spawn", "Village", "Mine", "Port");

    @Test
    void endToEndIsFullLineBothWaysOnTwoWayLine() {
        assertTrue(RailRideRecord.isFullLine(LINE, false, "spawn", "Port"));
        assertTrue(RailRideRecord.isFullLine(LINE, false, "Port", "Spawn"));
    }

    @Test
    void oneWayLineCountsOnlyForward() {
        assertTrue(RailRideRecord.isFullLine(LINE, true, "Spawn", "Port"));
        assertFalse(RailRideRecord.isFullLine(LINE, true, "Port", "Spawn"));
    }

    @Test
    void partialRidesAndUnknownDepartureDoNotCount() {
        assertFalse(RailRideRecord.isFullLine(LINE, false, "Village", "Port"));
        assertFalse(RailRideRecord.isFullLine(LINE, false, null, "Port"));
        assertFalse(RailRideRecord.isFullLine(List.of("Solo"), false, "Solo", "Solo"));
    }
}
```

- [ ] **Step 2: 失敗を確認** — `./gradlew :test --tests 'org.craftcore.stellaria.utils.RailRideRecordTest'` / コンパイルエラー

- [ ] **Step 3: 実装する**

File: src/main/java/org/craftcore/stellaria/utils/RailRideRecord.java
```java
package org.craftcore.stellaria.utils;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 高速鉄道の 1 回の乗車が「始発から終着まで」かどうかの判定。 */
public final class RailRideRecord {

    private RailRideRecord() {
    }

    public static boolean isFullLine(List<String> stationsInOrder, boolean oneWay, @Nullable String departure, String arrival) {
        if (departure == null || stationsInOrder.size() < 2) {
            return false;
        }
        String first = stationsInOrder.getFirst();
        String last = stationsInOrder.getLast();
        if (departure.equalsIgnoreCase(first) && arrival.equalsIgnoreCase(last)) {
            return true;
        }
        return !oneWay && departure.equalsIgnoreCase(last) && arrival.equalsIgnoreCase(first);
    }
}
```

- [ ] **Step 4: 通過を確認**（PASS 3 件）
- [ ] **Step 5: コミット** — `feat(advancements): 高速鉄道の全線走破の判定を追加する`

---

### Task 3: 受け口の拡張（UUID 版・スレッド安全・日ごとの集計・ログイン時の判定）

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/AdvancementStore.java`（`addCounterAndGet`）
- Modify: `src/main/java/org/craftcore/stellaria/managers/AdvancementManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`（`LoginDaysStore.createTable()`）
- Test: `src/test/java/org/craftcore/stellaria/managers/AdvancementStoreTest.java`（追記）

**Interfaces:**
- Produces（`AdvancementManager`）:
  - `void event(UUID uuid, String key)` — `addToCounter(uuid, key, 1)`
  - `void addToCounter(UUID uuid, String key, long amount)` — 既存。メインスレッド以外から呼ばれたらメインへ移す
  - `void addDistinct(UUID uuid, String key, String member)` — オフライン可。DB は呼び出したスレッドで同期挿入、キャッシュと判定はメイン
  - `void increment(Player, String, long)`, `void addDistinct(Player, String, String)` — UUID 版に委譲
  - `void onChat(Player player)` — 非同期から呼べる。`chat.messages` と日ごとの回数（100 で `chat.day100`）
  - `long addDaily(UUID uuid, String prefix, long amount)` — `prefix + "." + 今日` に同期で加算し、加算後の値を返す
- Produces（`AdvancementStore`）: `static long addCounterAndGet(UUID, String, long)`

- [ ] **Step 1: `AdvancementStoreTest` にテストを追加する**

```java
    @Test
    void addCounterAndGetReturnsNewTotal() {
        assertEquals(40L, AdvancementStore.addCounterAndGet(PLAYER, "shop.sales.2026-10-03", 40));
        assertEquals(100L, AdvancementStore.addCounterAndGet(PLAYER, "shop.sales.2026-10-03", 60));
    }
```

- [ ] **Step 2: 失敗を確認**（コンパイルエラー）

- [ ] **Step 3: `AdvancementStore` に実装する**

```java
    /** 同期で加算して、加算後の値を返す（日ごとの売上のように、その場でしきい値を判定したい値用）。 */
    public static long addCounterAndGet(UUID uuid, String key, long amount) {
        addCounter(uuid, key, amount);
        Long value = DatabaseManager.queryOne(
            "SELECT value FROM player_counters WHERE uuid = ? AND counter_key = ?",
            rs -> rs.getLong("value"), uuid.toString(), key);
        return value != null ? value : 0L;
    }
```

- [ ] **Step 4: `AdvancementManager` を拡張する**

1. メインスレッドへ移す補助を追加し、`addToCounter` の既存の `isPrimaryThread` 分岐をこれに置き換える。

```java
    private void onMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    public void event(UUID uuid, String key) {
        addToCounter(uuid, key, 1);
    }
```

2. `addDistinct(Player, ...)` を UUID 版に置き換える。

```java
    public void addDistinct(Player player, String key, String member) {
        addDistinct(player.getUniqueId(), key, member);
    }

    /** オフラインの相手にも使える。新しい値だったときだけ、オンラインならキャッシュに反映して判定する。 */
    public void addDistinct(UUID uuid, String key, String member) {
        if (!AdvancementStore.addMember(uuid, key, member) || !enabled) {
            return;
        }
        onMain(() -> {
            Player player = plugin.getServer().getPlayer(uuid);
            AdvancementRules.State state = cache.get(uuid);
            if (player == null || state == null) {
                return;
            }
            state.distinctCounts().merge(key, 1L, Long::sum);
            evaluate(player, byKey.getOrDefault(key, List.of()), k -> 0L);
        });
    }
```

（`addDistinct` は独自進捗が無効でも DB に記録する。`addToCounter` と同じ方針。）

3. 日ごとの集計を追加する。

```java
    /** prefix.<日本時間の日付> に同期で加算して、その日の合計を返す。 */
    public long addDaily(UUID uuid, String prefix, long amount) {
        return AdvancementStore.addCounterAndGet(uuid, prefix + "." + LoginDays.today(), amount);
    }

    private final Map<UUID, long[]> chatToday = new ConcurrentHashMap<>(); // [epochDay, count]

    /** AsyncChatEvent から呼ばれる。日ごとの回数はメモリだけで数える（再起動でその日の回数はリセット）。 */
    public void onChat(Player player) {
        UUID uuid = player.getUniqueId();
        addToCounter(uuid, "chat.messages", 1);
        long today = LoginDays.today().toEpochDay();
        long[] entry = chatToday.compute(uuid, (id, old) ->
                old == null || old[0] != today ? new long[]{today, 1} : new long[]{today, old[1] + 1});
        if (entry[1] == 100) {
            event(uuid, "chat.day100");
        }
    }
```

`onQuit` に `chatToday.remove(player.getUniqueId());` を追加する。

4. `onJoin` の `increment(player, "join.count", 1);` の直後に追加する。

```java
        recordJoinFacts(player);
```

```java
    /** ログイン時にわかる事実（時刻・初ログインからの日数・ログイン日）を記録する。 */
    private void recordJoinFacts(Player player) {
        UUID uuid = player.getUniqueId();
        if (java.time.LocalTime.now(java.time.ZoneId.of("Asia/Tokyo")).getHour() == 3) {
            event(uuid, "join.3am");
        }
        long firstPlayed = player.getFirstPlayed();
        if (firstPlayed > 0) {
            long days = (System.currentTimeMillis() - firstPlayed) / 86_400_000L;
            if (days >= 7) event(uuid, "account.age7");
            if (days >= 30) event(uuid, "account.age30");
            if (days >= 100) event(uuid, "account.age100");
        }
        recordLoginDay(uuid);
    }

    private void recordLoginDay(UUID uuid) {
        LocalDate today = LoginDays.today();
        LoginDaysStore.record(uuid, today);
        Set<LocalDate> days = LoginDaysStore.since(uuid, today.minusDays(30));
        if (LoginDays.streak(days, today) >= 7) event(uuid, "login.streak7");
        if (LoginDays.countWithin(days, today, 30) >= 20) event(uuid, "login.active20of30");
    }
```

`event` は達成済みでもカウンターを 1 増やすだけなので、毎回のログインで呼んでよい。

5. 日付をまたいでオンラインのプレイヤーの当日分を記録するため、`enable()` の最後に 10 分ごとのタスクを追加する。

```java
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (cache.containsKey(online.getUniqueId())) {
                    recordLoginDay(online.getUniqueId());
                }
            }
        }, 12_000L, 12_000L);
```

import を追加する: `org.craftcore.stellaria.utils.LoginDays`, `java.time.LocalDate`（`Set`, `Map`, `ConcurrentHashMap` は既存）。

6. `StellariaCore` の `AdvancementStore.createTables();` の直後に `LoginDaysStore.createTable();` を追加する。

- [ ] **Step 5: テストとビルド** — `./gradlew build` / Expected: BUILD SUCCESSFUL
- [ ] **Step 6: コミット** — `feat(advancements): 受け口を UUID 版・非同期対応にし、ログイン時の判定と日ごとの集計を追加する`

---

### Task 4: `advancements.yml` の 116 個の定義

**Files:**
- Modify: `src/main/resources/advancements.yml`（作業ツリーに作成済み。Ruling: 計画作成時に先に書き出した。内容は設計書の一覧どおり）
- Test: `src/test/java/org/craftcore/stellaria/utils/AdvancementsYamlTest.java`

- [ ] **Step 1: テストを書く**

File: src/test/java/org/craftcore/stellaria/utils/AdvancementsYamlTest.java
```java
package org.craftcore.stellaria.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementsYamlTest {

    private static final Set<String> SAMPLE_IDS = Set.of("welcome", "first_steps", "playtime_1h", "playtime_10h", "kikori_100");

    @Test
    void bundledDefinitionsLoadWithoutWarnings() throws Exception {
        YamlConfiguration yaml;
        try (var in = new InputStreamReader(getClass().getResourceAsStream("/advancements.yml"), StandardCharsets.UTF_8)) {
            yaml = YamlConfiguration.loadConfiguration(in);
        }
        List<String> warnings = new ArrayList<>();
        AdvancementDefinitions.Parsed parsed = AdvancementDefinitions.parse(yaml,
                name -> org.bukkit.Material.matchMaterial(name) != null, warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(10, parsed.tabs().size());
        assertEquals(116, parsed.definitions().size());
        assertEquals(116, yaml.getConfigurationSection("advancements").getKeys(false).size());
        for (String id : SAMPLE_IDS) {
            assertTrue(parsed.find(id).isPresent(), id);
        }
        assertEquals(15, parsed.definitions().stream().filter(d -> d.tab().equals("stellaria")).count());
        assertEquals(8, parsed.definitions().stream().filter(AdvancementDefinitions.Definition::hidden).count());
    }
}
```

- [ ] **Step 2: 失敗を確認する** — 基盤のサンプル 5 個だけの `advancements.yml` に戻した状態（`git stash` を使わず、`git show HEAD:src/main/resources/advancements.yml` の内容で一時的に確認）でテストが `116` の件数で失敗することを確認し、作成済みの 116 個の内容に戻す。
- [ ] **Step 3: 通過を確認** — `./gradlew :test --tests 'org.craftcore.stellaria.utils.AdvancementsYamlTest'` / PASS
- [ ] **Step 4: コミット** — `feat(advancements): 116 個の進捗を定義する`

---

### Task 5: 経済・ショップのフック

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/EconomyManager.java`（`checkZero`、`withdrawPlayer`、`transfer`）
- Modify: `src/main/java/org/craftcore/stellaria/commands/PayCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/ShopManager.java`（`create`、購入、売却）

- [ ] **Step 1: `EconomyManager` に残高 0 の判定を追加する**

```java
    /** 出金の後に呼ぶ。残高がちょうど 0 になっていれば進捗 economy.zero を記録する。 */
    public void checkZero(OfflinePlayer player) {
        AdvancementManager advancements = plugin.getAdvancementManager();
        if (advancements != null && player != null && getBalance(player) == 0) {
            advancements.event(player.getUniqueId(), "economy.zero");
        }
    }
```

`withdrawPlayer(OfflinePlayer, double)` の成功時の `return` の直前で `checkZero(player);` を呼ぶ。`transfer` の成功時（`return true` の直前）で `checkZero(from);` を呼ぶ。

- [ ] **Step 2: `/pay` のフック**

`PayCommand` で `economy.transfer(...)` の前に `double before = economy.getBalance(player);` を取り、成功後（`economy.recordEarning(...)` の直後）に追加する。

```java
        AdvancementManager advancements = plugin.getAdvancementManager();
        advancements.increment(player, "pay.sent_count", 1);
        advancements.increment(player, "pay.sent_total", amount);
        if (amount >= 100_000) advancements.increment(player, "pay.big", 1);
        if (amount == 1) {
            advancements.increment(player, "pay.one_yen", 1);
            if (before >= 1_000_000) advancements.increment(player, "pay.rich_one_yen", 1);
        }
        advancements.event(target.getUniqueId(), "pay.received_count");
        advancements.addDistinct(player, "pay.partners", target.getUniqueId().toString());
        advancements.addDistinct(target, "pay.partners", player.getUniqueId().toString());
```

- [ ] **Step 3: ショップのフック**

`ShopManager#create` の成功時（`return createDisplays(shop);` の直前）:

```java
        AdvancementManager advancements = plugin.getAdvancementManager();
        advancements.event(owner.getUniqueId(), "shop.created");
        if (owner.getUniqueId().equals(plugin.getLandManager().ownerOf(block.getLocation()))) {
            advancements.event(owner.getUniqueId(), "land.shop_in_land");
        }
```

購入（プレイヤーがショップから買う分岐）の `notifyOwner(...)` の直前:

```java
            recordPurchase(player, old, tradeQuantity, total);
```

売却（プレイヤーが買取ショップに売る分岐）の `notifyOwner(...)` の直前:

```java
        if (!old.owner().equals(player.getUniqueId())) {
            plugin.getAdvancementManager().increment(player, "shop.sold_to_shop", 1);
        }
```

補助メソッド:

```java
    /** 購入の進捗。自分のショップでの購入はオーナー側・他人向けの進捗に数えない。 */
    private void recordPurchase(Player buyer, Shop shop, int quantity, long total) {
        AdvancementManager advancements = plugin.getAdvancementManager();
        UUID owner = shop.owner();
        if (quantity == 1) {
            advancements.increment(buyer, "shop.bought_one", 1);
        }
        plugin.getEconomyManager().checkZero(buyer);
        if (owner.equals(buyer.getUniqueId())) {
            return;
        }
        advancements.increment(buyer, "shop.bought_other", 1);
        advancements.addDistinct(buyer, "shop.bought_shops", String.valueOf(shop.id()));
        advancements.event(owner, "shop.sold_count");
        advancements.addToCounter(owner, "shop.sold_items", quantity);
        advancements.addDistinct(owner, "shop.customers", buyer.getUniqueId().toString());
        if (advancements.addDaily(owner, "shop.sales", total) >= 100_000) {
            advancements.event(owner, "shop.daily100k");
        }
        if (shop.stock() - quantity == 0) {
            advancements.event(owner, "shop.sold_out");
        }
    }
```

- [ ] **Step 4: ビルド** — `./gradlew build` / BUILD SUCCESSFUL
- [ ] **Step 5: コミット** — `feat(advancements): 送金とショップの進捗を記録する`

---

### Task 6: チャット・メンション・個人メッセージ・TPA・土地への立ち入り

**Files:** `ChatListener.java`, `MentionService.java`, `MessageCommand.java`, `TpaCore.java`, `LandAreaStatusListener.java`

- [ ] **Step 1: チャット** — `ChatListener#onChat` のミュート判定の後（`String rawPlainMessage = ...` の直前）に `plugin.getAdvancementManager().onChat(sender);` を追加する。
- [ ] **Step 2: メンション** — `MentionService#highlightInternal` で、`@all` 以外で見つかった相手を `Set<Player> direct` に集め、ループの後で次を追加する。

```java
        if (sender != null) {
            for (Player target : direct) {
                if (!target.equals(sender)) {
                    plugin.getAdvancementManager().addDistinct(sender.getUniqueId(), "mention.targets", target.getUniqueId().toString());
                }
            }
        }
```

- [ ] **Step 3: 個人メッセージ** — `MessageCommand#trySend` の戻り値を `boolean`（ミュートで止めたら false、送ったら true）にし、`handleMsg` で true なら `addDistinct(sender, "msg.targets", target UUID)`、`handleReply` で true なら同じく `msg.targets` と `increment(sender, "msg.reply", 1)` を記録する。
- [ ] **Step 4: TPA** — `tpa` と `tphere` の分岐で `scheduleRequestExpiry(...)` を呼んだ直後に `plugin.getAdvancementManager().increment((Player) sender, "tpa.sent", 1);`。`tpaccept` と `tphaccept` の分岐で `scheduleTeleport(...)` を呼ぶ直前に `plugin.getAdvancementManager().increment((Player) sender, "tpa.accepted", 1);`。
- [ ] **Step 5: 土地への立ち入り** — `LandAreaStatusListener#updateDisplay` で、表示が変わる（前回と `AreaSignature` が違う）ときに `owner != null && !owner.equals(player.getUniqueId())` なら `plugin.getAdvancementManager().increment(player, "land.visited_other", 1);`。
- [ ] **Step 6: ビルドとコミット** — `feat(advancements): チャット・メッセージ・TPA・土地訪問の進捗を記録する`

---

### Task 7: 土地・チェスト保護

**Files:** `LandManager.java`, `ContainerLockManager.java`

- [ ] **Step 1: エリアの広さ** — `LandManager` に追加する。

```java
    /** そのエリア（つながった土地）のチャンク数。 */
    private int areaChunkCount(String areaId) {
        int count = 0;
        for (Claim claim : claimsByChunk.values()) {
            if (claim.areaId().equals(areaId)) {
                count++;
            }
        }
        return count;
    }
```

- [ ] **Step 2: 保護・解除・メンバー追加** — `claim` の `return new ClaimOutcome(ClaimResult.SUCCESS, ...)` の直前に:

```java
        AdvancementManager advancements = plugin.getAdvancementManager();
        advancements.increment(player, "land.claimed", 1);
        int size = areaChunkCount(resolution.areaId());
        if (size >= 10) advancements.increment(player, "land.area10", 1);
        if (size >= 25) advancements.increment(player, "land.area25", 1);
```

`unclaim` の `return new UnclaimOutcome(ActionResult.SUCCESS, ...)` の直前に、`!adminOverride` のときだけ `increment(player, "land.unclaimed", 1)`。`trust` で `inserted > 0` のときに `increment(owner, "land.trusted_other", 1)` と `event(target, "land.became_member")`。

- [ ] **Step 3: チェスト保護** — `ContainerLockManager#create` の最後の `return CreateResult.SUCCESS;`（新規作成）の直前に `plugin.getAdvancementManager().increment(owner, "lock.created", 1);`。`unlock` の成功時に、ロックのオーナーへ `event(<オーナーの UUID>, "lock.removed")`（`ContainerLock` のオーナーのアクセサ名を確認して使う）。
- [ ] **Step 4: ビルドとコミット** — `feat(advancements): 土地とチェスト保護の進捗を記録する`

---

### Task 8: 高速鉄道

**Files:** `RailSession.java`, `RailManager.java`, `AdvancementManager.java`

- [ ] **Step 1: `RailSession` に乗車記録を追加する**

```java
    private String departureStationName;
    private long traveledBlocks;

    public String departureStationName() { return departureStationName; }
    public void setDepartureStationName(String name) { if (departureStationName == null) departureStationName = name; }
    public long traveledBlocks() { return traveledBlocks; }
```

`rememberBlock(x, y, z)` で、前回の座標があれば `traveledBlocks += Math.abs(x - lastBlockX) + Math.abs(z - lastBlockZ);` を加算してから座標を更新する。

- [ ] **Step 2: 出発駅** — `markNearbyStationsAsNotified` で、発車時にトロッコの近くにある駅を `session.setDepartureStationName(station.name())` で記録する（最初に見つかった 1 駅）。
- [ ] **Step 3: 到着時の記録** — `endSession` の `if (reason == EndReason.ARRIVED && stationName != null)` のブロックで、乗っている各プレイヤーについて次を呼ぶ。

```java
                    RailLineManager.RailLine line = lineManager.findLineForStation(stationName);
                    plugin.getAdvancementManager().onRailArrival(player, session.departureStationName(), stationName, line, session.traveledBlocks());
```

`AdvancementManager` に追加する:

```java
    public void onRailArrival(Player player, String departure, String arrival, RailLineManager.RailLine line, long blocks) {
        increment(player, "rail.rides", 1);
        addDistinct(player, "rail.stations", arrival.toLowerCase(java.util.Locale.ROOT));
        if (line != null) {
            addDistinct(player, "rail.lines", line.name().toLowerCase(java.util.Locale.ROOT));
            if (RailRideRecord.isFullLine(line.stationNamesInOrder(), line.oneWay(), departure, arrival)) {
                increment(player, "rail.full_line", 1);
            }
        }
        increment(player, "rail.distance", blocks);
        if (blocks >= 5_000) increment(player, "rail.ride5k", 1);
        if (blocks >= 20_000) increment(player, "rail.ride20k", 1);
    }
```

- [ ] **Step 4: ビルドとコミット** — `feat(advancements): 高速鉄道の乗車を記録する`

---

### Task 9: 木こり・一括採掘・Home・Warp

**Files:** `KikoriManager.java`, `MineManager.java`, `HomeManager.java`, `HomeCommand.java`, `WarpManager.java`, `WarpCommand.java`

- [ ] **Step 1: 木こり** — `fellCompleteHandler.onFellComplete(...)` を呼んでいる 2 か所の直前で、次の補助を呼ぶ。

```java
    private void recordFelled(Player player, Material logType) {
        AdvancementManager advancements = plugin.getAdvancementManager();
        advancements.increment(player, "kikori.trees", 1);
        String type = logType.name().replace("_WOOD", "_LOG");
        if (type.endsWith("_LOG")) {
            advancements.addDistinct(player, "kikori.log_types", type);
        }
    }
```

（起点だけの伐採の分岐は `player.getScheduler().run(...)` の中で、`onFellComplete` の直前に呼ぶ。）

- [ ] **Step 2: 一括採掘** — `MineManager` の破壊ループの後（`finally` の後）に:

```java
        int broken = breakQueue.size() + 1;
        plugin.getAdvancementManager().increment(player, "mine.blocks", broken);
        if (broken >= 32) plugin.getAdvancementManager().increment(player, "mine.big", 1);
```

- [ ] **Step 3: Home** — `HomeManager#set` の最後で、結果が `SUCCESS` のとき:

```java
            AdvancementManager advancements = plugin.getAdvancementManager();
            advancements.increment(player, "home.set", 1);
            int homes = count(owner);
            if (homes >= 3) advancements.increment(player, "home.count3", 1);
            if (homes >= max) advancements.increment(player, "home.max", 1);
```

`HomeCommand#teleportToHome` の `TELEPORTED` の分岐で `increment(player, "home.teleports", 1)`。

- [ ] **Step 4: Warp** — `WarpManager` に `ownerOf(String name)`（`SELECT owner_uuid FROM warps WHERE name = ?`、無ければ null）を追加する。`WarpManager#set` の最後で、結果が `SUCCESS` のとき `warp.created`、`count(owner) >= 3` で `warp.count3`、`plugin.getLandManager().ownerOf(location)` が自分なら `land.warp_in_land`。`WarpCommand#teleportToWarp` の `TELEPORTED` の分岐で:

```java
            AdvancementManager advancements = plugin.getAdvancementManager();
            String key = name.toLowerCase(java.util.Locale.ROOT);
            advancements.addDistinct(player, "warp.visited", key);
            UUID owner = plugin.getWarpManager().ownerOf(name);
            if (owner != null && !owner.equals(player.getUniqueId())) {
                advancements.addDistinct(player, "warp.visited_other", key);
                advancements.addDistinct(player, "warp.visited_owners", owner.toString());
                advancements.event(owner, "warp.my_used");
            }
```

- [ ] **Step 5: ビルドとコミット** — `feat(advancements): 木こり・一括採掘・Home・Warp の進捗を記録する`

---

### Task 10: 投票・HeadShop・コマンドめぐり

**Files:** `TimeVoteCommand.java`, `WeatherVoteCommand.java`, `VoteListener.java`, `HeadshopGui.java`, `HeadshopPlayerHeadsGui.java`, `AdvancementManager.java`

- [ ] **Step 1: 時間投票・天気投票** — 開始（`activeVotes.put(...)` の直後）で `vote.started`, `vote.time_started`（天気は `vote.weather_started`）, `vote.participations`。賛成（`voteAccepts++` の直後）で `vote.yes` と `vote.participations`。反対で `vote.no` と `vote.participations`。終了時、可決なら `event(session.voteStartPlayer.getUniqueId(), "vote.passed")`、否決なら `vote.rejected`。
- [ ] **Step 2: 投票サイト** — `VoteListener` の入金成功後に `plugin.getAdvancementManager().event(offlinePlayer.getUniqueId(), "vote.server");`（非同期でも受け口がメインへ移す）。
- [ ] **Step 3: HeadShop** — `HeadshopGui#purchase` の成功時（購入メッセージの直前）:

```java
        AdvancementManager advancements = plugin.getAdvancementManager();
        advancements.increment(player, "headshop.bought", 1);
        advancements.addDistinct(player, "headshop.heads", head.texture());
        advancements.addDistinct(player, "headshop.rotation_days", LoginDays.today().toString());
        advancements.increment(player, "headshop.spent", price);
```

`HeadshopPlayerHeadsGui` の購入成功時: `headshop.bought`、`headshop.heads` に `"player:" + recentPlayer.uuid()`、`headshop.player_head`、`headshop.spent`。

- [ ] **Step 4: コマンドめぐり** — `AdvancementManager` に追加する（既に `Listener` として登録済み）。

```java
    private static final Set<String> TOUR_COMMANDS = Set.of("home", "warp", "tpa", "shop", "land");

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
        label = label.substring(label.indexOf(':') + 1);
        org.bukkit.command.PluginCommand command = plugin.getServer().getPluginCommand(label);
        if (command != null && command.getPlugin() == plugin && TOUR_COMMANDS.contains(command.getName())) {
            addDistinct(event.getPlayer(), "tour.commands", command.getName());
        }
    }
```

- [ ] **Step 5: ビルドとコミット** — `feat(advancements): 投票・HeadShop・コマンドめぐりの進捗を記録する`

---

### Task 11: ドキュメントと実機確認

- [ ] **Step 1: `CLAUDE.md`** — 独自進捗の段落に、カウンターのキーの一覧は設計書（`docs/superpowers/specs/2026-09-26-advancements-content-design.md` 3 章）にあること、`player_login_days` テーブル、UUID 版の受け口と非同期からの呼び出しが安全であることを追記する。テーブル一覧に `player_login_days`（`uuid`+`day`）を追加する。
- [ ] **Step 2: 設計書** — 状態を「実装済み」にし、木こりのキーを `kikori.trees` に更新する。
- [ ] **Step 3: 全体ビルド** — `./gradlew build` / 全テスト PASS
- [ ] **Step 4: 実機確認（ユーザー）** — タブごとに数個（`/pay 1`、ショップで購入、`/home` ×5 種のコマンド、木を 1 本伐採、高速鉄道で到着、時間投票）で達成できること。
- [ ] **Step 5: コミット** — `docs: 独自進捗の中身を CLAUDE.md に追記する`
