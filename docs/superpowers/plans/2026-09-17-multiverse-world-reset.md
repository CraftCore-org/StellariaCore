# Multiverse World Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Regenerate configured resource worlds through Multiverse-Core 5.8.1 while preserving MV settings, gamerules, and world borders but replacing the previous spawn.

**Architecture:** `WorldResetManager` retains its existing scheduling, evacuation, lockout, and database-cleanup responsibilities. Its destructive Bukkit folder deletion path is replaced by a direct `MultiverseCoreApi` regeneration call, configured to preserve the requested MV-owned settings. The manager then replaces MV's stored spawn with the newly generated Bukkit spawn before it considers a reset successful.

**Tech Stack:** Java 21, Paper 1.21.11, Multiverse-Core 5.8.1 API, Gradle Kotlin DSL, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-17-multiverse-world-reset-time-sync-design.md`

## Global Constraints

- Use `org.mvplugins.multiverse.core:multiverse-core:5.8.1` as a `compileOnly` dependency from `https://repo.onarandombox.com/content/groups/public/`.
- Declare `Multiverse-Core` in `plugin.yml` `softdepend`; never bundle its API or implementation.
- Run Multiverse world operations only on Paper's global/main server scheduler.
- Preserve MV world config, gamerules, and world border; use a random seed; do not preserve the old spawn location.
- If MV is unavailable, the target is not a loaded MV world, regeneration fails, or new-spawn persistence fails, do not use the old manual deletion fallback and do not delete homes/warps or clear lockout for that world.
- `world-reset` must not read `world-time-sync` configuration or start a time-sync task.

---

## File structure

- `build.gradle.kts` — resolves the compile-only Multiverse-Core 5.8.1 API.
- `src/main/resources/plugin.yml` — guarantees MV loads before this optional integration is used.
- `src/main/java/org/craftcore/stellaria/managers/WorldResetManager.java` — invokes MV regeneration in place of manual world-folder deletion and restores a fresh spawn.

### Task 1: Add the Multiverse-Core compile-time integration

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: MV Maven repository and artifact `org.mvplugins.multiverse.core:multiverse-core:5.8.1`.
- Produces: compile-time availability of `MultiverseCoreApi`, `WorldManager`, `LoadedMultiverseWorld`, and `RegenWorldOptions`; plugin load ordering through `softdepend`.

- [ ] **Step 1: Add the repository and compile-only dependency**

  In `repositories`, add:

  ```kotlin
  maven("https://repo.onarandombox.com/content/groups/public/")
  ```

  In `dependencies`, add:

  ```kotlin
  compileOnly("org.mvplugins.multiverse.core:multiverse-core:5.8.1")
  ```

- [ ] **Step 2: Declare the optional plugin dependency**

  Change the existing soft dependency declaration to include `Multiverse-Core`:

  ```yaml
  softdepend: [LuckPerms, NuVotifier, Multiverse-Core]
  ```

- [ ] **Step 3: Compile the production sources**

  Run: `./gradlew compileJava`

  Expected: `BUILD SUCCESSFUL`; Gradle resolves the MV API without placing it in the shadow jar.

- [ ] **Step 4: Commit the integration metadata**

  ```bash
  git add build.gradle.kts src/main/resources/plugin.yml
  git commit -m "build: add Multiverse-Core API"
  ```

### Task 2: Replace destructive Bukkit regeneration with Multiverse regeneration

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/WorldResetManager.java`

**Interfaces:**
- Consumes: `MultiverseCoreApi.get()`, `WorldManager#getLoadedWorld(String)`, and `WorldManager#regenWorld(RegenWorldOptions)`.
- Produces: a private per-world reset path that returns success only after MV regeneration and fresh-spawn persistence have both completed; `performReset(List<String>)` retains its current `resetCompleted`-based database cleanup and lockout release.

- [ ] **Step 1: Identify the testable and runtime-only boundaries before editing**

  Record in the PR/commit description that the MV API requires a loaded Paper server and has no in-repository fake. The existing schedule, evacuation, and location-cleanup flow remains unchanged; the new runtime-only boundary is verified in Task 3. Do not add a fake manual folder-deletion fallback.

- [ ] **Step 2: Replace imports and remove the obsolete deletion path**

  Remove imports used only by the old regeneration (`WorldCreator`, `WorldType`, `File`, and `ThreadLocalRandom`) and delete `deleteWorldFolder(File)`. Add the MV API imports:

  ```java
  import org.mvplugins.multiverse.core.MultiverseCoreApi;
  import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
  import org.mvplugins.multiverse.core.world.options.RegenWorldOptions;
  ```

- [ ] **Step 3: Implement the minimal MV reset branch**

  In the per-world loop inside `performReset`, retain the existing online-player guard. Obtain the API only after confirming that `Bukkit.getPluginManager().isPluginEnabled("Multiverse-Core")`; catch `IllegalStateException` from `MultiverseCoreApi.get()` and log a warning. Resolve the exact loaded world with the world manager; if absent, log and continue.

  Regenerate using this exact option chain:

  ```java
  RegenWorldOptions options = RegenWorldOptions.world(loadedWorld)
          .randomSeed(true)
          .keepWorldConfig(true)
          .keepGameRule(true)
          .keepWorldBorder(true);
  ```

  Check the `Attempt` result before doing anything else. On success, obtain the resulting `LoadedMultiverseWorld`, then reset its MV spawn to its new Bukkit world spawn:

  ```java
  LoadedMultiverseWorld recreated = attempt.get();
  recreated.setSpawnLocation(recreated.getBukkitWorld().getSpawnLocation());
  ```

  Treat a failed `setSpawnLocation` result as a reset failure: emit `severe`, skip database cleanup, and keep the lockout. Only after both operations succeed, run the existing `DELETE FROM homes`/`DELETE FROM warps`, log success, and add the world name to `resetCompleted`.

- [ ] **Step 4: Compile after the API integration**

  Run: `./gradlew compileJava`

  Expected: `BUILD SUCCESSFUL`; no `WorldCreator`, folder deletion, or direct Bukkit unload remains in `WorldResetManager`.

- [ ] **Step 5: Commit the MV reset implementation**

  ```bash
  git add src/main/java/org/craftcore/stellaria/managers/WorldResetManager.java
  git commit -m "feat: regenerate worlds through Multiverse"
  ```

### Task 3: Verify reset safety on a local Multiverse server

**Files:**
- Verify only: `run/plugins/`

**Interfaces:**
- Consumes: the built StellariaCore jar, Multiverse-Core 5.8.1 installed in `run/plugins`, and a configured test resource world.
- Produces: evidence that the real runtime API preserves the requested settings and replaces the old spawn.

- [ ] **Step 1: Build the plugin artifact**

  Run: `./gradlew build`

  Expected: `BUILD SUCCESSFUL` and the shadow jar copied to `run/plugins/`.

- [ ] **Step 2: Configure a disposable MV world**

  In the local Paper server, create/import a non-default world, set a distinctive world border and gamerule, and set a distinctive MV spawn away from the natural spawn. Configure only that world in `world-reset.worlds`, set `world-reset.enabled: true`, and use an evacuation world outside the target list.

- [ ] **Step 3: Execute and inspect one reset**

  Run `/worldreset now <world>` as an operator. Confirm that the server logs a successful MV regeneration; the world seed/terrain changes; MV world properties, gamerule, and border persist; `/mv spawn` uses the regenerated natural spawn rather than the former custom coordinate; and homes/warps in that world are removed only after success.

- [ ] **Step 4: Exercise the failure guard**

  Stop or disable Multiverse-Core, restart the local server, then invoke `/worldreset now <world>`. Confirm no world folder is deleted, no new Bukkit world is created, and the log names the missing/unavailable MV integration.

- [ ] **Step 5: Commit only source changes if manual verification required a correction**

  If Task 3 required a source correction, first run `./gradlew test`, then:

  ```bash
  git add src/main/java/org/craftcore/stellaria/managers/WorldResetManager.java
  git commit -m "fix: handle Multiverse reset runtime result"
  ```

