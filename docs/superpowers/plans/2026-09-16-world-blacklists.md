# Feature World Blacklists Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-feature, blacklist-based world restrictions.

**Architecture:** A stateless `WorldBlacklistUtil` compares a world name against each feature's `disabled-worlds` list. Commands check the actor's current world; Home/Warp also check destinations; Kikori/Mine check their break-event locations; Land bypasses all protection and display behavior in disabled worlds.

**Tech Stack:** Java 21, Paper 1.21.11, Gradle, JUnit Jupiter 5, YAML.

**Spec:** `docs/superpowers/specs/2026-09-16-world-blacklists-design.md`

## Global Constraints

- Add `disabled-worlds: []` below `home`, `warp`, `kikori`, `mine`, `land`, `weathervote`, and `timevote`.
- Empty lists allow all worlds; comparisons are case-insensitive; do not cache values.
- Delete default `land.enabled-worlds`; do not keep a compatibility fallback.
- Stored Home/Warp records remain intact when their destination is blacklisted.
- Add feature-local `world_disabled` messages in `messages.yml`.
- Do not stage or alter ContainerLock files.

---

### Task 1: WorldBlacklistUtil

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/WorldBlacklistUtil.java`
- Create: `src/test/java/org/craftcore/stellaria/utils/WorldBlacklistUtilTest.java`

**Interfaces:** Produces `public static boolean isBlacklisted(List<String> disabledWorlds, String worldName)`.

- [ ] **Step 1: Write the failing JUnit test**

```java
@Test
void matchesNamesIgnoringCaseAndAllowsOtherWorlds() {
    assertTrue(WorldBlacklistUtil.isBlacklisted(List.of("world_nether"), "WORLD_NETHER"));
    assertFalse(WorldBlacklistUtil.isBlacklisted(List.of(), "world"));
    assertFalse(WorldBlacklistUtil.isBlacklisted(List.of("world_the_end"), "world"));
}
```

- [ ] **Step 2: Verify red**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.WorldBlacklistUtilTest`

Expected: compilation fails because `WorldBlacklistUtil` is absent.

- [ ] **Step 3: Implement the utility**

```java
public final class WorldBlacklistUtil {
    private WorldBlacklistUtil() {}

    public static boolean isBlacklisted(List<String> disabledWorlds, String worldName) {
        return disabledWorlds.stream().anyMatch(name -> name.equalsIgnoreCase(worldName));
    }
}
```

- [ ] **Step 4: Verify green and commit**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.WorldBlacklistUtilTest`

Expected: test passes.

```bash
git add src/main/java/org/craftcore/stellaria/utils/WorldBlacklistUtil.java src/test/java/org/craftcore/stellaria/utils/WorldBlacklistUtilTest.java
git commit -m "feat: add world blacklist utility"
```

### Task 2: Configuration and messages

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`

**Interfaces:** Produces `*.disabled-worlds` paths and `*.world_disabled` messages for Tasks 3–6.

- [ ] **Step 1: Add defaults and remove Land whitelist**

```yaml
home:
  disabled-worlds: []
warp:
  disabled-worlds: []
kikori:
  disabled-worlds: []
mine:
  disabled-worlds: []
land:
  disabled-worlds: []
weathervote:
  disabled-worlds: []
timevote:
  disabled-worlds: []
```

Remove `land.enabled-worlds`.

- [ ] **Step 2: Add exact messages**

```yaml
home: { world_disabled: "&%cこのワールドではホームを利用できません。" }
warp: { world_disabled: "&%cこのワールドではワープを利用できません。" }
kikori: { world_disabled: "&%cこのワールドでは木こり機能を利用できません。" }
mine: { world_disabled: "&%cこのワールドでは鉱石一括破壊機能を利用できません。" }
land: { world_disabled: "&%cこのワールドでは土地保護を利用できません。" }
weathervote: { world_disabled: "&%cこのワールドでは天気投票を利用できません。" }
timevote: { world_disabled: "&%cこのワールドでは時間投票を利用できません。" }
```

- [ ] **Step 3: Process resources and commit**

Run: `./gradlew processResources`

Expected: success; generated config has seven empty lists.

```bash
git add src/main/resources/config.yml src/main/resources/messages.yml
git commit -m "feat: configure feature world blacklists"
```

### Task 3: Home and Warp source/destination guards

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/commands/HomeCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/WarpCommand.java`

**Interfaces:** Uses Task 1 and paths/messages from Task 2. `teleportToHome(Player, String)` and `teleportToWarp(Player, String)` remain the GUI and command destination boundary.

- [ ] **Step 1: Guard every command in the actor's world**

After each permission check, return `true` after this Home guard:

```java
if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("home.disabled-worlds", true), player.getWorld().getName())) {
    player.sendMessage(plugin.getConfigManager().getMessage("home.world_disabled", player));
    return true;
}
```

Use `warp.disabled-worlds` / `warp.world_disabled` in `WarpCommand`.

- [ ] **Step 2: Guard destinations before safety confirmation**

After null checks and before `TeleportSafetyUtil.attempt`, add:

```java
if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("home.disabled-worlds", true), destination.getWorld().getName())) {
    player.sendMessage(plugin.getConfigManager().getMessage("home.world_disabled", player));
    return;
}
```

Add the Warp equivalent. Do not change `HomeSelectGui` or `WarpSelectGui`; both already call these methods.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew compileJava`

Expected: success.

Manual: blacklist a saved destination, then verify `/home`, `/warp`, and GUI clicks reject it while records remain listed.

```bash
git add src/main/java/org/craftcore/stellaria/commands/HomeCommand.java src/main/java/org/craftcore/stellaria/commands/WarpCommand.java
git commit -m "feat: restrict homes and warps by world"
```

### Task 4: Kikori and Mine activation guards

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/commands/KikoriCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/MineCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/KikoriListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/MineListener.java`

**Interfaces:** Uses Task 1 and Task 2 paths/messages.

- [ ] **Step 1: Guard command execution**

After permission checks, add the analogous `kikori.disabled-worlds` and `mine.disabled-worlds` guard that sends the corresponding `world_disabled` message and returns `true`.

- [ ] **Step 2: Guard feature activation only**

In each `onBlockBreak`, preserve artificial-block cleanup, then add this before `tryStartFelling` / `tryStartMining`:

```java
if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("kikori.disabled-worlds", true), block.getWorld().getName())) {
    return;
}
```

Use `mine.disabled-worlds` in `MineListener`. Do not cancel ordinary breaking or change enabled state.

- [ ] **Step 3: Verify and commit**

Run: `./gradlew compileJava`

Expected: success.

Manual: enable each feature in an allowed world, move to a blacklisted world, break an eligible block, and verify only the first block breaks; return to an allowed world and verify activation resumes.

```bash
git add src/main/java/org/craftcore/stellaria/commands/KikoriCommand.java src/main/java/org/craftcore/stellaria/commands/MineCommand.java src/main/java/org/craftcore/stellaria/listeners/KikoriListener.java src/main/java/org/craftcore/stellaria/listeners/MineListener.java
git commit -m "feat: restrict mining features by world"
```

### Task 5: Disable Land behavior in blacklisted worlds

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/LandManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/LandCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/LandProtectionListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/LandAreaStatusListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/LandBorderParticleManager.java`

**Interfaces:** Add `LandManager.isWorldDisabled(String worldName): boolean`, backed by `land.disabled-worlds` and Task 1.

- [ ] **Step 1: Implement Land's bridge and change claim policy**

```java
public boolean isWorldDisabled(String worldName) {
    return WorldBlacklistUtil.isBlacklisted(
            plugin.getConfigManager().getStringList("land.disabled-worlds", true), worldName);
}
```

Replace the old `enabled-worlds` branch in `claim` with `if (isWorldDisabled(key.world())) return ClaimOutcome.of(ClaimResult.WORLD_DISABLED);`.

- [ ] **Step 2: Block Land commands**

After permission succeeds in `LandCommand.onCommand`, send `land.world_disabled` and return `true` if `isWorldDisabled(player.getWorld().getName())`.

- [ ] **Step 3: Bypass every protection event in disabled worlds**

Add `isDisabled(Location)` in `LandProtectionListener`. Every event handler returns before its LandManager check if the affected location is disabled. For explosions, keep disabled-world blocks in the event list; for pistons, bypass when either origin or destination world is disabled.

- [ ] **Step 4: Disable visual Land state without deleting preferences**

At `LandAreaStatusListener.updateDisplay` start, clear its actionbar channel and PvP bossbar then return when the location's world is disabled. In `LandBorderParticleManager.tick`, skip rendering for players in disabled worlds without calling `disableAndForget`.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew compileJava`

Expected: success.

Manual: claim in an allowed world, blacklist it and reload, then verify `/land` rejects, another player can edit the old claim, status/border effects disappear, and removing the blacklist restores protection.

```bash
git add src/main/java/org/craftcore/stellaria/managers/LandManager.java src/main/java/org/craftcore/stellaria/commands/LandCommand.java src/main/java/org/craftcore/stellaria/listeners/LandProtectionListener.java src/main/java/org/craftcore/stellaria/listeners/LandAreaStatusListener.java src/main/java/org/craftcore/stellaria/managers/LandBorderParticleManager.java
git commit -m "feat: disable land protection by world"
```

### Task 6: Weather and time voting guards

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/commands/WeatherVoteCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/TimeVoteCommand.java`

**Interfaces:** Uses Task 1 and Task 2 paths/messages.

- [ ] **Step 1: Guard every weather vote command**

Immediately after `Player player = (Player) sender;`, add:

```java
if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("weathervote.disabled-worlds", true), player.getWorld().getName())) {
    player.sendMessage(plugin.getConfigManager().getMessage("weathervote.world_disabled", player));
    return true;
}
```

This covers `/weathervote`, `/wvaccept`, `/wvdeny`, and GUI opening.

- [ ] **Step 2: Guard every time vote command**

Add the equivalent `timevote.disabled-worlds` / `timevote.world_disabled` guard at the same location in `TimeVoteCommand` for `/timevote`, `/tvaccept`, and `/tvdeny`.

- [ ] **Step 3: Full verification and commit**

Run: `./gradlew build`

Expected: exit 0; blacklist unit tests pass.

Manual: start a vote in an allowed world, then attempt accept/deny from a blacklisted world; the vote count remains unchanged.

```bash
git add src/main/java/org/craftcore/stellaria/commands/WeatherVoteCommand.java src/main/java/org/craftcore/stellaria/commands/TimeVoteCommand.java
git commit -m "feat: restrict world voting by world"
```
