# ワールド自動リセット機能 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 指定ワールドを一定周期（毎週/隔週/毎月）で完全削除→ランダムシード再生成し、事前告知・強制退避・再入場ロックアウト・home/warp後始末・管理者用即時リセットまで一貫して扱う機能を実装する。

**Architecture:** `AutoBroadcastManager`と同じ`Bukkit.getGlobalRegionScheduler().runAtFixedRate`パターンで1秒毎にスケジュールをチェックする`WorldResetManager`を中心に、`PlayerTeleportEvent`/`PlayerCommandPreprocessEvent`をフックする独立リスナー`WorldResetListener`、`TeleportSafetyUtil`と同型の確認フローを持つ`WorldResetSafetyUtil`、管理者用の即時実行コマンド`WorldResetCommand`を新設する。DB操作（homes/warpsの一括削除）は既存の慣習通り`DatabaseManager`の静的メソッドを直接呼ぶ。

**Tech Stack:** Java 21 / PaperMC 1.21 API / Gradle (Kotlin DSL) / SQLite（`sqlite-jdbc`経由、`DatabaseManager`ラッパー）

**Spec:** `docs/superpowers/specs/2026-09-16-world-auto-reset-design.md`

## Global Constraints

- このリポジトリに自動テスト基盤は無い（`src/test`無し、テストタスク未設定 — `CLAUDE.md`参照）。各タスクの検証は `./gradlew build` によるコンパイル確認と、最終タスクでの `./gradlew runServer` を使った手動E2E確認で行う。ユニットテストを新設するのは本タスクのスコープ外。
- `config.yml`/`messages.yml`の値は `&%<char>` のカスタムパレットで書く（生の`&a`スタイルや`§`は使わない）。太字/リセット等の書式コード（`&l`/`&r`）はプレーンな legacy コードのまま。
- DBを触る処理は `DatabaseManager` の static メソッド（`query`/`queryOne`/`insert`/`update`/`execute`/`exists`）を生のテーブル名・カラム名で直接呼ぶ。リポジトリ層は作らない。
- ユーザー向け文字列は必ず `ConfigManager.getMessage`/`getUsageMessage` 経由。`plugin.getConfig()` を直接呼ばない。
- 設定を再読み込み可能にする新規マネージャーは `StellariaCore#reloadFeatureManagers()` に登録する。
- `plugin.yml`の`version`は`processResources`でGradleの`version`からテンプレートされる。手編集しない（今回のタスクでは触らない）。

---

## Task 1: config.yml / messages.yml / plugin.yml スキーマ追加

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/plugin.yml`

**Interfaces:**
- Produces: `world-reset.*` config キー群、`world-reset.*` message キー群、`/worldreset` コマンドと `stellaria.worldreset` 権限（後続タスクが読み書きする）。

- [ ] **Step 1: config.yml の末尾に `world-reset` セクションを追加する**

`src/main/resources/config.yml` の末尾（`sudo:` セクションの後）に追記:

```yaml

world-reset:
  enabled: false
  worlds: []
  # 退避先ワールド名。空文字ならBukkit上の最初のワールド（デフォルトのメインワールド）のスポーンへ退避する
  evacuate-to-world: ""
  schedule:
    type: "weekly" # "weekly" または "monthly"
    time: "12:00"            # 24時間表記、両モード共通
    # type: weekly のとき使用
    day-of-week: "FRIDAY"    # java.time.DayOfWeek の名前（大文字）
    interval-weeks: 1        # 1=毎週、2=隔週
    # type: monthly のとき使用
    day-of-month: 1          # 1〜31。その月に存在しない日は月末日に丸める
```

- [ ] **Step 2: messages.yml の末尾に `world-reset` セクションを追加する**

`src/main/resources/messages.yml` の末尾（`vanish:` セクションの後）に追記:

```yaml

world-reset:
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  usage_now: "&%c使用方法: /worldreset now <ワールド名>"
  not_a_target: "&%c&%f%world% &%cはリセット対象ワールドに設定されていません。"
  reset_now_started: "&%aワールド &%f%world% &%aを即時リセットします。プレイヤーを退避させました。"
  announce: "&%e&l[ワールドリセット] &%f%worlds% &%eは%minutes%分後に自動リセットされます。"
  evacuated: "&%eワールドリセットのため、安全な場所へ避難させました。"
  lockout_warning: "&%cこのワールドはまもなくリセットされるため、現在は立ち入りできません。もう一度実行すると10秒以内なら強制的に入場します。"
  next_reset_notice: "&%7このワールドは自動リセット対象です。次回リセット予定: &%f%next_reset%"
  sethome_warning: "&%cこのワールドは自動リセット対象のため、リセット時にこのhome/warpは削除されます。"
```

- [ ] **Step 3: plugin.yml にコマンドと権限を追加する**

`src/main/resources/plugin.yml` の `commands:` ブロックに追記（`unlock:` の後）:

```yaml
  worldreset:
    permission: stellaria.worldreset
```

`permissions:` ブロックに追記（`stellaria.vanish:` の後）:

```yaml
  stellaria.worldreset:
    default: op
```

- [ ] **Step 4: コンパイル確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`（新規Javaクラスはまだ無いので、リソース変更のみでビルドが通ることを確認する）

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/config.yml src/main/resources/messages.yml src/main/resources/plugin.yml
git commit -m "feat: add world-reset config/messages/plugin.yml schema"
```

---

## Task 2: `managers/WorldResetManager` の実装

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/WorldResetManager.java`

**Interfaces:**
- Consumes: `plugin.getConfigManager()`（`getBoolean`/`getString`/`getInt`/`getStringList`）、`DatabaseManager.execute(String, Object...)`、`plugin.getLogger()`
- Produces:
  - `WorldResetManager(StellariaCore plugin)`
  - `void start()` — 既存タスクを止めてから、`world-reset.enabled`ならスケジュール監視タスクを登録する
  - `void restart()` — アナウンス済み状態をリセットしてから`start()`する（`/stellariareload`用）
  - `boolean isResetTarget(String worldName)` — `world-reset.worlds`に含まれるか
  - `boolean isLockedOut(String worldName)` — 現在ロックアウト窓中か
  - `String formattedNextResetTime()` — 次回リセット予定時刻の表示用文字列（`yyyy/MM/dd HH:mm`）
  - `boolean resetNow(String worldName)` — 管理者コマンド用の即時リセット（対象外ならfalseを返す）

- [ ] **Step 1: クラス全体を実装する**

```java
package org.craftcore.stellaria.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;

import java.io.File;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * config.yml の world-reset.* に設定されたワールドを、一定周期（毎週/隔週/毎月）で
 * 完全削除→ランダムシード再生成する。AutoBroadcastManagerと同じ
 * Bukkit.getGlobalRegionScheduler().runAtFixedRate パターンで1秒毎にスケジュールをチェックし、
 * 60/30/10/5分前の告知、30分前の強制退避、リセット実行までを一本のtickで進行させる。
 *
 * 次回リセット時刻は状態を永続化せず、現在時刻とconfigのスケジュール設定から都度計算する
 * （HeadshopManager#shopDate() と同じ「基準時刻を跨ぐ日付」の考え方）。
 */
public class WorldResetManager {

    private static final long TICK_INTERVAL_TICKS = 20L; // 1秒毎
    private static final int[] ANNOUNCE_MINUTES = {60, 30, 10, 5};
    private static final int EVACUATION_MINUTES = 30;
    // 2024-01-01は月曜日。interval-weeksが2以上の隔週判定を、状態を持たずに固定周期で
    // 行うための基準日（この日からの経過週数がinterval-weeksの倍数になる週だけを対象にする）。
    private static final LocalDate WEEK_ANCHOR_MONDAY = LocalDate.of(2024, 1, 1);
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private final StellariaCore plugin;
    private ScheduledTask task;
    private final Set<Integer> announcedMinutes = new HashSet<>();
    private Instant currentCycleResetAt;
    private final Set<String> lockoutWorlds = new HashSet<>();

    public WorldResetManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 既存タスクがあれば止めてから、config.yml の設定に従って（有効なら）再登録する。 */
    public void start() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
            task = null;
        }
        if (!plugin.getConfigManager().getBoolean("world-reset.enabled", false)) {
            return;
        }
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, scheduled -> tick(), TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);
    }

    /**
     * /stellariareload から呼ばれる想定。告知済み状態をリセットしてからstart()する。
     * ロックアウト中の状態（lockoutWorlds）はリロードで消さない
     * ——設定再読み込みが安全対策そのものを解除してしまわないようにするため。
     */
    public void restart() {
        announcedMinutes.clear();
        currentCycleResetAt = null;
        start();
    }

    public boolean isResetTarget(String worldName) {
        return plugin.getConfigManager().getStringList("world-reset.worlds").contains(worldName);
    }

    public boolean isLockedOut(String worldName) {
        return lockoutWorlds.contains(worldName);
    }

    public String formattedNextResetTime() {
        return DISPLAY_FORMAT.withZone(ZoneId.systemDefault()).format(nextResetInstant());
    }

    /**
     * 管理者用の即時リセット。対象ワールドでなければfalseを返して何もしない。
     * 通常スケジュールの4段階告知は行わず、即座に退避させてからリセットする（短縮版）。
     */
    public boolean resetNow(String worldName) {
        if (!isResetTarget(worldName)) {
            return false;
        }
        lockoutWorlds.add(worldName);
        evacuate(List.of(worldName));
        performReset(List.of(worldName));
        return true;
    }

    private void tick() {
        if (!plugin.getConfigManager().getBoolean("world-reset.enabled", false)) {
            return;
        }
        List<String> worlds = plugin.getConfigManager().getStringList("world-reset.worlds");
        if (worlds.isEmpty()) {
            return;
        }

        Instant resetAt = nextResetInstant();
        if (currentCycleResetAt == null || !currentCycleResetAt.equals(resetAt)) {
            // 新しいリセットサイクルに入った（前回分が完了した、または初回tick）ので告知状態を仕切り直す
            currentCycleResetAt = resetAt;
            announcedMinutes.clear();
        }

        long secondsUntilReset = Duration.between(Instant.now(), resetAt).getSeconds();

        // 起動直後や設定変更直後で残り時間が既に閾値を下回っている場合、経過済みの閾値も
        // このtickでまとめて発火する（例: 残り20分でプラグインが起動したら60分・30分告知＋
        // 強制退避を即座に行う）。取りこぼして退避を飛ばすより安全側に倒す。
        for (int minutes : ANNOUNCE_MINUTES) {
            if (secondsUntilReset <= minutes * 60L && announcedMinutes.add(minutes)) {
                announce(worlds, minutes);
                if (minutes == EVACUATION_MINUTES) {
                    evacuate(worlds);
                    lockoutWorlds.addAll(worlds);
                }
            }
        }

        if (secondsUntilReset <= 0) {
            performReset(worlds);
            currentCycleResetAt = null;
            announcedMinutes.clear();
        }
    }

    private void announce(List<String> worldNames, int minutes) {
        String message = plugin.getConfigManager().getMessage("world-reset.announce", null);
        message = FormatUtil.replace(message, "%worlds%", String.join(", ", worldNames));
        message = FormatUtil.replace(message, "%minutes%", String.valueOf(minutes));
        Bukkit.broadcast(ColorUtil.component(message));
    }

    private void evacuate(List<String> worldNames) {
        Location destination = evacuationDestination();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (worldNames.contains(player.getWorld().getName())) {
                player.teleportAsync(destination);
                player.sendMessage(plugin.getConfigManager().getMessage("world-reset.evacuated", player));
            }
        }
    }

    private Location evacuationDestination() {
        String worldName = plugin.getConfigManager().getString("world-reset.evacuate-to-world", "");
        World world = worldName.isEmpty() ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            world = Bukkit.getWorlds().get(0);
        }
        return world.getSpawnLocation();
    }

    /**
     * 対象ワールドのhomes/warpsをDBから削除し、ワールドフォルダを削除してランダムシードで
     * 再生成する。フォルダ削除・ワールド生成はブロッキングI/Oだが、リセット自体が低頻度の
     * 意図的操作なのでシンプルさを優先し、GlobalRegionScheduler上でそのまま実行する。
     */
    private void performReset(List<String> worldNames) {
        for (String worldName : worldNames) {
            DatabaseManager.execute("DELETE FROM homes WHERE world = ?", worldName);
            DatabaseManager.execute("DELETE FROM warps WHERE world = ?", worldName);

            World world = Bukkit.getWorld(worldName);
            World.Environment environment = world != null ? world.getEnvironment() : World.Environment.NORMAL;
            WorldType worldType = world != null ? world.getWorldType() : WorldType.NORMAL;
            File worldFolder = world != null ? world.getWorldFolder() : new File(Bukkit.getWorldContainer(), worldName);

            if (world != null) {
                Bukkit.unloadWorld(world, false);
            }
            deleteWorldFolder(worldFolder);

            new WorldCreator(worldName)
                    .environment(environment)
                    .type(worldType)
                    .seed(ThreadLocalRandom.current().nextLong())
                    .createWorld();

            plugin.getLogger().info("ワールド '" + worldName + "' を自動リセットしました。");
        }
        lockoutWorlds.removeAll(worldNames);
    }

    private void deleteWorldFolder(File folder) {
        File[] children = folder.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteWorldFolder(child);
                } else {
                    child.delete();
                }
            }
        }
        folder.delete();
    }

    private Instant nextResetInstant() {
        LocalTime time = parseScheduleTime();
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        String type = plugin.getConfigManager().getString("world-reset.schedule.type", "weekly");

        LocalDateTime candidate = "monthly".equalsIgnoreCase(type)
                ? nextMonthlyCandidate(now, time)
                : nextWeeklyCandidate(now, time);

        return candidate.atZone(zone).toInstant();
    }

    private LocalTime parseScheduleTime() {
        String raw = plugin.getConfigManager().getString("world-reset.schedule.time", "12:00");
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("world-reset.schedule.time の形式が不正です(HH:mm形式で指定してください): " + raw);
            return LocalTime.NOON;
        }
    }

    private LocalDateTime nextMonthlyCandidate(LocalDateTime now, LocalTime time) {
        int dayOfMonth = plugin.getConfigManager().getInt("world-reset.schedule.day-of-month", 1);
        LocalDateTime candidate = clampedMonthlyDateTime(now.getYear(), now.getMonthValue(), dayOfMonth, time);
        if (!candidate.isAfter(now)) {
            YearMonth nextMonth = YearMonth.from(now).plusMonths(1);
            candidate = clampedMonthlyDateTime(nextMonth.getYear(), nextMonth.getMonthValue(), dayOfMonth, time);
        }
        return candidate;
    }

    /** dayOfMonthがその月に存在しない日（例: 31日指定で2月）の場合、その月の末日に丸める。 */
    private LocalDateTime clampedMonthlyDateTime(int year, int month, int dayOfMonth, LocalTime time) {
        int clampedDay = Math.min(dayOfMonth, YearMonth.of(year, month).lengthOfMonth());
        return LocalDateTime.of(year, month, clampedDay, time.getHour(), time.getMinute());
    }

    private LocalDateTime nextWeeklyCandidate(LocalDateTime now, LocalTime time) {
        DayOfWeek dayOfWeek = parseDayOfWeek();
        int intervalWeeks = Math.max(1, plugin.getConfigManager().getInt("world-reset.schedule.interval-weeks", 1));

        LocalDateTime candidate = now.with(TemporalAdjusters.nextOrSame(dayOfWeek)).with(time);
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusWeeks(1);
        }
        while (weeksSinceAnchor(candidate.toLocalDate()) % intervalWeeks != 0) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }

    private DayOfWeek parseDayOfWeek() {
        String raw = plugin.getConfigManager().getString("world-reset.schedule.day-of-week", "FRIDAY");
        try {
            return DayOfWeek.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("world-reset.schedule.day-of-week の形式が不正です: " + raw);
            return DayOfWeek.FRIDAY;
        }
    }

    private long weeksSinceAnchor(LocalDate date) {
        LocalDate mondayOfWeek = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return ChronoUnit.WEEKS.between(WEEK_ANCHOR_MONDAY, mondayOfWeek);
    }
}
```

- [ ] **Step 2: コンパイル確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`（このクラスはまだどこからも呼ばれないが、単体でコンパイルが通ることを確認する）

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/managers/WorldResetManager.java
git commit -m "feat: add WorldResetManager (schedule calc, announce/evacuate/reset execution)"
```

---

## Task 3: `utils/WorldResetSafetyUtil` の実装

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/WorldResetSafetyUtil.java`

**Interfaces:**
- Produces:
  - `record WorldResetSafetyUtil.PendingConfirm(String worldName, long expiresAtMillis)`
  - `enum WorldResetSafetyUtil.Result { ALLOWED, WARNED }`
  - `static Result attempt(Player player, String targetWorldName, Map<UUID, PendingConfirm> pending)`

- [ ] **Step 1: クラスを実装する**

```java
package org.craftcore.stellaria.utils;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * ロックアウト窓中の対象ワールドへの再入場を「警告→10秒以内の再実行で強制許可」で確認する
 * フロー。TeleportSafetyUtilと同じ確認パターンだが、判定対象が「不安全な着地点」ではなく
 * 「リセット待ちワールドへの入場」であるため別クラスに切り出している。
 *
 * 保留中の確認状態はこのクラスでは保持しない。呼び出し側（WorldResetListener）が
 * static Mapを引数で渡す（TeleportSafetyUtilと同じ設計）。
 */
public final class WorldResetSafetyUtil {

    private WorldResetSafetyUtil() {
    }

    private static final long CONFIRM_WINDOW_MILLIS = 10_000L;

    public record PendingConfirm(String worldName, long expiresAtMillis) {
    }

    public enum Result {
        /** 確認済みで通過を許可した */
        ALLOWED,
        /** ロックアウト対象のため警告を出し、確認待ちにした（まだ許可していない） */
        WARNED
    }

    /**
     * targetWorldNameへの入場を試みる。直前10秒以内に同じワールドへの警告が残っていれば
     * ALLOWED（強制許可）、無ければ新しく警告状態を積んでWARNEDを返す。
     * ロックアウト対象かどうかの判定は呼び出し側（WorldResetListener）が先に行う。
     */
    public static Result attempt(Player player, String targetWorldName, Map<UUID, PendingConfirm> pending) {
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        PendingConfirm existing = pending.get(playerId);
        if (existing != null && existing.expiresAtMillis() >= now && existing.worldName().equals(targetWorldName)) {
            pending.remove(playerId);
            return Result.ALLOWED;
        }

        pending.put(playerId, new PendingConfirm(targetWorldName, now + CONFIRM_WINDOW_MILLIS));
        return Result.WARNED;
    }
}
```

- [ ] **Step 2: コンパイル確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/utils/WorldResetSafetyUtil.java
git commit -m "feat: add WorldResetSafetyUtil (lockout reentry confirm flow)"
```

---

## Task 4: `listeners/WorldResetListener` の実装

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/listeners/WorldResetListener.java`

**Interfaces:**
- Consumes: `plugin.getWorldResetManager().isLockedOut(String)` / `.isResetTarget(String)` / `.formattedNextResetTime()`（Task 2で定義）、`WorldResetSafetyUtil.attempt(...)`（Task 3で定義）
- Produces: `WorldResetListener(StellariaCore plugin)` — `Listener`実装。`PlayerTeleportEvent`でロックアウト窓チェック＋通常時入場メッセージ、`PlayerCommandPreprocessEvent`で`/sethome`・`/setwarp`警告。

- [ ] **Step 1: クラスを実装する**

```java
package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.WorldResetSafetyUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ワールド自動リセットに関わるプレイヤー操作を扱う独立リスナー。
 * PlayerListener（join/quit・AFK・elevator）とは守備範囲が異なるため分離している。
 *
 * PlayerTeleportEventはコマンド・ネザーポータル・他プラグイン経由など原因を問わず発火するため、
 * これをフックすることで再入場ルートを個別に塞ぐ必要がない。ただし、ログイン時の
 * リスポーン地点配置（ワールドが再生成された後の初回配置等）はテレポートとして扱われず
 * このイベントの対象外になる点は既知の制約として残す。
 */
public class WorldResetListener implements Listener {

    // ロックアウト窓の再入場確認待ち状態（プレイヤー1人につき1件）
    private static final Map<UUID, WorldResetSafetyUtil.PendingConfirm> PENDING_CONFIRM = new HashMap<>();

    private final StellariaCore plugin;

    public WorldResetListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        String targetWorld = event.getTo().getWorld().getName();
        Player player = event.getPlayer();

        if (plugin.getWorldResetManager().isLockedOut(targetWorld)) {
            WorldResetSafetyUtil.Result result = WorldResetSafetyUtil.attempt(player, targetWorld, PENDING_CONFIRM);
            if (result == WorldResetSafetyUtil.Result.WARNED) {
                event.setCancelled(true);
                player.sendMessage(plugin.getConfigManager().getMessage("world-reset.lockout_warning", player));
            }
            return;
        }

        if (plugin.getWorldResetManager().isResetTarget(targetWorld)) {
            String message = plugin.getConfigManager().getMessage("world-reset.next_reset_notice", player);
            message = FormatUtil.replace(message, "%next_reset%", plugin.getWorldResetManager().formattedNextResetTime());
            player.sendMessage(message);
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String commandLabel = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
        if (!commandLabel.equals("sethome") && !commandLabel.equals("setwarp")) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.getWorldResetManager().isResetTarget(player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("world-reset.sethome_warning", player));
        }
    }
}
```

- [ ] **Step 2: コンパイル確認**

Run: `./gradlew build --no-daemon`
Expected: コンパイルエラーになる想定（`StellariaCore#getWorldResetManager()` がまだ無いため）。
`error: cannot find symbol ... getWorldResetManager` のようなメッセージが出ることを確認する（Task 6でこのgetterを追加するまで解消しない、想定通りの一時的な失敗）。

- [ ] **Step 3: Commit**

一時的にビルドが壊れる状態でのコミットになるため、コミットメッセージにその旨を明記する。

```bash
git add src/main/java/org/craftcore/stellaria/listeners/WorldResetListener.java
git commit -m "feat: add WorldResetListener (lockout teleport hook, sethome/setwarp warning)

StellariaCore#getWorldResetManager() は未追加のため、この時点ではビルドが通らない
（Task 6のStellariaCore配線で解消する想定）。"
```

---

## Task 5: `commands/WorldResetCommand` の実装

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/WorldResetCommand.java`

**Interfaces:**
- Consumes: `plugin.getWorldResetManager().resetNow(String)`（Task 2）、`TabCompleteUtil.filterStartsWith(List<String>, String)`（既存）
- Produces: `WorldResetCommand(StellariaCore plugin)` — `CommandExecutor`/`TabCompleter`実装、`/worldreset now <world>`を処理

- [ ] **Step 1: クラスを実装する**

```java
package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /worldreset now <world> — スケジュールを待たずに即座にワールドを退避・リセットする管理者コマンド。 */
public class WorldResetCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public WorldResetCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.worldreset")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("world-reset.no_permission", null));
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("now")) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("world-reset.usage_now", null));
            return true;
        }

        String worldName = args[1];
        boolean started = plugin.getWorldResetManager().resetNow(worldName);
        if (!started) {
            sender.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("world-reset.not_a_target", null), "%world%", worldName));
            return true;
        }
        sender.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("world-reset.reset_now_started", null), "%world%", worldName));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("now"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("now")) {
            return TabCompleteUtil.filterStartsWith(
                    plugin.getConfigManager().getStringList("world-reset.worlds"), args[1]);
        }
        return List.of();
    }
}
```

- [ ] **Step 2: コンパイル確認**

Run: `./gradlew build --no-daemon`
Expected: Task 4と同じ理由（`getWorldResetManager()`未定義）でコンパイルエラーのまま。エラーメッセージにこのファイルの箇所も追加されていることを確認する。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/commands/WorldResetCommand.java
git commit -m "feat: add WorldResetCommand (/worldreset now <world>)

StellariaCore#getWorldResetManager() は未追加のため、この時点ではビルドが通らない
（Task 6のStellariaCore配線で解消する想定）。"
```

---

## Task 6: `StellariaCore` への配線

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `WorldResetManager`（Task 2）、`WorldResetListener`（Task 4）、`WorldResetCommand`（Task 5）
- Produces: `StellariaCore#getWorldResetManager()`（他クラスから参照可能に）

- [ ] **Step 1: フィールド宣言を追加する**

`src/main/java/org/craftcore/stellaria/StellariaCore.java:81` (`private LandBorderParticleManager landBorderParticleManager;` の直後) に追加:

```java
    private WorldResetManager worldResetManager;
```

- [ ] **Step 2: マネージャーのインスタンス化を追加する**

`this.warpManager = new WarpManager(this);` の直後（`StellariaCore.java:197`付近）に追加:

```java
        this.worldResetManager = new WorldResetManager(this);
```

- [ ] **Step 3: リスナー登録を追加する**

`getServer().getPluginManager().registerEvents(new VanishListener(this), this);` の直後（`StellariaCore.java:277`付近、リスナー登録ブロックの末尾）に追加:

```java
        getServer().getPluginManager().registerEvents(new WorldResetListener(this), this);
```

- [ ] **Step 4: コマンド登録を追加する**

`getCommand("unlock").setExecutor(lockCommand);` の直後（`StellariaCore.java:494`付近）に追加:

```java
        WorldResetCommand worldResetCommand = new WorldResetCommand(this);
        getCommand("worldreset").setExecutor(worldResetCommand);
        getCommand("worldreset").setTabCompleter(worldResetCommand);
```

- [ ] **Step 5: スケジューラ開始を追加する**

`headshopManager.start();` の直後（`StellariaCore.java:503`付近、`ConsoleUtil.printLogo(...)`の直前）に追加:

```java
        worldResetManager.start();
```

- [ ] **Step 6: getterを追加する**

`public LandBorderParticleManager getLandBorderParticleManager() { ... }` の直後（`StellariaCore.java:618-620`付近）に追加:

```java

    public WorldResetManager getWorldResetManager() {
        return this.worldResetManager;
    }
```

- [ ] **Step 7: `reloadFeatureManagers()` に登録する**

`autoBroadcastManager.restart();` の直後（`reloadFeatureManagers()`メソッド内、`StellariaCore.java:646`付近）に追加:

```java
        worldResetManager.restart();
```

- [ ] **Step 8: import文を確認する**

`StellariaCore.java` は `import org.craftcore.stellaria.managers.*;` と `import org.craftcore.stellaria.commands.*;` のワイルドカードインポートを既に使っているため、`WorldResetManager`/`WorldResetCommand`の追加importは不要。`WorldResetListener`用に個別importを追加する（`import org.craftcore.stellaria.listeners.VanishListener;` の直後、`StellariaCore.java:40`付近）:

```java
import org.craftcore.stellaria.listeners.WorldResetListener;
```

- [ ] **Step 9: コンパイル確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`（Task 4・5で保留していたエラーがここで解消される）

- [ ] **Step 10: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: wire WorldResetManager/Listener/Command into StellariaCore"
```

---

## Task 7: 手動E2E検証（`runServer`）

自動テスト基盤が無いため、実サーバーを起動して一連の動作を手で確認する。

**Files:** なし（動作確認のみ、コード変更はTask 6までで完了している）

- [ ] **Step 1: ビルドしてローカルサーバーを起動する**

```bash
./gradlew build --no-daemon
./gradlew runServer
```

起動完了まで待つ（初回はワールド生成に時間がかかる）。

- [ ] **Step 2: テスト用にスケジュールを直近に設定する**

サーバーを一度Ctrl+Cで止め、`run/plugins/StellariaCore/config.yml` の `world-reset` セクションを編集する:

```yaml
world-reset:
  enabled: true
  worlds:
    - "world"
  evacuate-to-world: ""
  schedule:
    type: "weekly"
    time: "<現在時刻の6分後をHH:mmで指定>"
    day-of-week: "<今日の曜日を大文字で指定（例: WEDNESDAY）>"
    interval-weeks: 1
```

再度 `./gradlew runServer` で起動する。

- [ ] **Step 3: 告知・強制退避を確認する**

起動直後、コンソール/参加中プレイヤーに複数のアナウンス（60分・30分・10分前相当）がまとめて即座に表示されることを確認する（残り時間が6分しかないため、経過済みの閾値が起動直後に一括発火する — Task 2のtick()実装で想定している挙動）。続けて強制退避メッセージが表示され、プレイヤーが`evacuate-to-world`（未設定ならデフォルトワールド）のスポーンへ実際にテレポートされることを確認する。

- [ ] **Step 4: ロックアウトの再入場確認フローを確認する**

退避後、`/world world`（または対象ワールドへのワープ/ホーム）で再入場を試みる。1回目は`world-reset.lockout_warning`メッセージが出てテレポートがキャンセルされることを確認する。10秒以内に同じコマンドを再実行すると、今度は実際に入場できることを確認する。

- [ ] **Step 5: 通常時の入場メッセージを確認する**

ロックアウト対象でなくなった状態（`world-reset.enabled: false`に一時的に戻すか、`worlds`リストを一時的に空にする）で対象ワールドへ入場し、`world-reset.next_reset_notice`が次回リセット日時付きで毎回表示されることを確認したら、設定を元に戻す。

- [ ] **Step 6: リセット実行と後始末を確認する**

`world-reset.enabled: true`に戻して残り時間ゼロまで待ち、コンソールに`ワールド 'world' を自動リセットしました。`のログが出ること、ワールドが正常にリロードされ入場できることを確認する。事前に対象ワールドで`/sethome`していたホームが、リセット後は`/homes`から消えていることを確認する。

- [ ] **Step 7: `/sethome`・`/setwarp` の警告を確認する**

対象ワールド内で `/sethome テスト用` を実行し、`world-reset.sethome_warning`が表示されつつ、`/sethome`自体は成功して`/homes`に登録されることを確認する（`/setwarp`も同様に確認する）。

- [ ] **Step 8: `/worldreset now` の即時リセットを確認する**

`world-reset.worlds`に含まれる別ワールド（無ければ`worlds`に追記）に対して `/worldreset now <world>` を実行し、告知の4段階を経ずに即座に全プレイヤーが退避し、直後にリセットが実行されることを確認する。対象外のワールド名を指定した場合は`world-reset.not_a_target`が表示され、何も起きないことも確認する。

- [ ] **Step 9: テスト用の設定を戻す**

確認が終わったら `run/plugins/StellariaCore/config.yml` の `world-reset.enabled` を `false` に戻す（本番投入前提のスケジュール値は別途決める）。

このタスクはコードの変更を伴わないため、コミットは無し（Task 6までのコミットで実装は完了している）。
