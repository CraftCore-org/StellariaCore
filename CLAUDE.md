# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

StellariaCore is a PaperMC (Minecraft 1.21) plugin written in Java 21, built with Gradle (Kotlin DSL). It provides a small set of gameplay features for the "Stellaria" server: TPA-style teleport requests, a SQLite-backed economy hooked into Vault, join/quit message formatting, and a custom tablist.

## Build & run

- `./gradlew build` — compiles, runs Shadow to produce the fat jar (`build/libs/StellariaCore-<version>.jar`, classifier disabled so it's the plain jar name), then copies it into `run/plugins/` for local testing.
- `./gradlew runServer` — launches a local Paper 1.21.11 test server (via the `run-paper` plugin) with the plugin loaded from `run/plugins/`.
- There are no automated tests in this repo (no `src/test`, no test task configured).
- CI (`.github/workflows/build.yml`) just runs `./gradlew build --no-daemon --refresh-dependencies` on push/PR to `main`.
- On every push to `main` that isn't itself a version-bump commit, `.github/workflows/version-bump.yml` bumps the patch/minor/major version in `build.gradle.kts` based on the merged PR's `bump:major`/`bump:minor` label (default patch), commits with `[ci-version-bump]`, tags, builds, and publishes a GitHub Release — don't hand-edit the `version` in `build.gradle.kts` on `main`.

Runtime dependencies (`compileOnly`, expected to be provided by the server): Paper API, Citizens, PacketEvents, VaultUnlockedAPI, PlaceholderAPI, Lombok. The only bundled/shaded dependency is `sqlite-jdbc`. `plugin.yml`'s `version` is templated from the Gradle `version` at `processResources` time — don't edit the version there directly.

## Architecture

**Wiring is manual, all in `StellariaCore#onEnable`** — there's no DI container. Order matters: construct `ConfigManager` right after `saveDefaultConfig()` → `DatabaseManager.connect()` → create the `players` table → construct `EconomyManager` → register it with Vault's `ServicesManager` (skipped with a warning if Vault isn't present) → register listeners (including `PlayerQuitListener`) → register the `TpaCore` executor for all six tpa commands → register `ReloadCommand` for `/stellariareload` → print the console banner. `onDisable` just closes the DB connection.

**`managers/ConfigManager` centralizes `config.yml` access.** Instantiated once in `onEnable` (unlike `DatabaseManager`, it holds real instance state — a `plugin` reference) and exposed via `StellariaCore#getConfigManager()`. Besides plain `getString`/`getInt`, it has `getMessage(path, OfflinePlayer)` which runs the raw config string through `FormatUtil.text(...)` — `%player%` substitution, PlaceholderAPI, and color codes all applied in one call. `/stellariareload` (op-only, no permission node) calls `ConfigManager.reload()` → `plugin.reloadConfig()`. Every class that sends a config-driven message (`TpaCore`, `PlayerJoinListener`, `PlayerQuitListener`) should go through `ConfigManager`, not `plugin.getConfig()` directly — a past merge briefly reintroduced direct `plugin.getConfig()` calls in `TpaCore`, so watch for that pattern creeping back in.

**Persistence is a single static SQLite wrapper, not a DAO layer.** `managers/DatabaseManager` holds the one `Connection` as a static field and exposes generic `query`/`queryOne`/`insert`/`update`/`execute` (+ `Async` variants that hop off-thread via `Bukkit.getAsyncScheduler()`) plus a `transaction()` helper. Every feature that touches the DB (currently `EconomyManager`, `PlayerJoinListener`) calls these statics directly with raw table/column names rather than going through a repository — there's one table, `players` (`uuid`, `name`, `coins`). Despite living in `managers/`, it is fully static (private constructor, no instance state but the static `Connection`) — unlike `EconomyManager` below.

**Naming convention: `utils/` = stateless static helpers, `managers/` = longer-lived components with more responsibility (not strictly "instantiated").** Everything in `utils/` (`ColorUtil`, `ConsoleUtil`, `FormatUtil`) has a private constructor and only static methods, same as `DatabaseManager`. `managers/EconomyManager` is instantiated once in `onEnable` and holds a `plugin` reference; it extends Vault's `AbstractEconomy` and implements balance ops by reading/writing `DatabaseManager` directly (no caching). `managers/PluginManager` is an unused singleton stub (`initialize()` is empty, nothing calls it) — the commented-out `PluginManager.getInstance().initialize()` line in `StellariaCore` reflects that. So the `utils`/`managers` split currently tracks "how central/stateful is this to the plugin's domain" more than "static vs. instantiated" — don't assume everything under `managers/` is instance-based.

**Two listener classes overlap on player join/quit without coordinating:** `PlayerJoinListener` (DB record creation + join message) and `PlayerListener` (tablist refresh on join + TPA request cleanup on quit) both fire on the same events; `PlayerQuitListener` (quit message) is a third one covering the same quit event. All three are registered in `onEnable`, but this split-by-concern-across-separate-listener-classes pattern means a new join/quit behavior could plausibly go in any of the three — check all of them before assuming one is authoritative.

**Text formatting has two parallel color pipelines.** `ColorUtil` supports legacy `&` codes, `&#RRGGBB` hex, and a custom `&%<char>` palette (defined in `ColorUtil.CUSTOM_COLORS`); `FormatUtil` wraps it and additionally does `%player%` substitution and PlaceholderAPI expansion (`FormatUtil.text(...)`, normally reached via `ConfigManager.getMessage(...)`, is what every config-driven message goes through). `config.yml` is written entirely in the `&%<char>` custom palette (not raw `§` codes, not plain `&a`-style legacy codes) — format codes like bold/reset (`&l`, `&r`) stay as plain legacy codes since they're not colors and have no `&%` entry. `utils/Utils.colorize` is an older, separate implementation of roughly the same hex-color logic and is not called from anywhere — don't extend it, use `ColorUtil`/`FormatUtil`.

**`TpaCore` is a single `CommandExecutor` for all six tpa commands** (`tpa`, `tpaccept`, `tpdeny`, `tphere`, `tphaccept`, `tphdeny`), dispatching on `command.getName()` inside one `onCommand`. It takes a `StellariaCore plugin` in its constructor to reach `ConfigManager` for every message under `messages.tpa.*` — every user-facing string is config-driven, none are hardcoded. Pending requests live in two static `Map<UUID, List<UUID>>` fields (in-memory only, cleared per-player via `resetPlayerTeleportRequests` on quit) — there's no persistence or timeout for requests.

`config.yml` has entries marked `!NOTE: 未実装` (not yet implemented): `server.prefix` and `economy.default-balance` are read from nowhere in the code yet.
