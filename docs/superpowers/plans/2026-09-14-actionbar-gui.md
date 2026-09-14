# 基盤マネージャー（アクションバー同時表示 + GUIフレームワーク）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 複数の機能が同時にアクションバー表示を要求しても1行に連結して共存表示する`ActionBarManager`を実装し、TPAカウントダウン・AFK個人通知と連携させる。あわせてインベントリGUIを作るための汎用フレームワーク（`Gui`/`GuiListener`）を用意する。

**Architecture:** `ActionBarManager`はプレイヤーごとに「チャンネルID→表示内容」の`LinkedHashMap`（挿入順=表示順）を保持し、`tick()`のたびに期限切れチャンネルを削除してから区切り文字で連結して`sendActionBar`する。`TpaCore`のテレポート待機と`AfkManager`のAFK切り替えはこのAPIを呼ぶだけの薄い連携にする。GUIは`InventoryHolder`を実装した抽象クラス`Gui`と、それを1つのリスナーで一括ディスパッチする`GuiListener`のみを用意し、具体的な画面は作らない。

**Tech Stack:** PaperMC 1.21 / Java 21 / Adventure Component API / Folia対応スケジューラ（`EntityScheduler#runAtFixedRate`含む）

**Spec:** `docs/superpowers/specs/2026-09-14-actionbar-gui-design.md`

## Global Constraints

- Folia安全性: グローバルなtickは`Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)`、プレイヤー個別のタイマー（TPAカウントダウン）は`player.getScheduler().runAtFixedRate(...)`を使う（`BukkitRunnable`系は禁止）。
- 全てのユーザー向け文言は`messages.yml`経由。`action-bar.enabled`が`false`の間は`ActionBarManager`のAPI呼び出しは黙って何もしない（NPEにしない）。
- `action-bar.persistent`のデフォルトは無効（既存サーバーの見た目を勝手に変えない）。
- GUIフレームワークはこのラウンドでは骨組みのみ。具体的な`Gui`サブクラス・コマンドは作らない。
- このリポジトリに自動テストは無い（`src/test`無し）。各タスクの検証は`./gradlew build`の成功 + 該当箇所の`./gradlew runServer`手動確認で行う。

---

## Task 1: ActionBarManager 基盤 + config/messages整備

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/ActionBarManager.java`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java`

**Interfaces:**
- Produces:
  - `ActionBarManager.setChannel(Player, String channelId, Component content): void`（無期限チャンネル）
  - `ActionBarManager.flash(Player, String channelId, Component content, long durationTicks): void`（自動消滅チャンネル）
  - `ActionBarManager.clearChannel(Player, String channelId): void`
  - `ActionBarManager.removePlayer(UUID): void`
  - `ActionBarManager.tick(): void`
  - `StellariaCore.getActionBarManager(): ActionBarManager`（後続タスクが使用）
  - config/messagesの全キー（後続タスクが依存）

- [ ] **Step 1: config.yml に action-bar セクションを追加し、既存 afk: に1キー追記**

`src/main/resources/config.yml` の末尾（既存の`message:`セクションの後）に追記:

```yaml

action-bar:
  enabled: true
  update-interval-ticks: 5
  separator: "&%7 | &r"
  persistent:
    enabled: false
    template: ""
```

既存の`afk:`セクション:

```yaml
afk:
  enabled: true
  timeout-seconds: 300
  tag: "&%7[AFK] &r"
```

これに`actionbar-flash-seconds`を追記:

```yaml
afk:
  enabled: true
  timeout-seconds: 300
  tag: "&%7[AFK] &r"
  actionbar-flash-seconds: 3
```

- [ ] **Step 2: messages.yml に became_actionbar/returned_actionbar/tpa_warmup_actionbar を追記**

既存の`afk:`セクション:

```yaml
afk:
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  became: "&%e%player% &%7がAFK（離席中）になりました。"
  returned: "&%e%player% &%7がAFKから復帰しました。"
```

これを次のように変更:

```yaml
afk:
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  became: "&%e%player% &%7がAFK（離席中）になりました。"
  returned: "&%e%player% &%7がAFKから復帰しました。"
  became_actionbar: "&e離席中です"
  returned_actionbar: "&aお帰りなさい"
```

既存の`tpa:`セクション内、テレポート詠唱まわり:

```yaml
  # 承認後のテレポート詠唱（tpa/tphere共通）
  tpa_warmup: "&%e&l| &%7%seconds%秒後にテレポートします。ダメージを受けるとキャンセルされます。"
  tpa_warmup_cancelled: "&%c&l| &%7ダメージを受けたため、テレポートをキャンセルしました。"
  tpa_warmup_target_offline: "&%c&l| &%7相手がオフラインになったため、テレポートをキャンセルしました。"
```

これを次のように変更（`tpa_warmup_actionbar`を追加）:

```yaml
  # 承認後のテレポート詠唱（tpa/tphere共通）
  tpa_warmup: "&%e&l| &%7%seconds%秒後にテレポートします。ダメージを受けるとキャンセルされます。"
  tpa_warmup_actionbar: "&e%seconds%秒後にテレポートします"
  tpa_warmup_cancelled: "&%c&l| &%7ダメージを受けたため、テレポートをキャンセルしました。"
  tpa_warmup_target_offline: "&%c&l| &%7相手がオフラインになったため、テレポートをキャンセルしました。"
```

- [ ] **Step 3: ActionBarManager を作成**

`src/main/java/org/craftcore/stellaria/managers/ActionBarManager.java`:

```java
package org.craftcore.stellaria.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 複数の機能が同時にアクションバー表示を要求しても、1行に連結して共存表示するためのマネージャー。
 * プレイヤーごとに「チャンネルID -> 表示内容」を保持し（{@link LinkedHashMap} なので挿入順=表示順、
 * 既存キーの更新は順序を変えない）、{@link #tick()} のたびに期限切れチャンネルを削除してから
 * {@code action-bar.separator} で連結して送信する。
 */
public class ActionBarManager {

    private record ChannelEntry(Component content, long expiresAtMillis) {
        boolean isExpired(long now) {
            return expiresAtMillis >= 0 && now >= expiresAtMillis;
        }
    }

    private final StellariaCore plugin;
    private final Map<UUID, LinkedHashMap<String, ChannelEntry>> channels = new HashMap<>();

    public ActionBarManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 無期限で表示し続けるチャンネルを設定/更新する（常設ステータス・TPAカウントダウンなど）。 */
    public void setChannel(Player player, String channelId, Component content) {
        if (!plugin.getConfigManager().getBoolean("action-bar.enabled", true)) {
            return;
        }
        channelsFor(player).put(channelId, new ChannelEntry(content, -1));
    }

    /** durationTicks 後に自動的に消えるチャンネルを設定する（AFK通知などの一時フラッシュ）。 */
    public void flash(Player player, String channelId, Component content, long durationTicks) {
        if (!plugin.getConfigManager().getBoolean("action-bar.enabled", true)) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + (durationTicks * 50L); // 1 tick = 50ms
        channelsFor(player).put(channelId, new ChannelEntry(content, expiresAt));
    }

    /** チャンネルを即座に消す（TPAキャンセル時など）。 */
    public void clearChannel(Player player, String channelId) {
        LinkedHashMap<String, ChannelEntry> playerChannels = channels.get(player.getUniqueId());
        if (playerChannels != null) {
            playerChannels.remove(channelId);
        }
    }

    /** プレイヤー退出時に呼ぶ。保持しているチャンネル情報を全て破棄する（メモリリーク防止）。 */
    public void removePlayer(UUID uuid) {
        channels.remove(uuid);
    }

    /** action-bar.update-interval-ticks ごとにグローバルリージョンスケジューラから呼ばれる想定。 */
    public void tick() {
        long now = System.currentTimeMillis();
        String separatorTemplate = plugin.getConfigManager().getString("action-bar.separator", " | ");
        Component separator = ColorUtil.component(separatorTemplate);

        for (Player player : Bukkit.getOnlinePlayers()) {
            LinkedHashMap<String, ChannelEntry> playerChannels = channels.get(player.getUniqueId());
            if (playerChannels == null || playerChannels.isEmpty()) {
                continue;
            }
            playerChannels.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
            if (playerChannels.isEmpty()) {
                continue;
            }
            player.sendActionBar(join(playerChannels.values(), separator));
        }
    }

    private LinkedHashMap<String, ChannelEntry> channelsFor(Player player) {
        return channels.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>());
    }

    private Component join(Iterable<ChannelEntry> entries, Component separator) {
        Component result = null;
        for (ChannelEntry entry : entries) {
            result = result == null ? entry.content() : result.append(separator).append(entry.content());
        }
        return result != null ? result : Component.empty();
    }
}
```

- [ ] **Step 4: StellariaCore に配線**

import群に追加:

```java
import org.craftcore.stellaria.managers.ActionBarManager;
```

フィールド宣言（`private PrivateMessageManager privateMessageManager;` の直後）に追加:

```java
    private ActionBarManager actionBarManager;
```

`onEnable` 内、`this.privateMessageManager = new PrivateMessageManager(this);` の直後に追加:

```java
        this.actionBarManager = new ActionBarManager(this);
        if (configManager.getBoolean("action-bar.enabled", true)) {
            long actionBarInterval = configManager.getInt("action-bar.update-interval-ticks", 5);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> actionBarManager.tick(), actionBarInterval, actionBarInterval);
        }
```

ゲッター群（`getPrivateMessageManager()` の直後）に追加:

```java
    public ActionBarManager getActionBarManager() {
        return this.actionBarManager;
    }
```

- [ ] **Step 5: PlayerListener の退出処理にクリーンアップを追加**

`src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java` の既存の`onPlayerLeave`:

```java
    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event){
        TpaCore.resetPlayerTeleportRequests(event.getPlayer());
        plugin.getAfkManager().removePlayer(event.getPlayer().getUniqueId());
    }
```

これを次のように変更:

```java
    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event){
        TpaCore.resetPlayerTeleportRequests(event.getPlayer());
        plugin.getAfkManager().removePlayer(event.getPlayer().getUniqueId());
        plugin.getActionBarManager().removePlayer(event.getPlayer().getUniqueId());
    }
```

- [ ] **Step 6: ビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 起動確認 + 常設ステータス表示の手動確認**

`./gradlew runServer` を起動しコンソールに例外が出ないことを確認する。続けて`config.yml`の
`action-bar.persistent.enabled`を`true`・`action-bar.persistent.template`に適当な文字列
（例: `"&aテスト表示"`）を設定して`/stellariareload` →

現時点では`persistent`チャンネルを実際に`setChannel`する呼び出し元がまだ無いため
（Task 2で追加する）、この時点ではアクションバーには何も出ない。ここでは
「例外が出ずに起動・reloadできること」までを確認すれば十分（常設表示の実際の見た目確認はTask 2以降）。

- [ ] **Step 8: コミット**

```bash
git add src/main/resources/config.yml src/main/resources/messages.yml src/main/java/org/craftcore/stellaria/managers/ActionBarManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/listeners/PlayerListener.java
git commit -m "feat: add ActionBarManager with channel-based simultaneous display" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmS2NT7oFcvtcZjiS8jkX6"
```

---

## Task 2: 常設ステータス表示 + TPAカウントダウン連携

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/tpa/TpaCore.java`

**Interfaces:**
- Consumes: `ActionBarManager.setChannel`/`clearChannel`（Task 1）、`ConfigManager.getMessage`/`getBoolean`/`getInt`（既存）、`FormatUtil.replace`（既存）。
- Produces: 常設ステータス表示の実配信、TPAテレポート待機中のアクションバーカウントダウン。

- [ ] **Step 1: 常設ステータス表示を tick に組み込む**

常設ステータスは「`persistent`という固定チャンネルIDを毎tick`setChannel`し続ける」ことで実現する。
`ActionBarManager`自体は特定チャンネルの意味を知らなくていい設計なので、`StellariaCore`側の
グローバルスケジューラでもう1つ小さいタスクを回す。

`src/main/java/org/craftcore/stellaria/StellariaCore.java` の、Task 1で追加した
`ActionBarManager`のtick登録ブロック:

```java
        this.actionBarManager = new ActionBarManager(this);
        if (configManager.getBoolean("action-bar.enabled", true)) {
            long actionBarInterval = configManager.getInt("action-bar.update-interval-ticks", 5);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> actionBarManager.tick(), actionBarInterval, actionBarInterval);
        }
```

これを次のように変更（常設ステータスの配信タスクを追加）:

```java
        this.actionBarManager = new ActionBarManager(this);
        if (configManager.getBoolean("action-bar.enabled", true)) {
            long actionBarInterval = configManager.getInt("action-bar.update-interval-ticks", 5);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> actionBarManager.tick(), actionBarInterval, actionBarInterval);

            if (configManager.getBoolean("action-bar.persistent.enabled", false)) {
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                    String template = configManager.getString("action-bar.persistent.template", "");
                    for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                        String resolved = placeholderManager.resolve(template, online);
                        actionBarManager.setChannel(online, "persistent", org.craftcore.stellaria.utils.ColorUtil.component(resolved));
                    }
                }, actionBarInterval, actionBarInterval);
            }
        }
```

- [ ] **Step 2: ビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: TpaCore にカウントダウン用の状態とヘルパーを追加**

`src/main/java/org/craftcore/stellaria/commands/tpa/TpaCore.java` の既存フィールド:

```java
    // テレポート詠唱中（承認後の遅延待ち）のプレイヤーUUID -> キャンセル用ScheduledTask
    private final static Map<UUID, ScheduledTask> pendingTeleport = new HashMap<>();
```

これを次のように変更（カウントダウン用のマップを追加）:

```java
    // テレポート詠唱中（承認後の遅延待ち）のプレイヤーUUID -> キャンセル用ScheduledTask
    private final static Map<UUID, ScheduledTask> pendingTeleport = new HashMap<>();
    // テレポート詠唱中のアクションバーカウントダウン用ScheduledTask
    private final static Map<UUID, ScheduledTask> pendingCountdown = new HashMap<>();
```

既存の`resetPlayerTeleportRequests`:

```java
    public static void resetPlayerTeleportRequests(Player player){
        tpRequest.remove(player.getUniqueId());
        tpHere.remove(player.getUniqueId());
        tpaPendingSender.remove(player.getUniqueId());
        tpHerePendingSender.remove(player.getUniqueId());
        ScheduledTask task = pendingTeleport.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }
```

これを次のように変更（カウントダウンタスクも一緒に止める）:

```java
    public static void resetPlayerTeleportRequests(Player player){
        tpRequest.remove(player.getUniqueId());
        tpHere.remove(player.getUniqueId());
        tpaPendingSender.remove(player.getUniqueId());
        tpHerePendingSender.remove(player.getUniqueId());
        ScheduledTask task = pendingTeleport.remove(player.getUniqueId());
        if (task != null) task.cancel();
        ScheduledTask countdownTask = pendingCountdown.remove(player.getUniqueId());
        if (countdownTask != null) countdownTask.cancel();
    }
```

既存の`onEntityDamage`:

```java
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        ScheduledTask task = pendingTeleport.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            player.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_warmup_cancelled", player));
        }
    }
```

これを次のように変更（キャンセル時にカウントダウン表示も消す）:

```java
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        ScheduledTask task = pendingTeleport.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            stopCountdown(player);
            player.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_warmup_cancelled", player));
        }
    }
```

- [ ] **Step 4: startCountdown/stopCountdown ヘルパーを追加し、scheduleTeleport から呼ぶ**

既存の`scheduleTeleport`:

```java
    private void scheduleTeleport(Player mover, Player destination) {
        int delaySeconds = plugin.getConfigManager().getInt("tpa.teleport-delay-seconds", 5);
        long delayTicks = Math.max(0, delaySeconds) * 20L;
        UUID moverId = mover.getUniqueId();
        UUID destinationId = destination.getUniqueId();

        if (delayTicks <= 0) {
            performTeleport(mover, destination);
            return;
        }

        mover.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("tpa.tpa_warmup", mover),
                "%seconds%", String.valueOf(delaySeconds)));

        ScheduledTask task = mover.getScheduler().runDelayed(plugin, scheduledTask -> {
            pendingTeleport.remove(moverId);
            Player freshMover = Bukkit.getPlayer(moverId);
            Player freshDestination = Bukkit.getPlayer(destinationId);
            if (freshMover == null) return;
            if (freshDestination == null) {
                freshMover.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_warmup_target_offline", freshMover));
                return;
            }
            performTeleport(freshMover, freshDestination);
        }, () -> pendingTeleport.remove(moverId), delayTicks);

        pendingTeleport.put(moverId, task);
    }
```

これを次のように変更（カウントダウン開始・停止を組み込む）:

```java
    private void scheduleTeleport(Player mover, Player destination) {
        int delaySeconds = plugin.getConfigManager().getInt("tpa.teleport-delay-seconds", 5);
        long delayTicks = Math.max(0, delaySeconds) * 20L;
        UUID moverId = mover.getUniqueId();
        UUID destinationId = destination.getUniqueId();

        if (delayTicks <= 0) {
            performTeleport(mover, destination);
            return;
        }

        mover.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("tpa.tpa_warmup", mover),
                "%seconds%", String.valueOf(delaySeconds)));

        startCountdown(mover, delaySeconds);

        ScheduledTask task = mover.getScheduler().runDelayed(plugin, scheduledTask -> {
            pendingTeleport.remove(moverId);
            stopCountdown(mover);
            Player freshMover = Bukkit.getPlayer(moverId);
            Player freshDestination = Bukkit.getPlayer(destinationId);
            if (freshMover == null) return;
            if (freshDestination == null) {
                freshMover.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_warmup_target_offline", freshMover));
                return;
            }
            performTeleport(freshMover, freshDestination);
        }, () -> {
            pendingTeleport.remove(moverId);
            stopCountdown(mover);
        }, delayTicks);

        pendingTeleport.put(moverId, task);
    }

    /** テレポート待機中、1秒ごとに残り秒数をアクションバーへ表示する。 */
    private void startCountdown(Player mover, int totalSeconds) {
        UUID moverId = mover.getUniqueId();
        int[] remaining = {totalSeconds};

        ScheduledTask countdownTask = mover.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
            Player freshMover = Bukkit.getPlayer(moverId);
            if (freshMover == null || remaining[0] < 0) {
                scheduledTask.cancel();
                pendingCountdown.remove(moverId);
                return;
            }
            String message = FormatUtil.replace(
                    plugin.getConfigManager().getMessage("tpa.tpa_warmup_actionbar", freshMover),
                    "%seconds%", String.valueOf(remaining[0]));
            plugin.getActionBarManager().setChannel(freshMover, "tpa_countdown", ColorUtil.component(message));
            remaining[0]--;
        }, () -> pendingCountdown.remove(moverId), 0L, 20L);

        pendingCountdown.put(moverId, countdownTask);
    }

    /** カウントダウンタスクを止めてアクションバー表示も消す。 */
    private void stopCountdown(Player mover) {
        ScheduledTask task = pendingCountdown.remove(mover.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        plugin.getActionBarManager().clearChannel(mover, "tpa_countdown");
    }
```

- [ ] **Step 5: ビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 手動確認（`./gradlew runServer`）**

1. `config.yml`の`action-bar.persistent.enabled: true`・適当な`template`を設定して起動
2. 2人のテストプレイヤーで`/tpa <player>`→承認 → 移動元プレイヤーのアクションバーに
   「5秒後にテレポートします」のようなカウントダウンが1秒ごとに減っていく
   （常設ステータスが有効なら`|`区切りで両方見える）
3. カウントダウン中にダメージを受ける → チャットのキャンセル通知と同時にアクションバーの
   カウントダウンも消える（常設ステータスのみに戻る）
4. カウントダウン完了 → 通常通りテレポートされ、アクションバーのカウントダウンも消える
5. `tphere`でも同様にカウントダウンが機能する

- [ ] **Step 7: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/commands/tpa/TpaCore.java
git commit -m "feat: add persistent action-bar status and TPA countdown" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmS2NT7oFcvtcZjiS8jkX6"
```

---

## Task 3: AFK個人フラッシュ連携

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/AfkManager.java`

**Interfaces:**
- Consumes: `ActionBarManager.flash`（Task 1）、`ConfigManager.getMessage`/`getInt`（既存）。
- Produces: AFK/復帰時の本人向けアクションバーフラッシュ表示。

- [ ] **Step 1: AfkManager の broadcastStateChange にフラッシュ通知を追加**

`src/main/java/org/craftcore/stellaria/managers/AfkManager.java` の既存の`broadcastStateChange`:

```java
    private void broadcastStateChange(Player player, boolean afk) {
        String path = afk ? "afk.became" : "afk.returned";
        String message = plugin.getConfigManager().getMessage(path, player);
        Bukkit.broadcast(ColorUtil.component(message));
    }
```

これを次のように変更（本人へのアクションバーフラッシュを追加）:

```java
    private void broadcastStateChange(Player player, boolean afk) {
        String path = afk ? "afk.became" : "afk.returned";
        String message = plugin.getConfigManager().getMessage(path, player);
        Bukkit.broadcast(ColorUtil.component(message));

        String actionBarPath = afk ? "afk.became_actionbar" : "afk.returned_actionbar";
        String actionBarMessage = plugin.getConfigManager().getMessage(actionBarPath, player);
        long durationTicks = plugin.getConfigManager().getInt("afk.actionbar-flash-seconds", 3) * 20L;
        plugin.getActionBarManager().flash(player, "afk_flash", ColorUtil.component(actionBarMessage), durationTicks);
    }
```

- [ ] **Step 2: ビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 手動確認（`./gradlew runServer`）**

1. 一定時間操作せず`afk.timeout-seconds`を超える（テスト用に短く設定してもよい） →
   自動でAFKになり、本人のアクションバーに「離席中です」が一瞬表示され、
   `afk.actionbar-flash-seconds`秒後に消える（常設ステータスが有効ならその後は常設表示に戻る）
2. 移動してAFKから復帰 → 「お帰りなさい」が一瞬表示される
3. `/afk`コマンドで手動トグルした場合も同様にフラッシュが出る

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/AfkManager.java
git commit -m "feat: flash action-bar notification on AFK state change" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmS2NT7oFcvtcZjiS8jkX6"
```

---

## Task 4: GUIフレームワーク（Gui + GuiListener）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/gui/Gui.java`
- Create: `src/main/java/org/craftcore/stellaria/gui/GuiListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Produces:
  - `Gui`（抽象クラス、`InventoryHolder`実装）: `getInventory(): Inventory`、`open(Player): void`、
    `onClick(InventoryClickEvent): void`（デフォルト何もしない）、`onClose(InventoryCloseEvent): void`
    （デフォルト何もしない）
  - `GuiListener`（`Listener`）: `Gui`を継承した画面へのクリック/クローズイベントを振り分ける

- [ ] **Step 1: Gui 抽象クラスを作成**

`src/main/java/org/craftcore/stellaria/gui/Gui.java`:

```java
package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * インベントリベースのGUI画面を作るための汎用フレームワーク。継承先はコンストラクタで
 * アイテムを並べ、必要に応じて {@link #onClick}/{@link #onClose} をオーバーライドする。
 * クリック・クローズイベントの購読・振り分けは {@link GuiListener} が一括で担当するので、
 * 継承先で {@code @EventHandler} を書く必要はない。
 *
 * 標準Bukkit Inventory APIのみを使用する（packeteventsは使わない — 通常のクリック式メニューは
 * 標準APIで十分に実現でき、パケット層を直接いじる必要が無いため）。
 */
public abstract class Gui implements InventoryHolder {

    private final Inventory inventory;

    protected Gui(int size, Component title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    @NotNull
    public Inventory getInventory() {
        return inventory;
    }

    /** この画面をプレイヤーに開く。 */
    public void open(Player player) {
        player.openInventory(inventory);
    }

    /**
     * この画面内でクリックされた時に呼ばれる（{@link GuiListener} 経由）。
     * デフォルトは何もしない。アイテムを持ち出されたくない場合は継承先で
     * {@code event.setCancelled(true)} を呼ぶこと。
     */
    public void onClick(InventoryClickEvent event) {
    }

    /** この画面が閉じられた時に呼ばれる（{@link GuiListener} 経由）。デフォルトは何もしない。 */
    public void onClose(InventoryCloseEvent event) {
    }
}
```

- [ ] **Step 2: GuiListener を作成**

`src/main/java/org/craftcore/stellaria/gui/GuiListener.java`:

```java
package org.craftcore.stellaria.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * {@link Gui} を継承した画面へのクリック・クローズイベントをまとめて振り分ける唯一のリスナー。
 * {@code getHolder()} が {@link Gui} のインスタンスでなければ（通常のチェスト・かまど等の場合）
 * 何もしない。
 */
public class GuiListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Gui gui) {
            gui.onClick(event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Gui gui) {
            gui.onClose(event);
        }
    }
}
```

- [ ] **Step 3: StellariaCore に GuiListener を登録**

`src/main/java/org/craftcore/stellaria/StellariaCore.java` の import群に追加:

```java
import org.craftcore.stellaria.gui.GuiListener;
```

`onEnable` 内、`getServer().getPluginManager().registerEvents(new MuteCommandBlockListener(this), this);` の直後に追加:

```java
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
```

- [ ] **Step 4: ビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 起動確認**

`./gradlew runServer` を起動し、`GuiListener`登録を含めて例外なく`Done`まで到達することを確認する。
具体的な`Gui`のサブクラス・コマンドはまだ無いため、実機での見た目確認はこのタスクでは行わない
（将来の機能がこのフレームワークを継承して使う）。

- [ ] **Step 6: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/gui/Gui.java src/main/java/org/craftcore/stellaria/gui/GuiListener.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add generic Gui/GuiListener framework" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmS2NT7oFcvtcZjiS8jkX6"
```

---

## Task 5: 統合確認 + ブランチ仕上げ

**Files:** なし（確認のみ）

**Interfaces:**
- Consumes: Task 1〜4で実装した全機能。

- [ ] **Step 1: フルビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 起動ログの確認**

`./gradlew runServer` を起動し、コンソールログに例外が出ずに`Done`まで到達することを確認してから停止する。

- [ ] **Step 3: spec記載のテスト方針を一通り流す**

`docs/superpowers/specs/2026-09-14-actionbar-gui-design.md` の「テスト方針」セクションの1〜7を
通しで確認する（Task 2・3の手動確認で大部分はカバー済みのため、`action-bar.enabled: false`にした
場合に全てのアクションバー表示が出なくなり例外も出ないことなど、抜けている項目を重点的に確認する）。

- [ ] **Step 4: finishing-a-development-branch スキルで仕上げ**

**REQUIRED SUB-SKILL:** Use superpowers:finishing-a-development-branch

全タスク完了・確認OKであれば、ブランチ運用の後始末（ローカルmerge / PR作成 / そのまま保持）を
このスキルに従って進める。
