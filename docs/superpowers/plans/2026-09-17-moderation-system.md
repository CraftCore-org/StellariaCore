# Moderation System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add staff warning, kick, ban, player report, and staff history commands with SQLite persistence and Discord audit notifications.

**Architecture:** `ModerationManager` owns immutable warn/kick/ban records and the active-ban cache; expired bans leave the cache but remain in the `bans` table so `/userhistory` can display the full moderation history. `ReportManager` owns report persistence and the pending report state, while `ReportCategoryGui` presents configured categories and `ReportListener` captures the follow-up chat message. Commands stay responsible for syntax, permission, player lookup, and user-facing messages.

**Tech Stack:** Java 21, Paper 1.21.11/Folia schedulers, SQLite through `DatabaseManager`, Adventure Components, JDA 6, JUnit 5, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-09-17-moderation-system-design.md`

## Global Constraints

- Use `ConfigManager` for every player-visible message; add player-visible strings only to `messages.yml` and behavior/settings only to `config.yml`.
- Access SQLite only through static `DatabaseManager` helpers; create the four new tables in `StellariaCore#onEnable`.
- `/warn` accepts a known offline target; `/kick` accepts an online target only; `/ban` accepts a known offline target.
- Reasons are mandatory for `/warn`, `/kick`, and `/ban`; duration must be `DurationParser`-compatible or `permanent`.
- A ban with `expires_at = NULL` is permanent. Expired temporary bans are removed from the active cache but retained in SQLite for `/userhistory`.
- `reports` are available to all players; `/userhistory` requires `stellaria.userhistory`; all staff commands use their individual `stellaria.*` permission.
- Discord failure or missing configuration must only log a warning and never reverse a successful moderation/report operation.
- Do not add unrelated features, migrations, refactors, or dependencies.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `managers/ModerationManager.java` | Persist warnings/kicks/bans, load and query active bans, expose aggregate/history queries. |
| `managers/ReportManager.java` | Hold report chat-input state and persist completed reports. |
| `commands/ModerationCommand.java` | Dispatch `/warn`, `/kick`, `/ban`, validate input, execute moderation actions, and tab-complete. |
| `commands/ReportCommand.java` | Validate `/report <player>` and open the category GUI. |
| `commands/UserHistoryCommand.java` | Render counts and recent mixed moderation/report history for staff. |
| `gui/ReportCategoryGui.java` | Display `report.categories` as selectable inventory buttons. |
| `listeners/BanLoginListener.java` | Reject currently banned players in `AsyncPlayerPreLoginEvent`. |
| `listeners/ReportListener.java` | Consume pending report chat and clear it when a reporter quits. |
| `managers/DiscordBotManager.java` | Send moderation and report embeds to their configured channel lists. |
| `StellariaCore.java` | Create tables, construct managers, register listeners/commands, and expose manager getters. |
| `config.yml`, `messages.yml`, `plugin.yml` | Supply report category/configuration, player messages, commands, and permissions. |
| `src/test/java/org/craftcore/stellaria/managers/ActiveBanRegistryTest.java` | Cover active-ban cache expiry without a running Paper server. |
| `src/test/java/org/craftcore/stellaria/managers/PendingReportRegistryTest.java` | Cover report pending-state consumption without a running Paper server. |

### Task 1: Establish test seams for active-ban expiry and report pending state

**Files:**
- Create: `src/test/java/org/craftcore/stellaria/managers/ActiveBanRegistryTest.java`
- Create: `src/test/java/org/craftcore/stellaria/managers/PendingReportRegistryTest.java`
- Create: `src/main/java/org/craftcore/stellaria/managers/ActiveBanRegistry.java`
- Create: `src/main/java/org/craftcore/stellaria/managers/PendingReportRegistry.java`

**Interfaces:**
- Produces: `ActiveBanRegistry#put(ActiveBanRegistry.BanEntry)`, `#getActive(UUID, long)`, `#remove(UUID)`, and `#clear()`.
- Produces: `PendingReportRegistry#put(UUID, PendingReportRegistry.PendingReport)`, `#take(UUID)`, and `#remove(UUID)`.
- Consumes: no Bukkit APIs; callers perform database persistence and Bukkit scheduling outside these focused state holders.

- [ ] **Step 1: Write the failing active-ban expiry tests**

```java
@Test
void returnsPermanentBanWithoutExpiry() {
    ActiveBanRegistry registry = new ActiveBanRegistry();
    UUID target = UUID.randomUUID();
    registry.put(new BanEntry(1, target, null, "reason", 100L, null));

    assertNotNull(registry.getActive(target, 200L));
}

@Test
void removesExpiredBanFromCacheButReturnsItForHistoryPersistence() {
    ActiveBanRegistry registry = new ActiveBanRegistry();
    UUID target = UUID.randomUUID();
    registry.put(new BanEntry(1, target, null, "reason", 100L, 150L));

    assertNull(registry.getActive(target, 150L));
    assertNull(registry.getActive(target, 200L));
}
```

- [ ] **Step 2: Run the active-ban test to verify it fails**

Run: `./gradlew test --tests org.craftcore.stellaria.managers.ActiveBanRegistryTest`

Expected: FAIL because `ActiveBanRegistry` and `BanEntry` do not exist.

- [ ] **Step 3: Write the minimal active-ban registry**

```java
public record BanEntry(int id, UUID targetUuid, UUID moderatorUuid, String reason,
                       long bannedAt, Long expiresAt) {
    public boolean isExpired(long now) {
        return expiresAt != null && now >= expiresAt;
    }
}

public BanEntry getActive(UUID targetUuid, long now) {
    BanEntry entry = entries.get(targetUuid);
    if (entry != null && entry.isExpired(now)) {
        entries.remove(targetUuid, entry);
        return null;
    }
    return entry;
}

public void clear() {
    entries.clear();
}
```

- [ ] **Step 4: Run the active-ban test to verify it passes**

Run: `./gradlew test --tests org.craftcore.stellaria.managers.ActiveBanRegistryTest`

Expected: PASS (2 tests, 0 failures).

- [ ] **Step 5: Write the failing pending-report state tests**

```java
@Test
void takeReturnsAndClearsTheReporterPendingState() {
    PendingReportRegistry registry = new PendingReportRegistry();
    UUID reporter = UUID.randomUUID();
    PendingReport pending = new PendingReport(UUID.randomUUID(), "チート", "world", 1, 64, 2);
    registry.put(reporter, pending);

    assertEquals(pending, registry.take(reporter));
    assertNull(registry.take(reporter));
}
```

- [ ] **Step 6: Run the pending-report test to verify it fails**

Run: `./gradlew test --tests org.craftcore.stellaria.managers.PendingReportRegistryTest`

Expected: FAIL because `PendingReportRegistry` and `PendingReport` do not exist.

- [ ] **Step 7: Write the minimal pending-report registry**

```java
public record PendingReport(UUID targetUuid, String category, String world, int x, int y, int z) { }

private final Map<UUID, PendingReport> pending = new ConcurrentHashMap<>();

public PendingReport take(UUID reporterUuid) {
    return pending.remove(reporterUuid);
}
```

- [ ] **Step 8: Run the pending-report test and full test suite**

Run: `./gradlew test --tests org.craftcore.stellaria.managers.PendingReportRegistryTest && ./gradlew test`

Expected: PASS with the new tests and all pre-existing tests green.

- [ ] **Step 9: Commit the focused state holders and their tests**

```bash
git add src/main/java/org/craftcore/stellaria/managers/ActiveBanRegistry.java src/main/java/org/craftcore/stellaria/managers/PendingReportRegistry.java src/test/java/org/craftcore/stellaria/managers/ActiveBanRegistryTest.java src/test/java/org/craftcore/stellaria/managers/PendingReportRegistryTest.java
git commit -m "test: cover moderation state registries"
```

### Task 2: Add moderation and report persistence managers

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/ModerationManager.java`
- Create: `src/main/java/org/craftcore/stellaria/managers/ReportManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `ActiveBanRegistry`, `PendingReportRegistry`, and static `DatabaseManager` query/insert/execute helpers.
- Produces: `ModerationManager#loadAllBans()`, `#warn(UUID, UUID, String)`, `#recordKick(UUID, UUID, String)`, `#ban(UUID, UUID, String, Long)`, `#getActiveBan(UUID)`, `#getHistory(UUID, int)`, and `#getCounts(UUID)`.
- Produces: `ReportManager#beginReport(Player, UUID, String)`, `#takePending(UUID)`, `#restorePending(UUID, PendingReportRegistry.PendingReport)`, `#completeReport(Player, PendingReportRegistry.PendingReport, String)`, and `#clearPending(UUID)`.

- [ ] **Step 1: Add the four schema definitions in `onEnable` immediately after `mutes`**

```java
DatabaseManager.createTableIfNotExists("warns",
    "id INTEGER PRIMARY KEY AUTOINCREMENT", "target_uuid TEXT", "moderator_uuid TEXT",
    "reason TEXT", "created_at INTEGER");
DatabaseManager.createTableIfNotExists("kicks",
    "id INTEGER PRIMARY KEY AUTOINCREMENT", "target_uuid TEXT", "moderator_uuid TEXT",
    "reason TEXT", "created_at INTEGER");
DatabaseManager.createTableIfNotExists("bans",
    "id INTEGER PRIMARY KEY AUTOINCREMENT", "target_uuid TEXT", "moderator_uuid TEXT",
    "reason TEXT", "banned_at INTEGER", "expires_at INTEGER NULL");
DatabaseManager.createTableIfNotExists("reports",
    "id INTEGER PRIMARY KEY AUTOINCREMENT", "reporter_uuid TEXT", "target_uuid TEXT",
    "category TEXT", "reason TEXT", "world TEXT", "x INTEGER", "y INTEGER", "z INTEGER", "created_at INTEGER");
```

- [ ] **Step 2: Implement `ModerationManager` with a complete active-ban load**

```java
public void loadAllBans() {
    activeBans.clear();
    for (ActiveBanRegistry.BanEntry entry : DatabaseManager.query(
            "SELECT id, target_uuid, moderator_uuid, reason, banned_at, expires_at FROM bans ORDER BY banned_at ASC",
            rs -> new ActiveBanRegistry.BanEntry(rs.getInt("id"), UUID.fromString(rs.getString("target_uuid")),
                    nullableUuid(rs.getString("moderator_uuid")), rs.getString("reason"), rs.getLong("banned_at"),
                    rs.getObject("expires_at") == null ? null : rs.getLong("expires_at")))) {
        if (!entry.isExpired(System.currentTimeMillis())) activeBans.put(entry);
    }
}
```

- [ ] **Step 3: Preserve history when checking expiration**

```java
public ActiveBanRegistry.BanEntry getActiveBan(UUID targetUuid) {
    return activeBans.getActive(targetUuid, System.currentTimeMillis());
}
```

The expiry path must only remove the cached entry; it must not issue `DELETE FROM bans`.

- [ ] **Step 4: Persist warning, kick, ban, report, counts, and the recent mixed history query**

```java
DatabaseManager.execute(
    "INSERT INTO warns (target_uuid, moderator_uuid, reason, created_at) VALUES (?, ?, ?, ?)",
    targetUuid.toString(), nullableUuidString(moderatorUuid), reason, System.currentTimeMillis());

DatabaseManager.execute(
    "INSERT INTO reports (reporter_uuid, target_uuid, category, reason, world, x, y, z, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    reporter.getUniqueId().toString(), pending.targetUuid().toString(), pending.category(), reason,
    pending.world(), pending.x(), pending.y(), pending.z(), System.currentTimeMillis());
```

`getHistory` must use a `UNION ALL` of warnings, kicks, bans, and reports with columns `type`, `actor_uuid`, `reason`, and `occurred_at`, ordered by `occurred_at DESC LIMIT ?`. Reports use `reporter_uuid` as `actor_uuid` and format the reason as `category + ": " + reason`.

- [ ] **Step 5: Construct managers in startup order and load bans before listeners run**

```java
this.moderationManager = new ModerationManager(this);
moderationManager.loadAllBans();
this.reportManager = new ReportManager(this);
```

Add the matching accessors alongside the existing manager getters:

```java
public ModerationManager getModerationManager() {
    return moderationManager;
}

public ReportManager getReportManager() {
    return reportManager;
}
```

- [ ] **Step 6: Run the complete unit test suite and compile**

Run: `./gradlew test && ./gradlew compileJava`

Expected: PASS; no compilation errors.

- [ ] **Step 7: Commit persistence and manager wiring**

```bash
git add src/main/java/org/craftcore/stellaria/managers/ModerationManager.java src/main/java/org/craftcore/stellaria/managers/ReportManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add moderation persistence managers"
```

### Task 3: Add Discord moderation and report audit delivery

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/DiscordBotManager.java`
- Modify: `src/main/resources/config.yml`

**Interfaces:**
- Consumes: JDA `EmbedBuilder`, `jda`, `hasConfiguredGuild()`, and configured string lists.
- Produces: `DiscordBotManager#sendModerationLog(EmbedBuilder)` and `#sendReportLog(EmbedBuilder)`.
- Called by: moderation/report command flows after successful persistence.

- [ ] **Step 1: Add empty-safe channel list defaults**

```yaml
discord:
  bot:
    modlog-channel-id: []
    report-channel-id: []
```

- [ ] **Step 2: Add a reusable guarded embed sender and the two public methods**

```java
public void sendModerationLog(EmbedBuilder embed) {
    sendEmbedToChannels(embed, "discord.bot.modlog-channel-id", "モデレーションログ");
}

public void sendReportLog(EmbedBuilder embed) {
    sendEmbedToChannels(embed, "discord.bot.report-channel-id", "報告ログ");
}
```

`sendEmbedToChannels` must return after logging a warning when JDA is unavailable, and must catch an invalid channel ID or failed queue callback to log a warning without throwing to its caller.

- [ ] **Step 3: Call the correct method after each successful database operation**

```java
plugin.getDiscordBotManager().sendModerationLog(
    new EmbedBuilder().setTitle("WARN").addField("実行者", moderatorName, true)
        .addField("対象", targetName, true).addField("理由", reason, false));
```

Use `sendModerationLog` for warn/kick/ban and `sendReportLog` for completed reports; preserve the existing server-chat embed behavior.

- [ ] **Step 4: Compile and run all tests**

Run: `./gradlew test && ./gradlew compileJava`

Expected: PASS.

- [ ] **Step 5: Commit Discord audit delivery**

```bash
git add src/main/java/org/craftcore/stellaria/managers/DiscordBotManager.java src/main/resources/config.yml src/main/java/org/craftcore/stellaria/managers/ModerationManager.java src/main/java/org/craftcore/stellaria/managers/ReportManager.java
git commit -m "feat: send moderation Discord audit logs"
```

### Task 4: Implement `/warn`, `/kick`, and `/ban`

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/ModerationCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: `ModerationManager`, `DurationParser`, `TabCompleteUtil`, `DiscordBotManager`, and `ConfigManager`.
- Produces: one executor/tab completer for `/warn`, `/kick`, and `/ban`.
- Requires: `stellaria.warn`, `stellaria.kick`, and `stellaria.ban` permissions.

- [ ] **Step 1: Add the commands and permissions**

```yaml
commands:
  warn:
    permission: stellaria.warn
  kick:
    permission: stellaria.kick
  ban:
    permission: stellaria.ban

permissions:
  stellaria.admin:
    children:
      stellaria.warn: true
      stellaria.kick: true
      stellaria.ban: true
  stellaria.warn:
    default: false
  stellaria.kick:
    default: false
  stellaria.ban:
    default: false
```

- [ ] **Step 2: Add config-driven usage, failure, and success messages**

```yaml
moderation:
  warn_usage:
    - "&%c使用方法: /warn <プレイヤー> <理由>"
    - "&%7理由を省略せずに入力してください。"
  kick_usage:
    - "&%c使用方法: /kick <プレイヤー> <理由>"
    - "&%7対象プレイヤーはオンラインである必要があります。"
  ban_usage:
    - "&%c使用方法: /ban <プレイヤー> <期間|permanent> <理由>"
    - "&%7期間の例: 10m, 1h, 3d, permanent"
```

Add separate `no_permission`, `player_not_found`, `player_not_online`, `warned_sender`, `warned_target_actionbar`, `kicked_sender`, `kick_screen`, `banned_sender`, and `ban_screen` message keys. Both disconnect messages must accept `%reason%`, `%expires%`, and `%discord_invite%` as applicable.

- [ ] **Step 3: Implement command parsing and target lookup**

```java
private OfflinePlayer knownTarget(CommandSender sender, String name) {
    OfflinePlayer target = Bukkit.getOfflinePlayer(name);
    if (!target.hasPlayedBefore() && !target.isOnline()) {
        sender.sendMessage(plugin.getConfigManager().getMessage("moderation.player_not_found", target));
        return null;
    }
    return target;
}
```

`/warn` requires at least two arguments and accepts `knownTarget`. `/kick` requires at least two arguments and uses `Bukkit.getPlayerExact`; `/ban` requires at least three arguments, accepts `knownTarget`, parses `args[1]` with `DurationParser.parseSeconds`, and joins `args[2..]` as the reason.

- [ ] **Step 4: Execute actions and issue the correct notices**

```java
String screen = plugin.getConfigManager().getMessage("moderation.ban_screen", target)
    .replace("%reason%", reason)
    .replace("%expires%", expiresText)
    .replace("%discord_invite%", plugin.getConfigManager().getString("discord.invite", ""));
target.getPlayer().kick(ColorUtil.component(screen));
```

`/warn` records first, flashes the online target through `ActionBarManager#flash` on channel `moderation_warn`, then confirms to the sender. `/kick` records first and disconnects the online target. `/ban` records and caches first, then disconnects an online target. The moderator UUID is `null` for console execution; moderator display name is `CONSOLE` for messages and embeds.

- [ ] **Step 5: Add tab completion and startup registration**

```java
ModerationCommand moderationCommand = new ModerationCommand(this);
for (String name : new String[]{"warn", "kick", "ban"}) {
    getCommand(name).setExecutor(moderationCommand);
    getCommand(name).setTabCompleter(moderationCommand);
}
```

Use known-player names for warn/ban, online names for kick, and `10m`, `1h`, `3d`, `permanent` for the second ban argument.

- [ ] **Step 6: Compile and run tests**

Run: `./gradlew test && ./gradlew compileJava`

Expected: PASS.

- [ ] **Step 7: Commit staff punishment commands**

```bash
git add src/main/java/org/craftcore/stellaria/commands/ModerationCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/resources/messages.yml src/main/resources/plugin.yml
git commit -m "feat: add warn kick and ban commands"
```

### Task 5: Block banned players during asynchronous pre-login

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/listeners/BanLoginListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/resources/messages.yml`

**Interfaces:**
- Consumes: `ModerationManager#getActiveBan(UUID)`, `DurationParser#formatRemaining(long)`, `ColorUtil#component`, and `ConfigManager`.
- Produces: an `AsyncPlayerPreLoginEvent` listener that disallows only active bans.

- [ ] **Step 1: Add an explicit pre-login denial message**

```yaml
moderation:
  ban_login_screen: "&%cあなたはBANされています。\n&%7理由: &%f%reason%\n&%7期限: &%f%expires%\n&%7異議申し立て: &%b%discord_invite%"
```

- [ ] **Step 2: Implement the listener using no Bukkit world/entity API**

```java
@EventHandler
public void onPreLogin(AsyncPlayerPreLoginEvent event) {
    ActiveBanRegistry.BanEntry ban = plugin.getModerationManager().getActiveBan(event.getUniqueId());
    if (ban == null) return;
    String expires = ban.expiresAt() == null ? "無期限" : DurationParser.formatRemaining(ban.expiresAt());
    String message = plugin.getConfigManager().getMessage("moderation.ban_login_screen", null)
        .replace("%reason%", ban.reason()).replace("%expires%", expires)
        .replace("%discord_invite%", plugin.getConfigManager().getString("discord.invite", ""));
    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, ColorUtil.component(message));
}
```

- [ ] **Step 3: Register the listener after moderation-manager construction**

```java
getServer().getPluginManager().registerEvents(new BanLoginListener(this), this);
```

- [ ] **Step 4: Run tests and compile**

Run: `./gradlew test && ./gradlew compileJava`

Expected: PASS.

- [ ] **Step 5: Commit login enforcement**

```bash
git add src/main/java/org/craftcore/stellaria/listeners/BanLoginListener.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/resources/messages.yml
git commit -m "feat: block active bans at login"
```

### Task 6: Implement report category selection and chat completion

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/ReportCommand.java`
- Create: `src/main/java/org/craftcore/stellaria/gui/ReportCategoryGui.java`
- Create: `src/main/java/org/craftcore/stellaria/listeners/ReportListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/ChatListener.java`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: `ReportManager`, `Gui`, `ConfigManager#getStringList`, and `TabCompleteUtil#knownPlayerNames`.
- Produces: `/report <player>`, a category GUI, and a low-priority chat listener that consumes only pending report descriptions.
- Requires: no permission node for `/report`.

- [ ] **Step 1: Define categories and report messages**

```yaml
# config.yml
report:
  categories: ["暴言", "チート", "荒らし", "その他"]
```

```yaml
# messages.yml
report:
  usage:
    - "&%e① &%f/report <プレイヤー> で対象を指定"
    - "&%e② &%f理由カテゴリを選択"
    - "&%e③ &%fチャットで詳細を入力"
    - "&%e④ &%f送信完了"
  player_not_found: "&%c対象が見つかりません。オンライン、または過去にログインしたプレイヤー名を指定してください。"
  gui_title: "&%9&l報告カテゴリを選択"
  detail_prompt: "&%e報告の詳細をチャットに入力してください。"
  detail_required: "&%c詳細を空欄にはできません。もう一度入力してください。"
  submitted: "&%a報告を受け付けました。ご協力ありがとうございます。"
```

- [ ] **Step 2: Add `/report` to `plugin.yml` and implement its validation**

```java
if (!(sender instanceof Player player)) {
    sender.sendMessage(plugin.getConfigManager().getMessage("report.must_be_player", null));
    return true;
}
if (args.length != 1) {
    sendLines(player, "report.usage");
    return true;
}
OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
if (!target.hasPlayedBefore() && !target.isOnline()) {
    player.sendMessage(plugin.getConfigManager().getMessage("report.player_not_found", player));
    return true;
}
new ReportCategoryGui(plugin, player, target.getUniqueId()).open(player);
```

`sendLines` must resolve each raw `report.usage` line through `FormatUtil.text(player, line)` before sending it, matching the required `ConfigManager.getMessageList` source.

- [ ] **Step 3: Implement a 27-slot category GUI with one PAPER button per configured category**

```java
public void onClick(InventoryClickEvent event) {
    event.setCancelled(true);
    String category = categoriesBySlot.get(event.getRawSlot());
    if (category == null) return;
    Player reporter = (Player) event.getWhoClicked();
    plugin.getReportManager().beginReport(reporter, targetUuid, category);
    reporter.closeInventory();
    reporter.sendMessage(plugin.getConfigManager().getMessage("report.detail_prompt", reporter));
}
```

Populate only the first 27 configured categories; use a `PAPER` item with the colorized category title. Log a warning and display no button if the category list is empty.

- [ ] **Step 4: Implement report chat capture and safe completion**

```java
@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
public void onChat(AsyncChatEvent event) {
    PendingReportRegistry.PendingReport pending = plugin.getReportManager().takePending(event.getPlayer().getUniqueId());
    if (pending == null) return;
    event.setCancelled(true);
    String reason = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
    if (reason.isEmpty()) {
        plugin.getReportManager().restorePending(event.getPlayer().getUniqueId(), pending);
        event.getPlayer().getScheduler().run(plugin, task -> event.getPlayer().sendMessage(
            plugin.getConfigManager().getMessage("report.detail_required", event.getPlayer())), null);
        return;
    }
    plugin.getReportManager().completeReport(event.getPlayer(), pending, reason);
}
```

Capture the reporter location synchronously in `ReportManager#beginReport`, never from the asynchronous chat event. Run any player message on the reporting player’s entity scheduler. `ReportListener#onQuit` must call `ReportManager#clearPending`.

- [ ] **Step 5: Make normal chat formatting skip consumed report descriptions**

```java
@EventHandler
public void onChat(AsyncChatEvent event) {
    if (event.isCancelled()) return;
    // existing mute, Discord bridge, and formatting flow
}
```

This narrow guard prevents `ChatListener` from broadcasting or formatting a report description after `ReportListener` consumes it.

- [ ] **Step 6: Register the command and listener**

```java
ReportCommand reportCommand = new ReportCommand(this);
getCommand("report").setExecutor(reportCommand);
getCommand("report").setTabCompleter(reportCommand);
getServer().getPluginManager().registerEvents(new ReportListener(this), this);
```

- [ ] **Step 7: Run tests and compile**

Run: `./gradlew test && ./gradlew compileJava`

Expected: PASS.

- [ ] **Step 8: Commit the report flow**

```bash
git add src/main/java/org/craftcore/stellaria/commands/ReportCommand.java src/main/java/org/craftcore/stellaria/gui/ReportCategoryGui.java src/main/java/org/craftcore/stellaria/listeners/ReportListener.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/listeners/ChatListener.java src/main/resources/config.yml src/main/resources/messages.yml src/main/resources/plugin.yml
git commit -m "feat: add player report flow"
```

### Task 7: Implement staff user-history output

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/UserHistoryCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: `ModerationManager#getCounts(UUID)`, `#getHistory(UUID, int)`, `TabCompleteUtil#knownPlayerNames`, `ConfigManager`, and `DurationParser#formatDuration` for relative timestamps.
- Produces: `/userhistory <player>` restricted to `stellaria.userhistory`.

- [ ] **Step 1: Register the command and permission under `stellaria.admin`**

```yaml
commands:
  userhistory:
    permission: stellaria.userhistory
permissions:
  stellaria.admin:
    children:
      stellaria.userhistory: true
  stellaria.userhistory:
    default: false
```

- [ ] **Step 2: Add history message templates**

```yaml
userhistory:
  usage: "&%c使用方法: /userhistory <プレイヤー>"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  player_not_found: "&%c%player% &%7の記録が見つかりません。"
  header: "&%9&l--- %player% のモデレーション履歴 ---"
  counts: "&%7警告: &%f%warns% &%7Kick: &%f%kicks% &%7BAN: &%f%bans% &%7被報告: &%f%reports%"
  entry: "&%8[%time%前] &%f%type% &%7実行者: %actor% &%7理由: %reason%"
  empty: "&%7直近の履歴はありません。"
```

- [ ] **Step 3: Implement the command with known-player lookup and a fixed recent-entry limit**

```java
if (!sender.hasPermission("stellaria.userhistory")) {
    sender.sendMessage(plugin.getConfigManager().getMessage("userhistory.no_permission", null));
    return true;
}
if (args.length != 1) {
    sender.sendMessage(plugin.getConfigManager().getUsageMessage("userhistory.usage", null));
    return true;
}
```

Use `Bukkit.getOfflinePlayer(args[0])`; reject a player that is neither online nor has played before. Query exactly 10 mixed entries, convert actor UUIDs to the known player name when available, use `CONSOLE` for a null moderator UUID, and send the configured header/count/entry messages in descending time order.

- [ ] **Step 4: Register executor and tab completer**

```java
UserHistoryCommand userHistoryCommand = new UserHistoryCommand(this);
getCommand("userhistory").setExecutor(userHistoryCommand);
getCommand("userhistory").setTabCompleter(userHistoryCommand);
```

- [ ] **Step 5: Run tests and compile**

Run: `./gradlew test && ./gradlew compileJava`

Expected: PASS.

- [ ] **Step 6: Commit user-history support**

```bash
git add src/main/java/org/craftcore/stellaria/commands/UserHistoryCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/resources/messages.yml src/main/resources/plugin.yml
git commit -m "feat: add moderation user history"
```

### Task 8: Build, perform the specified manual checks, and review the changes

**Files:**
- Modify only if a verification failure identifies a required correction in a file from Tasks 1-7.

**Interfaces:**
- Consumes: built plugin jar copied to `run/plugins/`, the registered Paper server commands, SQLite data, and optional configured Discord bot channels.
- Produces: verified moderation behavior and a clean code-review request.

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`

Expected: `BUILD SUCCESSFUL` and `build/libs/StellariaCore-<version>.jar` copied to `run/plugins/`.

- [ ] **Step 2: Start the local Paper server**

Run: `./gradlew runServer`

Expected: Paper starts with StellariaCore enabled and logs the loaded active-ban count.

- [ ] **Step 3: Verify warn and history manually**

Run in-game as a staff member: `/warn <known-offline-or-online-player> test warning`, then `/userhistory <player>`.

Expected: warning succeeds; online target sees an action-bar notice; history count and one recent WARN entry increase.

- [ ] **Step 4: Verify kick and ban manually**

Run in-game as a staff member: `/kick <online-player> test kick`; then `/ban <online-player> 1s test temporary ban`; repeat with `/ban <known-player> permanent test permanent ban`.

Expected: kick and ban disconnect screens include reason and Discord invite; the temporary and permanent bans reject an immediate reconnect; after the one-second ban expires, reconnect is allowed and `/userhistory` still shows the temporary BAN record.

- [ ] **Step 5: Verify report flow manually**

Run in-game: `/report` and `/report nonexistent`, then `/report <known-player>`, select a category, and type a description in chat.

Expected: missing target shows the four-step help; unknown target gives the concrete known-player guidance; selected report consumes the chat message, stores location/category/reason, confirms submission, and appears as a report count/history entry for the target.

- [ ] **Step 6: Verify Discord delivery when bot and channels are configured**

Run one warn, kick, ban, and completed report against test players.

Expected: each moderation action appears in every `discord.bot.modlog-channel-id` channel and the report appears in every `discord.bot.report-channel-id` channel. With bot disabled or an empty/invalid list, operations still succeed and only a server warning is logged.

- [ ] **Step 7: Re-run the build after manual verification**

Run: `./gradlew build`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Request code review with verification evidence**

Use `superpowers:requesting-code-review` and provide the final commit range, `./gradlew build` output, manual command results, and any Discord test availability limitation.

- [ ] **Step 9: Commit verification fixes only when necessary**

```bash
git add src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/commands/ModerationCommand.java src/main/java/org/craftcore/stellaria/commands/ReportCommand.java src/main/java/org/craftcore/stellaria/commands/UserHistoryCommand.java src/main/java/org/craftcore/stellaria/gui/ReportCategoryGui.java src/main/java/org/craftcore/stellaria/listeners/BanLoginListener.java src/main/java/org/craftcore/stellaria/listeners/ChatListener.java src/main/java/org/craftcore/stellaria/listeners/ReportListener.java src/main/java/org/craftcore/stellaria/managers/ActiveBanRegistry.java src/main/java/org/craftcore/stellaria/managers/DiscordBotManager.java src/main/java/org/craftcore/stellaria/managers/ModerationManager.java src/main/java/org/craftcore/stellaria/managers/PendingReportRegistry.java src/main/java/org/craftcore/stellaria/managers/ReportManager.java src/main/resources/config.yml src/main/resources/messages.yml src/main/resources/plugin.yml src/test/java/org/craftcore/stellaria/managers/ActiveBanRegistryTest.java src/test/java/org/craftcore/stellaria/managers/PendingReportRegistryTest.java
git commit -m "fix: address moderation verification findings"
```

## Plan Self-Review

- Spec coverage: Tasks 2-5 cover persistence, cache loading, expiry, warn/kick/ban, kick and login messages, permissions, and Discord moderation logs. Task 6 covers category GUI, chat capture, report persistence, report Discord logs, detailed usage, and cleanup. Task 7 covers staff counts and recent mixed history. Task 8 covers every requested manual verification case.
- Conflict resolution: the user explicitly chose to retain expired BAN rows for history, so cache expiry intentionally performs no database deletion.
- Existing test correction: the design document says there is no `src/test`, but the repository currently has JUnit tests and Gradle test support. Task 1 adds narrowly testable state-holder tests; Paper behavior remains manually verified in Task 8.
- Placeholder scan: no unresolved implementation or requirement placeholders remain.
- Type consistency: `ActiveBanRegistry.BanEntry` and `PendingReportRegistry.PendingReport` are the shared types used consistently by their manager and listener consumers.
