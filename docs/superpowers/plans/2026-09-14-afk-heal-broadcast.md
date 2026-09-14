# AFK / Heal / Broadcast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `/afk` (with timeout auto-detection and a tablist/belowname indicator), `/heal`, and `/broadcast` (manual + config-driven auto-cycling) to StellariaCore, ported from UtilsPlugin and adapted to StellariaCore's conventions.

**Architecture:** Three new single-purpose `CommandExecutor` classes in `commands/` (mirroring `ReloadCommand`), one new stateful `AfkManager` and one new `AutoBroadcastManager` in `managers/` (mirroring `ScoreboardManager`/`BelownameManager`'s `tick()` pattern and Folia-safe `Bukkit.getGlobalRegionScheduler()` scheduling), an `AfkManager`-backed `%afk%` token added to the existing `PlaceholderManager`, and two new activity-detection handlers added to the existing `PlayerListener`. No new listener classes, no persistence (AFK/broadcast state is in-memory only, same as TPA requests).

**Tech Stack:** Java 21, Paper API 1.21.11 (Folia-safe schedulers), Adventure `Component`, existing `ConfigManager`/`ColorUtil`/`FormatUtil`/`PlaceholderManager`.

**Spec:** `docs/superpowers/specs/2026-09-14-afk-heal-broadcast-design.md`

## Global Constraints

- Java 21 / Paper API 1.21.11-R0.1-SNAPSHOT (`build.gradle.kts`) — no new dependencies needed.
- Package root: `org.craftcore.stellaria`. New classes follow existing subpackage split: `commands/` (stateless command dispatch), `managers/` (stateful, longer-lived).
- All config/server settings go through `ConfigManager` (`getString`/`getInt`/`getBoolean`/`getStringList`/`getDouble`/`getMessage`/`getMessageList`) — never `plugin.getConfig()` directly.
- All user-facing text lives in `messages.yml`, accessed via `ConfigManager.getMessage(path, OfflinePlayer)` (which applies `%player%`, PlaceholderAPI, and color codes in one call). `config.yml` holds server/feature settings and config-driven *templates* (scoreboard/tablist/belowname/broadcast-auto), consistent with the existing split.
- Ticking features use `Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)` (Folia-safe), matching `ScoreboardManager`/`TabListManager`/`BelownameManager`. Do not use `BukkitRunnable`/`runTaskTimer`.
- Do not add a fourth join/quit-adjacent listener class — new AFK activity hooks go into the existing `PlayerListener`.
- `config.yml` uses the `&%<char>` custom color palette (see `ColorUtil.CUSTOM_COLORS`) for colors; `&l`/`&r` stay as plain legacy codes.
- **No automated test suite exists in this repo** (confirmed: no `src/test`, no test Gradle task). Every task's verification step is (a) `./gradlew build` succeeding, and (b) a precise manual check performed via `./gradlew runServer`, spelled out in the step itself — there is no unit-test step to write or run.
- Commit messages follow this repo's existing convention (`feat:`, `fix:`, `docs:`, `chore:` prefixes, seen in `git log`).

---

### Task 1: Config, messages, plugin.yml foundation

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/java/org/craftcore/stellaria/managers/ConfigManager.java`

**Interfaces:**
- Produces: `ConfigManager#getRawMessage(String path)` → `String` (unprocessed `messages.yml` string, no `%player%`/PAPI/color applied — needed by Task 6's `%message%` splicing, since `getMessage()` fully bakes the string and can't have a `Component` spliced into it afterwards).
- Produces: config keys `afk.enabled` (bool), `afk.timeout-seconds` (int), `afk.tag` (string), `broadcast.auto.enabled` (bool), `broadcast.auto.interval-minutes` (int), `broadcast.auto.messages` (string list).
- Produces: message keys `afk.must_be_player`, `afk.no_permission`, `afk.became`, `afk.returned`, `heal.must_be_player`, `heal.no_permission`, `heal.self`, `heal.other_sender`, `heal.other_receiver`, `heal.player_not_found`, `broadcast.no_permission`, `broadcast.format` (raw, via `getRawMessage`), `broadcast.usage`.
- Produces: `plugin.yml` commands `afk`, `heal`, `broadcast` (alias `bc`).

- [ ] **Step 1: Add the `afk` and `broadcast.auto` sections to `config.yml`, and wire `%afk%` into the tablist/belowname templates**

Append at the end of `src/main/resources/config.yml`:

```yaml
afk:
  enabled: true
  timeout-seconds: 300
  tag: "&%7[AFK] &r"

broadcast:
  auto:
    enabled: false
    interval-minutes: 5
    messages: []
```

Also change these two existing lines so the AFK tag shows up by default:

```yaml
tablist:
  value: "%afk%&%7%ping%ms"
```

```yaml
belowname:
  title: "%afk%&%c❤"
```

- [ ] **Step 2: Add the `afk`/`heal`/`broadcast` sections to `messages.yml`**

Append at the end of `src/main/resources/messages.yml`:

```yaml
afk:
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  became: "&%e%player% &%7がAFK（離席中）になりました。"
  returned: "&%e%player% &%7がAFKから復帰しました。"

heal:
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  self: "&%a体力と満腹度を回復しました。"
  other_sender: "&%a%player% &%7の体力と満腹度を回復しました。"
  other_receiver: "&%a体力と満腹度が回復されました。"
  player_not_found: "&%c%player% &%7はオンラインではありません。"

broadcast:
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  format: "&%6&l[お知らせ] &r%message%"
  usage: "&%c使用方法: /broadcast <メッセージ>"
```

- [ ] **Step 3: Add `afk`/`heal`/`broadcast` commands to `plugin.yml`**

In `src/main/resources/plugin.yml`, change the `commands:` block to:

```yaml
commands:
  tpa:
  tpaccept:
  tpdeny:
  tphere:
  tphaccept:
  tphdeny:
  stellariareload:
  afk:
  heal:
  broadcast:
    aliases: [bc]
```

- [ ] **Step 4: Add `ConfigManager#getRawMessage()`**

In `src/main/java/org/craftcore/stellaria/managers/ConfigManager.java`, add this method right after `getMessage(...)`:

```java
    /**
     * messages.yml の文字列を未加工のまま取得する（%player%/PAPI/色変換をしない）。
     * BroadcastCommand が %message% の位置にColorEventを持つComponentを差し込むために使う。
     * 単に送信するだけなら {@link #getMessage(String, OfflinePlayer)} を使うこと。
     */
    public String getRawMessage(String path) {
        return get("messages.yml").get().getString(path, "");
    }
```

- [ ] **Step 5: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. This validates the YAML parses (loaded into `ConfigFile`/`plugin.yml` at resource-processing time) and `ConfigManager` still compiles.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/config.yml src/main/resources/messages.yml src/main/resources/plugin.yml src/main/java/org/craftcore/stellaria/managers/ConfigManager.java
git commit -m "feat: add config/messages foundation for afk/heal/broadcast"
```

---

### Task 2: `AfkManager` + `%afk%` placeholder + timeout ticker

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/AfkManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/PlaceholderManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `ConfigManager.getBoolean/getInt/getMessage` (Task 1), `ColorUtil.component(String)` (existing).
- Produces: `AfkManager(StellariaCore plugin)`, `boolean isAfk(UUID)`, `void updateActivity(Player)`, `void setAfk(Player, boolean)`, `void removePlayer(UUID)`, `void tick()` — all consumed by Task 3 (`PlayerListener`) and Task 4 (`AfkCommand`).
- Produces: `StellariaCore#getAfkManager()` → `AfkManager`.

- [ ] **Step 1: Create `AfkManager`**

Create `src/main/java/org/craftcore/stellaria/managers/AfkManager.java`:

```java
package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AFK（離席）状態の管理。手動トグル（/afk）とタイムアウト自動判定の両方から
 * {@link #setAfk} を呼ぶ。状態はTPAリクエストと同様インメモリのみ（永続化なし）。
 * UtilsPlugin の AFKManager を StellariaCore の流儀（Folia対応スケジューラ・
 * ConfigManager経由のメッセージ）に合わせて移植したもの。
 */
public class AfkManager {

    private final StellariaCore plugin;
    private final Set<UUID> afkPlayers = new HashSet<>();
    private final Map<UUID, Long> lastActivityMillis = new HashMap<>();

    public AfkManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public boolean isAfk(UUID uuid) {
        return afkPlayers.contains(uuid);
    }

    /** 移動・インタラクト等の操作を検知した時に呼ぶ。AFK中だったら自動的に復帰させる。 */
    public void updateActivity(Player player) {
        lastActivityMillis.put(player.getUniqueId(), System.currentTimeMillis());
        if (afkPlayers.contains(player.getUniqueId())) {
            setAfk(player, false);
        }
    }

    /** /afk コマンドやタイムアウト検知から呼ぶ。状態が実際に変わった時だけ通知を出す。 */
    public void setAfk(Player player, boolean afk) {
        UUID uuid = player.getUniqueId();
        if (afk == afkPlayers.contains(uuid)) {
            return;
        }
        if (afk) {
            afkPlayers.add(uuid);
        } else {
            afkPlayers.remove(uuid);
        }
        lastActivityMillis.put(uuid, System.currentTimeMillis());
        broadcastStateChange(player, afk);
    }

    public void removePlayer(UUID uuid) {
        afkPlayers.remove(uuid);
        lastActivityMillis.remove(uuid);
    }

    /** afk.enabled が true の間、10秒毎にグローバルリージョンスケジューラから呼ばれる想定。 */
    public void tick() {
        long timeoutMillis = plugin.getConfigManager().getInt("afk.timeout-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Long lastActivity = lastActivityMillis.get(uuid);
            if (lastActivity == null) {
                lastActivityMillis.put(uuid, now);
                continue;
            }
            if (!afkPlayers.contains(uuid) && now - lastActivity > timeoutMillis) {
                setAfk(player, true);
            }
        }
    }

    private void broadcastStateChange(Player player, boolean afk) {
        String path = afk ? "afk.became" : "afk.returned";
        String message = plugin.getConfigManager().getMessage(path, player);
        Bukkit.broadcast(ColorUtil.component(message));
    }
}
```

- [ ] **Step 2: Add the `%afk%` token to `PlaceholderManager`**

In `src/main/java/org/craftcore/stellaria/managers/PlaceholderManager.java`, add `.replace("%afk%", resolveAfkTag(player))` as the first line of the `.replace(...)` chain inside `resolveBuiltIn`, so it reads:

```java
        return template
                .replace("%afk%", resolveAfkTag(player))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
```

(leave the rest of the chain untouched). Then add this private method below `resolveBuiltIn`:

```java
    private String resolveAfkTag(Player player) {
        if (!plugin.getAfkManager().isAfk(player.getUniqueId())) {
            return "";
        }
        return plugin.getConfigManager().getString("afk.tag", "&%7[AFK] &r");
    }
```

- [ ] **Step 3: Wire `AfkManager` into `StellariaCore`**

In `src/main/java/org/craftcore/stellaria/StellariaCore.java`, add the import `org.craftcore.stellaria.managers.AfkManager` and the field:

```java
    private AfkManager afkManager;
```

right after the `private MentionService mentionService;` field. In `onEnable()`, right after step "1. データベースの接続とテーブル作成" (after the `DatabaseManager.createTableIfNotExists(...)` call, before "2. EconomyManager のインスタンス化"), add:

```java
        this.afkManager = new AfkManager(this);
```

Then, right after the block that starts the scoreboard/tablist/belowname tickers (the three `Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)` lines), add:

```java
        if (configManager.getBoolean("afk.enabled", true)) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> afkManager.tick(), 200L, 200L);
        }
```

Finally, add the getter next to `getMentionService()`:

```java
    public AfkManager getAfkManager() {
        return this.afkManager;
    }
```

- [ ] **Step 4: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual verification**

Run: `./gradlew runServer`. While the server is starting, edit `run/plugins/StellariaCore/config.yml` and set `afk.timeout-seconds: 15`, then in the running server console run `stellariareload`... actually `afk.timeout-seconds` is read live every tick (no restart needed) — just edit the file and run `/stellariareload` as any op, or simply restart `runServer` with the lowered value set before first boot.

Join with a game client, then leave the character idle (no keyboard/mouse input) for ~15 seconds. Confirm:
- A chat message matching `afk.became` (`... がAFK（離席中）になりました。`) appears in chat.
- The tab list ping entry for your name now shows the `[AFK]` prefix before the ping.
- The health-display text below your nametag now also shows `[AFK]` before the heart icon.

(Movement won't clear it yet — that's Task 3.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/managers/AfkManager.java src/main/java/org/craftcore/stellaria/managers/PlaceholderManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add AfkManager with timeout detection and %afk% placeholder"
```

---

### Task 3: AFK activity detection in `PlayerListener`

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `AfkManager.updateActivity(Player)` / `AfkManager.removePlayer(UUID)` (Task 2), `ConfigManager.getBoolean` (Task 1).
- Produces: nothing new (behavioral change only).

- [ ] **Step 1: Give `PlayerListener` a `plugin` reference and add the AFK hooks**

Replace the full contents of `src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java` with:

```java
package org.craftcore.stellaria.listeners;

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.tpa.TpaCore;

public class PlayerListener implements Listener {

    private final StellariaCore plugin;

    public PlayerListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    // タブリストのヘッダー/フッター更新は TabListManager が毎tick自動で行うようになったので、
    // Join時にここで手動更新する必要は無くなった（旧 TabList.updateAllPlayersTablist()）。
    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event){
        TpaCore.resetPlayerTeleportRequests(event.getPlayer());
        plugin.getAfkManager().removePlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfigManager().getBoolean("afk.enabled", true)) {
            return;
        }
        // 位置が実際に変わった場合のみ活動とみなす（視点変更だけでは復帰させない）
        if (event.hasChangedPosition()) {
            plugin.getAfkManager().updateActivity(event.getPlayer());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getConfigManager().getBoolean("afk.enabled", true)) {
            return;
        }
        plugin.getAfkManager().updateActivity(event.getPlayer());
    }
}
```

- [ ] **Step 2: Update the `PlayerListener` construction call site**

In `src/main/java/org/craftcore/stellaria/StellariaCore.java`, find:

```java
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);
```

and change it to:

```java
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
```

- [ ] **Step 3: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification**

Run: `./gradlew runServer`, join, let yourself go AFK (per Task 2's manual check — either wait out the timeout or lower `afk.timeout-seconds` first). Once flagged AFK, walk a few blocks. Confirm:
- A chat message matching `afk.returned` (`... がAFKから復帰しました。`) appears.
- The `[AFK]` tags in the tab list and below-name display disappear.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: clear AFK state on player movement/interaction"
```

---

### Task 4: `/afk` command

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/AfkCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `AfkManager.isAfk(UUID)` / `AfkManager.setAfk(Player, boolean)` (Task 2), `ConfigManager.getMessage` (Task 1).
- Produces: nothing new (final piece of the AFK feature).

- [ ] **Step 1: Create `AfkCommand`**

Create `src/main/java/org/craftcore/stellaria/commands/AfkCommand.java`:

```java
package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.jetbrains.annotations.NotNull;

/**
 * /afk コマンド。実行するたびにAFK状態をトグルする。
 */
public class AfkCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public AfkCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("afk.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.afk")) {
            player.sendMessage(plugin.getConfigManager().getMessage("afk.no_permission", player));
            return true;
        }

        boolean currentlyAfk = plugin.getAfkManager().isAfk(player.getUniqueId());
        plugin.getAfkManager().setAfk(player, !currentlyAfk);
        return true;
    }
}
```

- [ ] **Step 2: Register the command**

In `src/main/java/org/craftcore/stellaria/StellariaCore.java`, add the import `org.craftcore.stellaria.commands.AfkCommand` and, right after the `getCommand("stellariareload").setExecutor(new ReloadCommand(this));` line, add:

```java
        getCommand("afk").setExecutor(new AfkCommand(this));
```

- [ ] **Step 3: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification**

Run: `./gradlew runServer`, join, run `/afk`. Confirm the `afk.became` message + `[AFK]` tags appear immediately (no need to wait for timeout). Run `/afk` again and confirm `afk.returned` + tags clearing, immediately.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/commands/AfkCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add /afk command"
```

---

### Task 5: `/heal` command

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/HealCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `ConfigManager.getMessage` (Task 1).
- Produces: nothing new (self-contained feature).

- [ ] **Step 1: Create `HealCommand`**

Create `src/main/java/org/craftcore/stellaria/commands/HealCommand.java`:

```java
package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * /heal コマンド。引数なしなら自分、引数ありなら指定プレイヤー（要 stellaria.heal.others）を
 * 全回復させる（HP・満腹度・隠し満腹度・炎消火）。
 */
public class HealCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public HealCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.heal")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("heal.no_permission", null));
            return true;
        }

        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(plugin.getConfigManager().getMessage("heal.must_be_player", null));
                return true;
            }
            target = self;
        } else {
            if (!sender.hasPermission("stellaria.heal.others")) {
                sender.sendMessage(plugin.getConfigManager().getMessage("heal.no_permission", null));
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(plugin.getConfigManager().getMessage("heal.player_not_found", Bukkit.getOfflinePlayer(args[0])));
                return true;
            }
        }

        target.setHealth(Objects.requireNonNull(target.getAttribute(Attribute.MAX_HEALTH)).getValue());
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setFireTicks(0);

        if (target.equals(sender)) {
            target.sendMessage(plugin.getConfigManager().getMessage("heal.self", target));
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessage("heal.other_sender", target));
            target.sendMessage(plugin.getConfigManager().getMessage("heal.other_receiver", target));
        }
        return true;
    }
}
```

- [ ] **Step 2: Register the command**

In `src/main/java/org/craftcore/stellaria/StellariaCore.java`, add the import `org.craftcore.stellaria.commands.HealCommand` and, right after the `/afk` registration line added in Task 4, add:

```java
        getCommand("heal").setExecutor(new HealCommand(this));
```

- [ ] **Step 3: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification**

Run: `./gradlew runServer`, join, take damage (fall, or `/damage`), then run `/heal`. Confirm HP/food/saturation are full and the `heal.self` message appears. With a second client (or an op testing `/heal <name>` on themselves granted `stellaria.heal.others`), confirm both the `heal.other_sender` and `heal.other_receiver` messages appear on the right sides. Run `/heal doesnotexist` and confirm `heal.player_not_found` shows with the typed name substituted.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/commands/HealCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add /heal command"
```

---

### Task 6: `/broadcast` command (manual)

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/BroadcastCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `ConfigManager.getMessage` / `ConfigManager.getRawMessage` (Task 1), `ColorUtil.component(String)` (existing).
- Produces: nothing new (self-contained feature).

- [ ] **Step 1: Create `BroadcastCommand`**

Create `src/main/java/org/craftcore/stellaria/commands/BroadcastCommand.java`:

```java
package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.jetbrains.annotations.NotNull;

/**
 * /broadcast（alias bc）コマンド。引数を結合し、messages.yml の broadcast.format
 * （%message% プレースホルダ）に差し込んで全員に送信する。
 * カラーコード使用は stellaria.broadcast.color 権限を持つ場合のみ有効。
 */
public class BroadcastCommand implements CommandExecutor {

    private static final String MESSAGE_TOKEN = "%message%";

    private final StellariaCore plugin;

    public BroadcastCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.broadcast")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("broadcast.no_permission", null));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.getConfigManager().getMessage("broadcast.usage", null));
            return true;
        }

        String rawMessage = String.join(" ", args);
        boolean colorAllowed = sender.hasPermission("stellaria.broadcast.color");
        Component messageBody = colorAllowed ? ColorUtil.component(rawMessage) : Component.text(rawMessage);

        String template = plugin.getConfigManager().getRawMessage("broadcast.format");
        Bukkit.broadcast(splice(template, messageBody));
        return true;
    }

    /** テンプレート文字列の %message% の位置にComponentを差し込んで、前後をColorUtilで色変換する。 */
    private Component splice(String template, Component messageBody) {
        int index = template.indexOf(MESSAGE_TOKEN);
        if (index < 0) {
            return ColorUtil.component(template).append(messageBody);
        }
        Component prefix = ColorUtil.component(template.substring(0, index));
        Component suffix = ColorUtil.component(template.substring(index + MESSAGE_TOKEN.length()));
        return prefix.append(messageBody).append(suffix);
    }
}
```

- [ ] **Step 2: Register the command**

In `src/main/java/org/craftcore/stellaria/StellariaCore.java`, add the import `org.craftcore.stellaria.commands.BroadcastCommand` and, right after the `/heal` registration line added in Task 5, add:

```java
        getCommand("broadcast").setExecutor(new BroadcastCommand(this));
```

- [ ] **Step 3: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification**

Run: `./gradlew runServer`, join as op, run `/broadcast hello world` and confirm it appears prefixed with `[お知らせ]` to all online players. Run `/bc &c red text` both as a user with `stellaria.broadcast.color` and without it (temporarily remove the permission), and confirm color codes are applied only in the first case (literal `&c red text` shows in the second). Run `/broadcast` with no args and confirm the usage message.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/commands/BroadcastCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add /broadcast command"
```

---

### Task 7: Auto-broadcast + `/stellariareload` integration

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/AutoBroadcastManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `ConfigManager.getBoolean/getInt/getStringList` (Task 1), `PlaceholderManager.resolve(String, Player)` (existing), `ColorUtil.component(String)` (existing).
- Produces: `AutoBroadcastManager(StellariaCore plugin)`, `void start()`, `void restart()` — `restart()` is consumed by `StellariaCore#reloadFeatureManagers()`.

- [ ] **Step 1: Create `AutoBroadcastManager`**

Create `src/main/java/org/craftcore/stellaria/managers/AutoBroadcastManager.java`:

```java
package org.craftcore.stellaria.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.List;

/**
 * config.yml の broadcast.auto.* に設定された複数メッセージを、一定間隔で順番に
 * 全プレイヤーへ送信する。各メッセージはプレイヤーごとに PlaceholderManager で解決してから
 * 送信するので、%ping% のような視聴者依存トークンも正しく出る。
 * /stellariareload で {@link #restart()} すると、インデックスをリセットして仕切り直す。
 * UtilsPlugin の AutoBroadcastManager を Folia対応スケジューラに置き換えて移植したもの。
 */
public class AutoBroadcastManager {

    private final StellariaCore plugin;
    private int currentIndex = 0;
    private ScheduledTask task;

    public AutoBroadcastManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 既存タスクがあれば止めてから、config.yml の設定に従って（有効なら）再登録する。 */
    public void start() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
            task = null;
        }

        if (!plugin.getConfigManager().getBoolean("broadcast.auto.enabled", false)) {
            return;
        }

        List<String> messages = plugin.getConfigManager().getStringList("broadcast.auto.messages");
        if (messages.isEmpty()) {
            plugin.getLogger().warning("broadcast.auto.messages が空のため、定期放送は開始しません。");
            return;
        }

        long intervalTicks = plugin.getConfigManager().getInt("broadcast.auto.interval-minutes", 5) * 60L * 20L;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> sendNext(), intervalTicks, intervalTicks);
    }

    /** /stellariareload から呼ばれる想定。インデックスを0に戻してから start() する。 */
    public void restart() {
        currentIndex = 0;
        start();
    }

    private void sendNext() {
        List<String> messages = plugin.getConfigManager().getStringList("broadcast.auto.messages");
        if (messages.isEmpty()) {
            return;
        }
        if (currentIndex >= messages.size()) {
            currentIndex = 0;
        }

        String rawMessage = messages.get(currentIndex);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.sendMessage(ColorUtil.component(plugin.getPlaceholderManager().resolve(rawMessage, viewer)));
        }
        currentIndex = (currentIndex + 1) % messages.size();
    }
}
```

- [ ] **Step 2: Wire `AutoBroadcastManager` into `StellariaCore`**

In `src/main/java/org/craftcore/stellaria/StellariaCore.java`, add the import `org.craftcore.stellaria.managers.AutoBroadcastManager` and the field, right after the `afkManager` field:

```java
    private AutoBroadcastManager autoBroadcastManager;
```

In `onEnable()`, right after the `/broadcast` command registration line added in Task 6, add:

```java
        this.autoBroadcastManager = new AutoBroadcastManager(this);
        autoBroadcastManager.start();
```

In `reloadFeatureManagers()`, add as the last line of the method body:

```java
        autoBroadcastManager.restart();
```

- [ ] **Step 3: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification**

Edit `run/plugins/StellariaCore/config.yml` before starting: set `broadcast.auto.enabled: true`, `broadcast.auto.interval-minutes: 1`, and `broadcast.auto.messages: ["&amessage one", "&bmessage two (%online% online)"]`. Run `./gradlew runServer`, join, and confirm the two messages alternate every minute, colors applied, `%online%` resolved to the real count. While the server is running, edit the messages list to add a third message, run `/stellariareload` as op, and confirm — after the next interval — the new message is included in the rotation (starting back from index 0).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/managers/AutoBroadcastManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add auto-broadcast with reload support"
```

---

### Task 8: End-to-end verification

**Files:** none (verification only).

**Interfaces:** none.

- [ ] **Step 1: Full build from clean**

Run: `./gradlew clean build`
Expected: `BUILD SUCCESSFUL`, and `build/libs/StellariaCore-<version>.jar` is produced and copied into `run/plugins/`.

- [ ] **Step 2: Cross-feature manual pass**

Run: `./gradlew runServer` with default `config.yml` values (revert any temporary test values from earlier tasks' manual checks back to the Task 1 defaults, or leave as-is if they were reasonable — just note in your PR description if defaults were changed). With two clients if possible:

- Confirm `/afk`, timeout-based AFK, and movement-clears-AFK all still work together (no regression from Task 4-7 changes).
- Confirm the tablist/belowname `%afk%` tag and `/heal`/`/broadcast` don't interfere with each other (e.g., broadcasting while AFK still shows the tag correctly afterward).
- Confirm `/stellariareload` doesn't throw any console errors and all three features keep working after it.

- [ ] **Step 3: Final commit (if any cleanup was needed)**

If Step 2 required any fixes, commit them individually with descriptive `fix:` messages before moving on. If nothing needed fixing, no commit is required for this task.
