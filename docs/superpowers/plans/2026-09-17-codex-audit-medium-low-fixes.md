# Codex監査 Medium/Low 修正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Medium 10件とLow/Improvement 5件を修正し、設定不備・退出・競合・外部プラグインとの共存で機能を壊さないようにする。

**Architecture:** 新規の常駐サービスは作らず、`ParticleUtil`、`DurationParser`、新設する`SchedulerIntervalUtil`に入力検証を集約する。既存のコマンド／マネージャーは、その結果と明確な状態遷移を利用する。`/stellariareload` は定期タスクを所有する `StellariaCore` が停止・再登録して設定を反映する。

**Tech Stack:** Java 21、Paper 1.21 API、Folia scheduler、SQLite、JUnit 5、Gradle Kotlin DSL。

**Spec:** `docs/superpowers/specs/2026-09-17-codex-audit-medium-low-fixes-design.md`

## Global Constraints

- ユーザー向けの文言は `messages.yml` に置き、`§` コードを直書きしない。
- 無効な有限 mute 期間を永久 mute として扱わない。永久は `perm` / `permanent` のみ。
- reload は Vault・Discord/JDA・イベントリスナーを再接続／再登録しない。
- デフォルト以外の個人スコアボードを Stellaria のものと見なさず、更新をスキップする。
- 各テストは対象の本番変更を1つ戻すと失敗する振る舞いを確認する。

---

### Task 1: 検証ユーティリティと設定公開の安全化

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/SchedulerIntervalUtil.java`
- Create: `src/test/java/org/craftcore/stellaria/utils/SchedulerIntervalUtilTest.java`
- Modify: `src/main/java/org/craftcore/stellaria/utils/DurationParser.java`
- Modify: `src/main/java/org/craftcore/stellaria/utils/ParticleUtil.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/ConfigFile.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/ConfigManager.java`
- Modify: `src/test/java/org/craftcore/stellaria/utils/ParticleUtilTest.java`
- Modify: `src/test/java/org/craftcore/stellaria/utils/DurationParserTest.java`

**Interfaces:**
- Produces: `SchedulerIntervalUtil.ticks(int configuredTicks): long` and `SchedulerIntervalUtil.minutesToTicks(int configuredMinutes): long`.
- Produces: `ParticleUtil.resolveParticle(String configuredName, Consumer<String> warn): Particle`.
- Produces: `DurationParser.expiresAtMillis(long nowMillis, long seconds): long`, which throws `IllegalArgumentException` when a finite duration cannot be represented.

- [ ] **Step 1: Write failing utility tests**

```java
@Test
void clampsNonPositiveTicksAndSaturatesMinuteConversion() {
    assertEquals(1L, SchedulerIntervalUtil.ticks(0));
    assertEquals(1L, SchedulerIntervalUtil.ticks(-5));
    assertEquals(2_576_980_376_400L, SchedulerIntervalUtil.minutesToTicks(Integer.MAX_VALUE));
}

@Test
void rejectsFiniteMuteDurationThatCannotFitEpochMillis() {
    assertThrows(IllegalArgumentException.class,
            () -> DurationParser.expiresAtMillis(Long.MAX_VALUE - 5, 1));
}

@Test
void resolvesInvalidParticleToDustAndWarns() {
    List<String> warnings = new ArrayList<>();
    assertEquals(Particle.DUST, ParticleUtil.resolveParticle("not_a_particle", warnings::add));
    assertEquals(1, warnings.size());
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.SchedulerIntervalUtilTest --tests org.craftcore.stellaria.utils.DurationParserTest --tests org.craftcore.stellaria.utils.ParticleUtilTest`

Expected: compilation failure because the new utility and methods do not exist.

- [ ] **Step 3: Implement the smallest reusable validators**

```java
public static long minutesToTicks(int minutes) {
    return Math.max(1L, (long) minutes * 60L * 20L);
}

public static long expiresAtMillis(long nowMillis, long seconds) {
    if (seconds < 0) return -1L;
    try {
        return Math.addExact(nowMillis, Math.multiplyExact(seconds, 1000L));
    } catch (ArithmeticException e) {
        throw new IllegalArgumentException("期間が長すぎます", e);
    }
}
```

Use `trim().toUpperCase(Locale.ROOT)` in `resolveParticle`; catch `IllegalArgumentException`, call `warn.accept(...)`, and return `Particle.DUST`. Declare `ConfigFile.configuration` as `volatile`; load into a local `YamlConfiguration loaded` and assign it only after loading completes. In the `ignoreWarn` overloads of `getMessage`, `getRawMessage`, and `getMessageList`, replace `warnIfMissing("config.yml", path)` with `warnIfMissing("messages.yml", path)`.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.SchedulerIntervalUtilTest --tests org.craftcore.stellaria.utils.DurationParserTest --tests org.craftcore.stellaria.utils.ParticleUtilTest`

Expected: all selected tests pass.

- [ ] **Step 5: Commit the utility changes**

```bash
git add src/main/java/org/craftcore/stellaria/utils/SchedulerIntervalUtil.java src/main/java/org/craftcore/stellaria/utils/DurationParser.java src/main/java/org/craftcore/stellaria/utils/ParticleUtil.java src/main/java/org/craftcore/stellaria/managers/ConfigFile.java src/main/java/org/craftcore/stellaria/managers/ConfigManager.java src/test/java/org/craftcore/stellaria/utils
git commit -m "fix: validate scheduled intervals and finite durations"
```

### Task 2: テレポート結果・パーティクル・退出時状態を正しく扱う

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/utils/TeleportSafetyUtil.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/HomeCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/WarpCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/tpa/TpaCore.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/ElevatorManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerJoinListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/PrivateMessageManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/utils/MenuItemUtil.java`

**Interfaces:**
- Produces: `TeleportSafetyUtil.Result` with `WARNED`, `TELEPORTED`, and `FAILED`.
- Produces: `HomeCommand.clearPendingConfirm(UUID)`, `WarpCommand.clearPendingConfirm(UUID)`, `PrivateMessageManager.removePlayer(UUID)`, and `MenuItemUtil.removePlayer(UUID)`.

- [ ] **Step 1: Write the failing transition tests**

Create a test-only `Player` dynamic proxy whose `teleport(Location)` returns `false`. Assert that `TeleportSafetyUtil.attempt` returns `FAILED` and leaves an existing forced-confirmation entry in the passed map. Add an equivalent proxy returning `true` and assert `TELEPORTED` plus map removal. Name the tests `returnsFailedAndPreservesConfirmationWhenTeleportIsRejected` and `returnsTeleportedAndClearsConfirmationWhenTeleportSucceeds`.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.TeleportSafetyUtilTest`

Expected: `FAILED` does not exist and the state-transition assertions cannot pass.

- [ ] **Step 3: Implement the state transitions and cleanup**

```java
if (isSafe(destination)) {
    return player.teleport(destination) ? Result.TELEPORTED : Result.FAILED;
}
if (confirmedSameDestination) {
    if (player.teleport(destination)) {
        pending.remove(playerId);
        return Result.TELEPORTED;
    }
    return Result.FAILED;
}
```

Only `Result.TELEPORTED` calls the home/warp visual effect. In `TpaCore#performTeleport`, return immediately unless `mover.teleport(...)` succeeds; in both elevator branches, send the success action-bar message and sound only if `player.teleport(location)` succeeds. Replace all four unsafe `Particle.valueOf(...)` calls with `ParticleUtil.resolveParticle(value, plugin.getLogger()::warning)`. In `PlayerListener#onPlayerLeave`, invoke the four new removal methods alongside the existing manager cleanup calls.

- [ ] **Step 4: Run the focused test and full unit suite**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.TeleportSafetyUtilTest`

Run: `./gradlew test`

Expected: the transition tests and all existing tests pass.

- [ ] **Step 5: Commit the teleport and cleanup changes**

```bash
git add src/main/java/org/craftcore/stellaria/utils/TeleportSafetyUtil.java src/main/java/org/craftcore/stellaria/commands/HomeCommand.java src/main/java/org/craftcore/stellaria/commands/WarpCommand.java src/main/java/org/craftcore/stellaria/commands/tpa/TpaCore.java src/main/java/org/craftcore/stellaria/managers/ElevatorManager.java src/main/java/org/craftcore/stellaria/listeners/PlayerJoinListener.java src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java src/main/java/org/craftcore/stellaria/managers/PrivateMessageManager.java src/main/java/org/craftcore/stellaria/utils/MenuItemUtil.java src/test/java/org/craftcore/stellaria/utils/TeleportSafetyUtilTest.java
git commit -m "fix: preserve teleport failure state and clear player caches"
```

### Task 3: 完全な設定リロードとメッセージ化

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/AutoBroadcastManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/ReloadCommand.java`
- Modify: `src/main/resources/messages.yml`

**Interfaces:**
- Produces: private `StellariaCore.restartConfigScheduledTasks()` that cancels stored `ScheduledTask` references before registering fresh tasks.
- Consumes: `SchedulerIntervalUtil.ticks` and `SchedulerIntervalUtil.minutesToTicks` from Task 1.

- [ ] **Step 1: Write the failing reload configuration test**

Extract the scheduler configuration calculation into package-visible methods in `StellariaCore`: `hudIntervals()` returning four normalized tick values and `actionBarInterval()` returning the normalized configured value. Add a test with 0, -1, and normal values asserting results `1L`, `1L`, and the literal positive configured interval. The test must fail before the extractor exists.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew test --tests org.craftcore.stellaria.StellariaCoreSchedulerConfigTest`

Expected: compilation failure for the absent interval accessors.

- [ ] **Step 3: Make all config-owned tasks restartable**

Keep one `ScheduledTask` field per configurable repeating task: ActionBar tick, persistent ActionBar, BossBar, Scoreboard, TabList, Belowname, Nametag, and land-border tick. Add one `cancelTask(ScheduledTask)` helper. Have `onEnable` call the same registration method used by `reloadFeatureManagers`; call `autoBroadcastManager.restart()`, `worldResetManager.restart()`, `headshopManager.start()`, and `rankManager.reload()` after the fresh task registration. Do not register listeners or start/stop Discord in this method.

Use `SchedulerIntervalUtil` for every settings-derived initial delay and period, including `AutoBroadcastManager#start`. Add `reload.no_permission` and `reload.success` under a `reload:` key in `messages.yml`, and replace both literal strings in `ReloadCommand` with `ConfigManager#getMessage`.

- [ ] **Step 4: Run focused and complete tests**

Run: `./gradlew test --tests org.craftcore.stellaria.StellariaCoreSchedulerConfigTest`

Run: `./gradlew test`

Expected: all tests pass; the task fields ensure a reload cannot accumulate duplicate periodic tasks.

- [ ] **Step 5: Commit the reload work**

```bash
git add src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/managers/AutoBroadcastManager.java src/main/java/org/craftcore/stellaria/commands/ReloadCommand.java src/main/resources/messages.yml src/test/java/org/craftcore/stellaria/StellariaCoreSchedulerConfigTest.java
git commit -m "fix: reload all config-owned scheduled features"
```

### Task 4: 原子的なアカウント確保と公開ランキングのページング

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerJoinListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/EconomyManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/RankingCommand.java`

**Interfaces:**
- Produces: `EconomyManager.getPublicTopBalances(int limit, int offset): List<BalanceEntry>` and `EconomyManager.getPublicPlayerCount(): int`.
- Consumes: existing `ensurePlayerRecord(OfflinePlayer)` to synchronize first-account insertion.

- [ ] **Step 1: Write failing query-contract tests**

Extract the public-balance SQL into package-visible constants. Add a test that asserts the query contract is executed against a temporary SQLite `players` table with three rows where the middle balance has `hide_balance = 1`: requesting limit 2 offset 0 must return the two public rows, and the count must be 2. This must use a real temporary database, not a mocked query method.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew test --tests org.craftcore.stellaria.managers.EconomyManagerRankingQueryTest`

Expected: it returns a private row or cannot call the new public query API.

- [ ] **Step 3: Implement atomic account creation and public queries**

Replace the join listener's `exists` followed by `insertAsync` block with `plugin.getEconomyManager().ensurePlayerRecord(player)`. Keep `INSERT OR IGNORE` synchronous in `ensurePlayerRecord`. Make `createPlayerAccount(OfflinePlayer)` call it and return whether the account exists after the operation. In `depositPlayer`, `withdrawPlayer`, and `setBalance`, return failure when the update count is zero; never report a 0-row update as success.

Implement the two public ranking methods with these queries:

```sql
SELECT uuid, name, coins FROM players WHERE hide_balance = 0
ORDER BY coins DESC LIMIT ? OFFSET ?
```

```sql
SELECT COUNT(*) AS cnt FROM players WHERE hide_balance = 0
```

Have `RankingCommand` use only these APIs for money page bounds and display. Remove the post-page `isHideBalance` filtering.

- [ ] **Step 4: Run the focused test and all tests**

Run: `./gradlew test --tests org.craftcore.stellaria.managers.EconomyManagerRankingQueryTest`

Run: `./gradlew test`

Expected: page one is filled by public rows and all tests pass.

- [ ] **Step 5: Commit economy and ranking changes**

```bash
git add src/main/java/org/craftcore/stellaria/listeners/PlayerJoinListener.java src/main/java/org/craftcore/stellaria/managers/EconomyManager.java src/main/java/org/craftcore/stellaria/commands/RankingCommand.java src/test/java/org/craftcore/stellaria/managers/EconomyManagerRankingQueryTest.java
git commit -m "fix: atomically create accounts and page public balances"
```

### Task 5: 投票ワールド制限と mute 期限の安全化

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/commands/TimeVoteCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/WeatherVoteCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/MuteCommand.java`
- Modify: `src/test/java/org/craftcore/stellaria/utils/DurationParserTest.java`

**Interfaces:**
- Consumes: `DurationParser.expiresAtMillis(long, long)` from Task 1.
- Produces: private `isEligibleVoter(Player)` in both vote commands, returning true only when `isVoting` and `player.getWorld().equals(votingWorld)`.

- [ ] **Step 1: Extend the failing duration test**

```java
@Test
void keepsPermanentDurationDistinctFromOverflow() {
    assertEquals(-1L, DurationParser.expiresAtMillis(100L, -1L));
    assertThrows(IllegalArgumentException.class,
            () -> DurationParser.expiresAtMillis(Long.MAX_VALUE - 1, 1L));
}
```

For each vote command, add a test-only world comparison helper receiving a voter world and vote world; assert that equal worlds are eligible and distinct worlds are not. This must fail before the helper is extracted.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.DurationParserTest --tests org.craftcore.stellaria.commands.TimeVoteCommandTest --tests org.craftcore.stellaria.commands.WeatherVoteCommandTest`

Expected: absent helper/API or incorrect acceptance of a non-target world.

- [ ] **Step 3: Reject invalid finite mute values and cross-world votes**

In `MuteCommand`, replace `System.currentTimeMillis() + seconds * 1000L` with `DurationParser.expiresAtMillis(System.currentTimeMillis(), seconds)`, catch its `IllegalArgumentException`, and send `mute.invalid_duration` without muting.

Before duplicate-vote checks in `tvaccept`, `tvdeny`, `wvaccept`, and `wvdeny`, reject when `!player.getWorld().equals(votingWorld)` with `timevote.world_disabled` or `weathervote.world_disabled`. Keep initiating a vote and all broadcasts unchanged.

- [ ] **Step 4: Run focused tests and all tests**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.DurationParserTest --tests org.craftcore.stellaria.commands.TimeVoteCommandTest --tests org.craftcore.stellaria.commands.WeatherVoteCommandTest`

Run: `./gradlew test`

Expected: only the start world can cast a vote, and finite overflow is rejected.

- [ ] **Step 5: Commit vote and mute work**

```bash
git add src/main/java/org/craftcore/stellaria/commands/TimeVoteCommand.java src/main/java/org/craftcore/stellaria/commands/WeatherVoteCommand.java src/main/java/org/craftcore/stellaria/commands/MuteCommand.java src/test/java/org/craftcore/stellaria
git commit -m "fix: limit votes to their target world"
```

### Task 6: 他プラグインのスコアボードを保持する

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/utils/BoardUtil.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/ScoreboardManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/TabListManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/BelownameManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/NametagManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java`

**Interfaces:**
- Produces: `BoardUtil.getOrCreateStellariaBoard(Player): Scoreboard` and `BoardUtil.forgetPlayer(UUID)`.
- Produces: nullable board handling in all four HUD managers; a null result means skip rendering for that viewer.

- [ ] **Step 1: Write failing board ownership tests**

Use a test `ScoreboardManager` with a main board and two generated boards. After `getOrCreateStellariaBoard` creates and assigns board A, assert a repeat call returns A. Assign externally-created board B, then assert the method returns `null` and leaves player scoreboard B unchanged. Name the tests `reusesOnlyBoardCreatedByStellaria` and `doesNotAdoptExternalPersonalBoard`.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.BoardUtilTest`

Expected: existing `ensurePersonalBoard` returns external board B and the test fails.

- [ ] **Step 3: Implement ownership tracking and nullable rendering**

Track only boards created by this utility in a `ConcurrentHashMap<UUID, Scoreboard>`. If a player has the main board, create a board, store it under their UUID, assign it, and return it. If their current board equals the stored board, return it. For any other board return `null`; do not change the player's board. Remove the stored entry in `forgetPlayer` from `PlayerListener#onPlayerLeave`.

Replace calls to `ensurePersonalBoard` in the four HUD managers with the new method. In every call site, immediately return/continue if it returns null. Preserve existing objective/team names and rendering behavior for Stellaria-owned boards.

- [ ] **Step 4: Run focused test and full suite**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.BoardUtilTest`

Run: `./gradlew test`

Expected: external scoreboard B remains assigned and no existing test regresses.

- [ ] **Step 5: Commit scoreboard coexistence changes**

```bash
git add src/main/java/org/craftcore/stellaria/utils/BoardUtil.java src/main/java/org/craftcore/stellaria/managers/ScoreboardManager.java src/main/java/org/craftcore/stellaria/managers/TabListManager.java src/main/java/org/craftcore/stellaria/managers/BelownameManager.java src/main/java/org/craftcore/stellaria/managers/NametagManager.java src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java src/test/java/org/craftcore/stellaria/utils/BoardUtilTest.java
git commit -m "fix: preserve externally managed scoreboards"
```

### Task 7: 最終統合検証

**Files:**
- Modify: `docs/superpowers/plans/2026-09-17-codex-audit-medium-low-fixes.md` (check off verified steps only)

- [ ] **Step 1: Inspect the complete diff and requirement coverage**

Run: `git diff 99bdacb..HEAD --check`

Confirm the diff includes M-1 through M-10 and L-1 through L-5, and that every changed user-facing reload message is read through `ConfigManager`.

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test`

Expected: exit code 0 with no failing tests.

- [ ] **Step 3: Run the production build**

Run: `./gradlew build`

Expected: exit code 0 and a plugin jar under `build/libs/`.

- [ ] **Step 4: Commit the verified plan checkboxes if they changed**

```bash
git add docs/superpowers/plans/2026-09-17-codex-audit-medium-low-fixes.md
git commit -m "docs: record medium low audit verification"
```
