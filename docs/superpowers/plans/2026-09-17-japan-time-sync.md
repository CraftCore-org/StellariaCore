# Japan Time World Synchronization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Continuously synchronize the visible sun position of explicitly configured worlds with Japan Standard Time.

**Architecture:** `JapanTimeUtil` owns the deterministic JST-to-Minecraft-tick conversion and is unit tested without Bukkit. `JapanTimeSyncManager` owns its independent once-per-second scheduler and looks up configured Bukkit worlds. `StellariaCore` constructs, starts, restarts, and stops that manager, without involving `WorldResetManager` or Multiverse.

**Tech Stack:** Java 21, Paper 1.21.11, Paper global region scheduler, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-17-multiverse-world-reset-time-sync-design.md`

## Global Constraints

- Use the fixed `ZoneId.of("Asia/Tokyo")`, never the host system time zone.
- Map visible solar time as JST 00:00 → 18000, 06:00 → 0, 12:00 → 6000, and 18:00 → 12000 ticks.
- Synchronize only `world-time-sync.worlds` while `world-time-sync.enabled` is true.
- The plugin must not set `advance_time`; configuration comments must instruct operators to run `/gamerule advance_time false` in every target world.
- `timevote.disabled-worlds` remains the manual-vote exclusion mechanism and must document that the same world names belong there.
- `world-time-sync` must not read `world-reset` configuration, invoke Multiverse, or alter reset behavior.

---

## File structure

- `src/main/java/org/craftcore/stellaria/utils/JapanTimeUtil.java` — pure `Instant` to Minecraft-tick conversion.
- `src/test/java/org/craftcore/stellaria/utils/JapanTimeUtilTest.java` — deterministic conversion tests.
- `src/main/java/org/craftcore/stellaria/managers/JapanTimeSyncManager.java` — reloadable Paper scheduler and configured-world application.
- `src/main/java/org/craftcore/stellaria/StellariaCore.java` — manager construction and lifecycle calls.
- `src/main/resources/config.yml` — disabled-by-default section and operator comments.

### Task 1: Test-drive the JST-to-tick converter

**Files:**
- Create: `src/test/java/org/craftcore/stellaria/utils/JapanTimeUtilTest.java`
- Create: `src/main/java/org/craftcore/stellaria/utils/JapanTimeUtil.java`

**Interfaces:**
- Produces: `JapanTimeUtil.minecraftTicks(Instant instant): long`.
- Consumes: `Instant`; implementation converts it with the fixed JST zone.

- [ ] **Step 1: Write the failing conversion tests**

  Create this JUnit test class before production code:

  ```java
  package org.craftcore.stellaria.utils;

  import org.junit.jupiter.api.Test;

  import java.time.Instant;

  import static org.junit.jupiter.api.Assertions.assertEquals;

  class JapanTimeUtilTest {
      @Test
      void mapsJstSolarMilestonesToMinecraftTicks() {
          assertEquals(18_000L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-17T15:00:00Z")));
          assertEquals(0L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-17T21:00:00Z")));
          assertEquals(6_000L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-18T03:00:00Z")));
          assertEquals(12_000L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-18T09:00:00Z")));
      }

      @Test
      void floorsPartialMinecraftTicksWithoutCrossingTheDayBoundary() {
          assertEquals(18_001L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-17T15:00:04Z")));
          assertEquals(17_999L, JapanTimeUtil.minecraftTicks(Instant.parse("2026-09-17T14:59:59Z")));
      }
  }
  ```

- [ ] **Step 2: Run the focused test and verify it fails for the missing class**

  Run: `./gradlew test --tests org.craftcore.stellaria.utils.JapanTimeUtilTest`

  Expected: compilation failure stating that `JapanTimeUtil` does not exist.

- [ ] **Step 3: Implement the smallest pure converter**

  Create `JapanTimeUtil` with a private constructor, `ZoneId.of("Asia/Tokyo")`, and this method:

  ```java
  public static long minecraftTicks(Instant instant) {
      long nanosPerDay = 86_400_000_000_000L;
      long shiftedNanos = Math.floorMod(
              instant.atZone(JAPAN_TIME).toLocalTime().toNanoOfDay() + 64_800_000_000_000L,
              nanosPerDay);
      return shiftedNanos * 24_000L / nanosPerDay;
  }
  ```

- [ ] **Step 4: Run the focused test and verify it passes**

  Run: `./gradlew test --tests org.craftcore.stellaria.utils.JapanTimeUtilTest`

  Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Commit the converter and its test**

  ```bash
  git add src/main/java/org/craftcore/stellaria/utils/JapanTimeUtil.java src/test/java/org/craftcore/stellaria/utils/JapanTimeUtilTest.java
  git commit -m "feat: add JST world-time conversion"
  ```

### Task 2: Add the independent scheduler and configuration

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/JapanTimeSyncManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/resources/config.yml`

**Interfaces:**
- Consumes: `JapanTimeUtil.minecraftTicks(Instant.now())`, `world-time-sync.enabled`, and `world-time-sync.worlds`.
- Produces: `JapanTimeSyncManager.start()`, `restart()`, and `stop()`; each can be called safely from the plugin lifecycle.

- [ ] **Step 1: Add the disabled configuration and operational comments**

  Immediately after the existing `timevote` section, add:

  ```yaml
  # world-time-sync.worlds に指定するワールドでは、時間投票を無効化するため
  # disabled-worlds にも同じワールド名を追加してください。
  world-time-sync:
    enabled: false
    worlds: []
    # 対象ワールド内で /gamerule advance_time false を実行してください。
  ```

  Keep `timevote.disabled-worlds` unchanged by default.

- [ ] **Step 2: Implement the minimal manager**

  Create `JapanTimeSyncManager` with a `StellariaCore` field, a nullable `ScheduledTask`, and a `Set<String> warnedMissingWorlds`. Implement:

  ```java
  public void start();
  public void restart();
  public void stop();
  ```

  `start()` cancels an existing active task, clears `warnedMissingWorlds`, returns when `world-time-sync.enabled` is false, otherwise schedules `tick()` every 20 ticks through `Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)`. `tick()` computes one tick value from `JapanTimeUtil.minecraftTicks(Instant.now())`, iterates the configured names, calls `Bukkit.getWorld(name)`, logs a warning only on the first null result per name, and calls `world.setTime(ticks)` for every loaded result. `restart()` delegates to `start()`, and `stop()` cancels and nulls its task.

- [ ] **Step 3: Wire only the manager lifecycle**

  In `StellariaCore`, add a `JapanTimeSyncManager japanTimeSyncManager` field. Instantiate it beside `worldResetManager`; call `japanTimeSyncManager.start()` beside `worldResetManager.start()`; call `japanTimeSyncManager.restart()` in `reloadFeatureManagers()` beside `worldResetManager.restart()`; and call `japanTimeSyncManager.stop()` at the start of `onDisable()`.

  Do not pass the reset manager into the time manager, add reset-world checks, or add a Multiverse dependency to the new manager.

- [ ] **Step 4: Run the focused and complete test suites**

  Run: `./gradlew test --tests org.craftcore.stellaria.utils.JapanTimeUtilTest`

  Expected: `BUILD SUCCESSFUL`, 2 tests passed.

  Run: `./gradlew test`

  Expected: `BUILD SUCCESSFUL`; all existing tests and `JapanTimeUtilTest` pass.

- [ ] **Step 5: Commit the scheduler, wiring, and configuration**

  ```bash
  git add src/main/java/org/craftcore/stellaria/managers/JapanTimeSyncManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/resources/config.yml
  git commit -m "feat: synchronize worlds to Japan time"
  ```

### Task 3: Build and manually validate independent operation

**Files:**
- Verify only: `run/plugins/` and the local server configuration.

**Interfaces:**
- Consumes: a locally loaded Paper 1.21.11 server and a world name listed only in `world-time-sync.worlds`.
- Produces: evidence that time sync has no dependency on world reset or Multiverse.

- [ ] **Step 1: Build the deployable artifact**

  Run: `./gradlew build`

  Expected: `BUILD SUCCESSFUL`; the shadow jar is copied to `run/plugins/`.

- [ ] **Step 2: Configure one time-only test world**

  Set `world-time-sync.enabled: true` and list a loaded test world in `world-time-sync.worlds`. Leave `world-reset.enabled: false` and leave `world-reset.worlds` empty. In the target world, run `/gamerule advance_time false`; add the same world to `timevote.disabled-worlds` and reload the plugin.

- [ ] **Step 3: Confirm real-time solar synchronization**

  Wait for two scheduler updates and confirm the target world time tracks JST using the required mapping. Change that world's time manually, wait at most one second, and confirm the scheduler corrects it. Confirm an unlisted world is not changed.

- [ ] **Step 4: Confirm no reset/MV coupling**

  With `world-reset` still disabled, confirm time synchronization continues. Stop the local server without Multiverse-Core installed, restart it, and confirm the time-only configuration still works and `WorldResetManager` is never invoked.

- [ ] **Step 5: Commit only a correction discovered during validation**

  If manual validation required a source correction, first run `./gradlew test`, then:

  ```bash
  git add src/main/java/org/craftcore/stellaria/utils/JapanTimeUtil.java src/main/java/org/craftcore/stellaria/managers/JapanTimeSyncManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/resources/config.yml
  git commit -m "fix: stabilize Japan time synchronization"
  ```
