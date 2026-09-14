# 経済コマンド Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/pay`（送金）・`/eco give|set|take`（管理者用残高操作）・`/balance [player|top [page]]`（残高確認・ランキング）の3コマンドを実装し、`config.yml`の`economy.default-balance`（現状未実装）を実際に使う初期所持金付与も仕上げる。

**Architecture:** 既存の`EconomyManager`（Vault連携・SQLite永続化）に`setBalance`/`transfer`/`getTopBalances`/`getPlayerCount`を追加し、3つの新規コマンドクラスはこれらのAPIを呼ぶだけの薄い層にする。`/eco`は`TpaCore`/`MuteCommand`と同じ「関連サブコマンドを1クラスでdispatch」方針、`/balance`も同様に引数解析だけで`top`とプレイヤー指定を切り替える。

**Tech Stack:** PaperMC 1.21 / Java 21 / VaultUnlockedAPI / SQLite（`DatabaseManager`経由）

**Spec:** `docs/superpowers/specs/2026-09-14-economy-commands-design.md`

## Global Constraints

- 金額は常に整数（`EconomyManager.fractionalDigits() == 0`）。`/pay`・`/eco give`・`/eco take`は1以上、`/eco set`は0以上の整数のみ受け付ける。
- 残高がマイナスになる操作は常に拒否する（`/pay`・`/eco take`とも残高不足なら失敗、確認済みの方針）。
- 金額の表示は必ず`EconomyManager.format(double)`（万/億/兆表記＋「円」接尾辞）を通す。生の整数をそのまま表示しない。
- `/eco`の対象はオフラインでも操作可（`Bukkit.getOfflinePlayer`解決、`/mute`と同じ前提）。`/pay`の対象はオンライン必須。
- 全てのユーザー向け文言は`messages.yml`経由。
- このリポジトリに自動テストは無い（`src/test`無し）。各タスクの検証は`./gradlew build`の成功 + 該当箇所の`./gradlew runServer`手動確認で行う。

---

## Task 1: EconomyManager拡張 + config/messages/plugin.yml基盤 + 初期所持金

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/EconomyManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/PlayerJoinListener.java`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/plugin.yml`

**Interfaces:**
- Produces:
  - `EconomyManager.setBalance(OfflinePlayer, double): boolean`
  - `EconomyManager.transfer(OfflinePlayer from, OfflinePlayer to, double amount): boolean`
  - `EconomyManager.BalanceEntry`（record: `name: String, coins: int`）
  - `EconomyManager.getTopBalances(int limit, int offset): List<BalanceEntry>`
  - `EconomyManager.getPlayerCount(): int`
  - config/messages/plugin.ymlの全キー（後続タスクが依存）

- [ ] **Step 1: EconomyManager に独自拡張メソッドを追加**

`src/main/java/org/craftcore/stellaria/managers/EconomyManager.java` の
`hasAccount`/`createPlayerAccount`セクションの直前（`// アカウント作成・確認`の区切りコメントの前）に、
新しいセクションとして追記:

```java
    // -------------------------------------------------------------
    // 独自拡張（Vault標準APIに無い操作）
    // -------------------------------------------------------------

    /** 残高を指定額に設定する（Vaultの標準APIには無い操作）。マイナス指定は禁止。 */
    public boolean setBalance(OfflinePlayer player, double amount) {
        if (player == null || amount < 0) {
            return false;
        }
        int newBalance = (int) amount;
        DatabaseManager.updateAsync("players", java.util.Map.of("coins", newBalance), "uuid = ?", player.getUniqueId().toString());
        return true;
    }

    /**
     * from -> to へ amount を送金する。DatabaseManager.transaction() で2件のUPDATEを
     * 1トランザクションにまとめる。from の残高が不足していれば何もせず false を返す。
     */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (from == null || to == null || amount <= 0) {
            return false;
        }
        double currentFrom = getBalance(from);
        if (currentFrom < amount) {
            return false;
        }
        int newFromBalance = (int) (currentFrom - amount);
        int newToBalance = (int) (getBalance(to) + amount);
        DatabaseManager.transaction(conn -> {
            DatabaseManager.execute("UPDATE players SET coins = ? WHERE uuid = ?", newFromBalance, from.getUniqueId().toString());
            DatabaseManager.execute("UPDATE players SET coins = ? WHERE uuid = ?", newToBalance, to.getUniqueId().toString());
        });
        return true;
    }

    /** /balance top のランキング1行分。 */
    public record BalanceEntry(String name, int coins) {
    }

    /** 残高降順で limit 件、offset 件スキップして取得する（/balance top のページング用）。 */
    public List<BalanceEntry> getTopBalances(int limit, int offset) {
        return DatabaseManager.query(
            "SELECT name, coins FROM players ORDER BY coins DESC LIMIT ? OFFSET ?",
            rs -> new BalanceEntry(rs.getString("name"), rs.getInt("coins")),
            limit, offset
        );
    }

    /** players テーブルの総レコード数（/balance top のページ数計算用）。 */
    public int getPlayerCount() {
        Integer count = DatabaseManager.queryOne("SELECT COUNT(*) as cnt FROM players", rs -> rs.getInt("cnt"));
        return count != null ? count : 0;
    }

```

（`List`は既存のimportで足りている。`java.util.Map.of`は既存の`withdrawPlayer`/`depositPlayer`と同じく完全修飾名で呼ぶので追加importは不要）

- [ ] **Step 2: PlayerJoinListener の初期所持金を config 駆動にする**

`src/main/java/org/craftcore/stellaria/listeners/PlayerJoinListener.java` の既存の該当部分:

```java
        if (!exists) {
            DatabaseManager.insertAsync("players", Map.of(
                "uuid", uuid,
                "name", event.getPlayer().getName(),
                "coins", 0
            ));
        }
```

これを次のように変更:

```java
        if (!exists) {
            int defaultBalance = plugin.getConfigManager().getInt("economy.default-balance", 1000);
            DatabaseManager.insertAsync("players", Map.of(
                "uuid", uuid,
                "name", event.getPlayer().getName(),
                "coins", defaultBalance
            ));
        }
```

- [ ] **Step 3: config.yml の economy セクションを更新**

既存:

```yaml
economy:
  default-balance: 1000 # 新規プレイヤーの初期所持金 !NOTE: 未実装
```

これを次のように変更（未実装コメントを削除し、ページサイズを追加）:

```yaml
economy:
  default-balance: 1000 # 新規プレイヤーの初期所持金
  balance-top-page-size: 10
```

- [ ] **Step 4: messages.yml に pay/eco/balance セクションを追加**

`src/main/resources/messages.yml` の末尾に追記:

```yaml

pay:
  usage: "&%c使用方法: /pay <プレイヤー> <金額>"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  self: "&%c自分自身に送金することはできません。"
  invalid_amount: "&%c金額は1以上の整数で指定してください。"
  player_not_found: "&%c%player% &%7はオンラインではありません。"
  insufficient_balance: "&%c所持金が不足しています。"
  sender: "&%a%player% &%7に%amount%を送金しました。"
  receiver: "&%a%player% &%7から%amount%を受け取りました。"

eco:
  usage: "&%c使用方法: /eco <give|set|take> <プレイヤー> <金額>"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  invalid_subcommand: "&%c不明なサブコマンドです。give/set/take のいずれかを指定してください。"
  invalid_amount: "&%c金額は0以上の整数で指定してください。"
  player_not_found: "&%c%player% &%7はオフラインまたは存在しません。"
  insufficient_balance: "&%c%player% &%7の所持金が不足しています。"
  give_sender: "&%a%player% &%7に%amount%を付与しました。"
  give_receiver: "&%a%amount% &%7を受け取りました。"
  set_sender: "&%a%player% &%7の所持金を%amount%に設定しました。"
  set_receiver: "&%aあなたの所持金が%amount%に設定されました。"
  take_sender: "&%a%player% &%7から%amount%を回収しました。"
  take_receiver: "&%c%amount% &%7が回収されました。"

balance:
  usage: "&%c使用方法: /balance [プレイヤー|top [ページ]]"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  no_permission_others: "&%c他人の残高を確認する権限がありません。"
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  player_not_found: "&%c%player% &%7はオフラインまたは存在しません。"
  self: "&%7所持金: %amount%"
  other: "&%7%player% &%7の所持金: %amount%"
  top_header: "&%6&l--- 所持金ランキング（%page%/%max_page%） ---"
  top_entry: "&%7#%rank% &%f%player% &%7- %amount%"
  top_empty: "&%7該当するプレイヤーがいません。"
```

- [ ] **Step 5: plugin.yml にコマンド・権限を追加**

既存の`commands:`末尾:

```yaml
  mute:
  unmute:
```

これを次のように変更:

```yaml
  mute:
  unmute:
  pay:
  eco:
  balance:
    aliases: [money, bal]
```

既存の`permissions:`ブロック:

```yaml
permissions:
  stellaria.msg:
    default: true
```

これを次のように変更:

```yaml
permissions:
  stellaria.msg:
    default: true
  stellaria.pay:
    default: true
  stellaria.balance:
    default: true
```

- [ ] **Step 6: ビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 起動確認**

`./gradlew runServer` を起動し、コンソールに例外が出ずに`Done`まで到達することを確認してから停止する。
この時点ではまだコマンドが登録されていないため（Task 2〜4で登録）、実際のコマンド動作確認はできない
— 起動が正常に通ることだけを確認すれば十分。

- [ ] **Step 8: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/EconomyManager.java src/main/java/org/craftcore/stellaria/listeners/PlayerJoinListener.java src/main/resources/config.yml src/main/resources/messages.yml src/main/resources/plugin.yml
git commit -m "feat: extend EconomyManager and default-balance foundation" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmS2NT7oFcvtcZjiS8jkX6"
```

---

## Task 2: /pay コマンド

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/PayCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `EconomyManager.transfer`/`format`（Task 1）、`ConfigManager.getMessage`（既存）。
- Produces: `/pay`コマンド。

- [ ] **Step 1: PayCommand を作成**

`src/main/java/org/craftcore/stellaria/commands/PayCommand.java`:

```java
package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.EconomyManager;
import org.jetbrains.annotations.NotNull;

/**
 * /pay コマンド。プレイヤー間送金。EconomyManager.transfer() が1トランザクションで処理する。
 */
public class PayCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public PayCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("pay.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.pay")) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.no_permission", player));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.usage", player));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.player_not_found", Bukkit.getOfflinePlayer(args[0])));
            return true;
        }
        if (target.equals(player)) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.self", player));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
            if (amount <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.invalid_amount", player));
            return true;
        }

        EconomyManager economy = plugin.getEconomyManager();
        if (!economy.transfer(player, target, amount)) {
            player.sendMessage(plugin.getConfigManager().getMessage("pay.insufficient_balance", player));
            return true;
        }

        String amountText = economy.format(amount);
        player.sendMessage(plugin.getConfigManager().getMessage("pay.sender", target)
                .replace("%amount%", amountText));
        target.sendMessage(plugin.getConfigManager().getMessage("pay.receiver", player)
                .replace("%amount%", amountText));
        return true;
    }
}
```

- [ ] **Step 2: StellariaCore に配線**

import群に追加:

```java
import org.craftcore.stellaria.commands.PayCommand;
```

`onEnable` 内、`getCommand("reply").setExecutor(messageCommand);` の直後に追加:

```java
        getCommand("pay").setExecutor(new PayCommand(this));
```

- [ ] **Step 3: ビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 手動確認（`./gradlew runServer`）**

1. `/pay <player> 500` → 送受信双方に通知、双方の`%money%`表示（スコアボード等）が反映される
2. 残高を超える金額で`/pay` → `pay.insufficient_balance`、残高は変化しない
3. `/pay <自分> 100` → `pay.self`
4. `/pay <オフラインプレイヤー名> 100` → `pay.player_not_found`
5. `/pay <player> abc` / `/pay <player> 0` → `pay.invalid_amount`

- [ ] **Step 5: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/commands/PayCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add /pay command" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmS2NT7oFcvtcZjiS8jkX6"
```

---

## Task 3: /eco コマンド

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/EcoCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `EconomyManager.depositPlayer`/`withdrawPlayer`/`setBalance`/`getBalance`/`format`（既存＋Task 1）、
  `ConfigManager.getMessage`（既存）。
- Produces: `/eco give|set|take`コマンド。

- [ ] **Step 1: EcoCommand を作成**

`src/main/java/org/craftcore/stellaria/commands/EcoCommand.java`:

```java
package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.EconomyManager;
import org.jetbrains.annotations.NotNull;

/**
 * /eco give|set|take コマンド。管理者用の残高操作を1つのCommandExecutorで捌く
 * （TpaCore/MuteCommandと同じ「関連サブコマンドをまとめてdispatch」方針）。
 */
public class EcoCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public EcoCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.eco")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("eco.no_permission", null));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.getConfigManager().getMessage("eco.usage", null));
            return true;
        }

        String subCommand = args[0].toLowerCase();
        if (!subCommand.equals("give") && !subCommand.equals("set") && !subCommand.equals("take")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("eco.invalid_subcommand", null));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("eco.player_not_found", target));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
            boolean valid = subCommand.equals("set") ? amount >= 0 : amount > 0;
            if (!valid) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.getConfigManager().getMessage("eco.invalid_amount", null));
            return true;
        }

        EconomyManager economy = plugin.getEconomyManager();
        switch (subCommand) {
            case "give" -> {
                economy.depositPlayer(target, amount);
                notify(sender, target, "eco.give_sender", "eco.give_receiver", amount);
            }
            case "set" -> {
                economy.setBalance(target, amount);
                notify(sender, target, "eco.set_sender", "eco.set_receiver", amount);
            }
            case "take" -> {
                if (economy.getBalance(target) < amount) {
                    sender.sendMessage(plugin.getConfigManager().getMessage("eco.insufficient_balance", target));
                    return true;
                }
                economy.withdrawPlayer(target, amount);
                notify(sender, target, "eco.take_sender", "eco.take_receiver", amount);
            }
        }
        return true;
    }

    private void notify(CommandSender sender, OfflinePlayer target, String senderKey, String receiverKey, int amount) {
        String amountText = plugin.getEconomyManager().format(amount);
        sender.sendMessage(plugin.getConfigManager().getMessage(senderKey, target)
                .replace("%amount%", amountText));
        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage(plugin.getConfigManager().getMessage(receiverKey, target)
                    .replace("%amount%", amountText));
        }
    }
}
```

- [ ] **Step 2: StellariaCore に配線**

import群に追加:

```java
import org.craftcore.stellaria.commands.EcoCommand;
```

`onEnable` 内、`getCommand("pay").setExecutor(new PayCommand(this));` の直後に追加:

```java
        getCommand("eco").setExecutor(new EcoCommand(this));
```

- [ ] **Step 3: ビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 手動確認（`./gradlew runServer`）**

1. `/eco give <player> 1000` → 対象の残高が増える、双方に通知
2. `/eco set <player> 0` → 対象の残高が0になる
3. `/eco take <player> 100` → 対象の残高が減る
4. 残高より多い金額で`/eco take` → `eco.insufficient_balance`、残高は変化しない
5. `/eco set <player> -1` → `eco.invalid_amount`（set は0以上のみ許容）
6. `/eco foo <player> 100` → `eco.invalid_subcommand`
7. オフラインプレイヤーに対しても`give`/`set`/`take`が実行できる（一度でもサーバーに来たことがある名前で確認）

- [ ] **Step 5: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/commands/EcoCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add /eco give|set|take command" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01CmS2NT7oFcvtcZjiS8jkX6"
```

---

## Task 4: /balance コマンド

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/BalanceCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `EconomyManager.getBalance`/`getTopBalances`/`getPlayerCount`/`format`/`BalanceEntry`
  （既存＋Task 1）、`ConfigManager.getMessage`/`getInt`（既存）。
- Produces: `/balance`（alias `money`, `bal`）コマンド。

- [ ] **Step 1: BalanceCommand を作成**

`src/main/java/org/craftcore/stellaria/commands/BalanceCommand.java`:

```java
package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.EconomyManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /balance（alias money, bal）コマンド。引数なしで自分の残高、プレイヤー指定で他人の残高
 * （要 stellaria.balance.others）、"top [page]" でランキング表示。
 */
public class BalanceCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public BalanceCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.balance")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.no_permission", null));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("top")) {
            showTop(sender, args);
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(plugin.getConfigManager().getMessage("balance.must_be_player", null));
                return true;
            }
            showBalance(sender, self, "balance.self");
            return true;
        }

        if (!sender.hasPermission("stellaria.balance.others")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.no_permission_others", null));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.player_not_found", target));
            return true;
        }
        showBalance(sender, target, "balance.other");
        return true;
    }

    private void showBalance(CommandSender sender, OfflinePlayer target, String messageKey) {
        EconomyManager economy = plugin.getEconomyManager();
        String amountText = economy.format(economy.getBalance(target));
        sender.sendMessage(plugin.getConfigManager().getMessage(messageKey, target)
                .replace("%amount%", amountText));
    }

    private void showTop(CommandSender sender, String[] args) {
        int pageSize = plugin.getConfigManager().getInt("economy.balance-top-page-size", 10);
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                page = 1;
            }
        }

        EconomyManager economy = plugin.getEconomyManager();
        int totalPlayers = economy.getPlayerCount();
        int maxPage = Math.max(1, (int) Math.ceil(totalPlayers / (double) pageSize));
        int offset = (page - 1) * pageSize;

        List<EconomyManager.BalanceEntry> entries = economy.getTopBalances(pageSize, offset);

        sender.sendMessage(plugin.getConfigManager().getMessage("balance.top_header", null)
                .replace("%page%", String.valueOf(page))
                .replace("%max_page%", String.valueOf(maxPage)));

        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.top_empty", null));
            return;
        }

        int rank = offset + 1;
        for (EconomyManager.BalanceEntry entry : entries) {
            sender.sendMessage(plugin.getConfigManager().getMessage("balance.top_entry", null)
                    .replace("%rank%", String.valueOf(rank))
                    .replace("%player%", entry.name())
                    .replace("%amount%", economy.format(entry.coins())));
            rank++;
        }
    }
}
```

- [ ] **Step 2: StellariaCore に配線**

import群に追加:

```java
import org.craftcore.stellaria.commands.BalanceCommand;
```

`onEnable` 内、`getCommand("eco").setExecutor(new EcoCommand(this));` の直後に追加:

```java
        getCommand("balance").setExecutor(new BalanceCommand(this));
```

- [ ] **Step 3: ビルド確認**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 手動確認（`./gradlew runServer`）**

1. `/balance` → 自分の残高が表示される
2. `/money`, `/bal` のエイリアスも同様に動作する
3. `/balance <player>`（`stellaria.balance.others`を持たない状態） → `balance.no_permission_others`
4. OPに`stellaria.balance.others`を付与して`/balance <player>` → 対象の残高が表示される
5. `/balance top` → 残高降順でランキングが表示される（`--- 所持金ランキング（1/N） ---`のヘッダー付き）
6. `economy.balance-top-page-size`を2に変更して`/stellariareload`、3人以上プレイヤーがいる状態で
   `/balance top 2` → 2ページ目の内容が表示される
7. 存在しないページ番号（例: `/balance top 999`） → `balance.top_empty`（エラーにならない）

- [ ] **Step 5: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/commands/BalanceCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add /balance command with top ranking" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
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

- [ ] **Step 3: 初期所持金の確認**

新規プレイヤー（一度もこのテストサーバーに来たことのない名前）で参加し、`/balance`で
`economy.default-balance`（デフォルト1000）が初期残高になっていることを確認する。

- [ ] **Step 4: spec記載のテスト方針を一通り流す**

`docs/superpowers/specs/2026-09-14-economy-commands-design.md` の「テスト方針」セクションの1〜7を
通しで確認する（Task 2〜4の手動確認で大部分はカバー済みのため、抜けている項目があれば重点的に確認する）。

- [ ] **Step 5: finishing-a-development-branch スキルで仕上げ**

**REQUIRED SUB-SKILL:** Use superpowers:finishing-a-development-branch

全タスク完了・確認OKであれば、ブランチ運用の後始末（ローカルmerge / PR作成 / そのまま保持）を
このスキルに従って進める。
