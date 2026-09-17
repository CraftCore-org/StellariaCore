# Codex監査 Critical/High 修正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Codex(GPT-5.3系レビューエージェント)による2026-09-17のStellariaCore全体監査で指摘されたCritical 4件・High 14件のうち、本番運用がFolia前提の3件(H-4/H-7/H-10, YAGNIで見送り)を除いた計14件を修正する。

**Architecture:** 新規コンポーネントは作らない。既存の`EconomyManager`/`DatabaseManager`/`TpaCore`/`MuteManager`/`LandManager`/`KikoriManager`/`MineManager`/`DiscordBotManager`/`PlaceholderManager`/`PlaytimeManager`/`AdminShopGui`/`ChunkBorderCommand`/`LandCommand`/`TimeVoteCommand`/`WeatherVoteCommand`/`KikoriListener`/`MineListener`/`StellariaCore`に対する局所修正の積み重ね。経済処理は「read-modify-write」から「DBの条件付きUPDATE + 影響行数チェック」によるアトミック化に統一し、DB接続は`DatabaseManager`の主要メソッドを`synchronized`化して直列化する。

**Tech Stack:** Java 21 / PaperMC 1.21 API / Gradle (Kotlin DSL) / SQLite（`sqlite-jdbc`経由、`DatabaseManager`ラッパー）

**Spec:** 独立したspecファイルは無し。本プラン自体がspecを兼ねる。出典はCodexによるStellariaCore監査レポート（本セッションの会話履歴、2026-09-17実施）と、そのCritical/High 18件全件を実コードと突き合わせた検証結果（forkエージェントによる再検証、同日実施）。

## Global Constraints

- このリポジトリに自動テスト基盤は無い（`src/test`無し、テストタスク未設定 — `CLAUDE.md`参照）。各タスクの検証は `./gradlew build` によるコンパイル確認で行い、最終タスクでの `./gradlew runServer` を使った手動E2E確認で全体の動作を確かめる。ユニットテストを新設するのは本タスクのスコープ外。
- DBを触る処理は `DatabaseManager` の static メソッド（`query`/`queryOne`/`insert`/`update`/`execute`/`exists`/`transaction`）を生のテーブル名・カラム名で直接呼ぶ。リポジトリ層は作らない。
- ユーザー向け文字列は必ず `ConfigManager.getMessage`/`getUsageMessage` 経由。`plugin.getConfig()` を直接呼ばない。
- 本番サーバーは通常のPaper運用（Foliaではない）と確認済み。よって `Bukkit.getGlobalRegionScheduler()`/entity scheduler はメインスレッドで直列実行される互換シムとして扱ってよく、H-4/H-7/H-10（Folia region安全性）はこのプランのスコープ外（YAGNI）。
- **同じ作業ディレクトリで別のClaudeセッションが並行して別機能（ワールド自動リセット等）を実装中の可能性がある。** 各タスク開始前に対象ファイルを必ず読み直し、本プランに書かれた「現在のコード」と差異が無いか確認してから編集すること。差異があれば該当箇所だけプランの意図に沿って手動で追従し、無関係な変更には触れない。
- 各タスックの完了時、対象ファイルのみを `git add` してコミットする（`git add -A`/`git add .` は使わない）。

---

## Task 1: C-1 — PlaceholderManagerの初期化順序を修正（起動時NPE）

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java:213,284`

**Interfaces:** N/A（フィールド代入位置の移動のみ、シグネチャ変更なし）

**現在のコード（209〜227行目、抜粋）:**
```java
        this.muteManager = new MuteManager(this);
        muteManager.loadAll();
        this.privateMessageManager = new PrivateMessageManager(this);

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
`placeholderManager`フィールドは284行目（`// 6. Scoreboard/Tablist/Belowname のインスタンス化とtick開始`の直下）で初めて代入される。`action-bar.persistent.enabled: true`のとき、219行目で登録されるタスクのラムダが未初期化（null）の`placeholderManager`を参照し、初回実行時に`NullPointerException`で落ちる。

- [ ] **Step 1: `placeholderManager`の生成を`actionBarManager`構築より前に移動する**

`PlaceholderManager`のコンストラクタは`plugin`参照しか使わない（`managers/PlaceholderManager.java:33-35`）ため、他のマネージャーへの依存は無く安全に前倒しできる。

209〜213行目を次のように変更する（`this.actionBarManager = new ActionBarManager(this);`の直前に1行追加）:

```java
        this.muteManager = new MuteManager(this);
        muteManager.loadAll();
        this.privateMessageManager = new PrivateMessageManager(this);

        this.placeholderManager = new PlaceholderManager(this);

        this.actionBarManager = new ActionBarManager(this);
```

284行目付近の元の代入行は削除する:

```java
        // 6. Scoreboard/Tablist/Belowname のインスタンス化とtick開始
        // (削除: this.placeholderManager = new PlaceholderManager(this); は上に移動済み)

        this.scoreboardManager = new ScoreboardManager(
            placeholderManager,
            ...
```

- [ ] **Step 2: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（`placeholderManager`未定義エラーが出ないこと）

- [ ] **Step 3: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "fix: PlaceholderManagerをactionBarの永続タスク登録より前に構築する"
```

---

## Task 2: C-2 — EconomyManagerの経済操作をアトミック化する

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/EconomyManager.java:125-142,164-177,248-263`
- Modify: `src/main/java/org/craftcore/stellaria/managers/HomeManager.java:46-62`
- Modify: `src/main/java/org/craftcore/stellaria/managers/WarpManager.java:50-66`
- Modify: `src/main/java/org/craftcore/stellaria/managers/LandManager.java:226-239`

**Interfaces:**
- 変更なし: `withdrawPlayer`/`depositPlayer`/`transfer`の戻り値型・呼び出しシグネチャは維持する（`EconomyResponse`/`boolean`のまま）。挙動のみ「非同期・非アトミック」から「同期・条件付きUPDATE」に変わる。

**問題の中身:** `withdrawPlayer`/`depositPlayer`は`getBalance()`で読んでからローカル計算し`DatabaseManager.updateAsync(...)`で非同期に書く。read-modify-write の間にロックが無いため、同時に2回呼ばれると両方が同じ残高を見て両方成功してしまう（二重購入・残高消失）。`transfer()`も同様に`getBalance()`を先に呼んでから`transaction()`内でUPDATEしており、常に`true`を返す。さらに`HomeManager`/`WarpManager`/`LandManager`は`economy.has(player, cost)`で残高チェックしてから`economy.withdrawPlayer(...)`の**戻り値を見ずに**DB INSERTしており、withdrawが失敗しても常に成功扱いになる（アトミック化後は特に重要——今回`withdrawPlayer`が正しく失敗を返すようになるので、呼び出し側で戻り値を見ないとその失敗が握りつぶされる）。

- [ ] **Step 1: `EconomyManager.withdrawPlayer(OfflinePlayer, double)`を条件付きUPDATEに変更する**

`managers/EconomyManager.java:124-142`を次のように置き換える:

```java
    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "プレイヤーが見つかりません");
        }
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "負の数値は指定できません");
        }

        long amountLong = (long) amount;
        int affected = DatabaseManager.execute(
            "UPDATE players SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
            amountLong, player.getUniqueId().toString(), amountLong
        );
        if (affected <= 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "残高が足りません");
        }

        return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }
```

- [ ] **Step 2: `EconomyManager.depositPlayer(OfflinePlayer, double)`を同期UPDATEに変更する**

`managers/EconomyManager.java:163-177`を次のように置き換える:

```java
    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "プレイヤーが見つかりません");
        }
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "負の数値は指定できません");
        }

        long amountLong = (long) amount;
        DatabaseManager.execute(
            "UPDATE players SET coins = coins + ? WHERE uuid = ?",
            amountLong, player.getUniqueId().toString()
        );

        return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }
```

- [ ] **Step 3: `EconomyManager.transfer(...)`を条件付きUPDATEに変更する**

`managers/EconomyManager.java:244-263`を次のように置き換える（メソッド直上のJavadocコメントも実態に合わせて更新する）:

```java
    /**
     * from -> to へ amount を送金する。from の残高が不足していれば何もせず false を返す
     * （DatabaseManager.transaction() 内で条件付きUPDATEを使い、残高不足を例外でロールバックの
     * トリガーにすることでアトミック性を確保する）。
     */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (from == null || to == null || amount <= 0) {
            return false;
        }
        long amountLong = (long) amount;
        return DatabaseManager.transaction(conn -> {
            int affected = DatabaseManager.execute(
                "UPDATE players SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
                amountLong, from.getUniqueId().toString(), amountLong
            );
            if (affected <= 0) {
                throw new IllegalStateException("残高不足のため送金を中止");
            }
            DatabaseManager.execute("UPDATE players SET coins = coins + ? WHERE uuid = ?", amountLong, to.getUniqueId().toString());
        });
    }
```

- [ ] **Step 4: `HomeManager.set(...)`で`withdrawPlayer`の戻り値を確認してからINSERTする**

`managers/HomeManager.java:46-74`の該当部分（55〜62行目）を次のように置き換える:

```java
        double cost = plugin.getConfigManager().getDouble("home.cost", 0);
        if (cost > 0) {
            net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, cost);
            if (!response.transactionSuccess()) {
                return SetResult.INSUFFICIENT_FUNDS;
            }
        }
```
（`if (cost > 0 && !economy.has(player, cost)) { return SetResult.INSUFFICIENT_FUNDS; }` の事前チェックは削除し、上記の「引き落としてから成否を見る」形に一本化する。`EconomyManager economy = plugin.getEconomyManager();` のローカル変数がこの後使われなくなる場合は削除する。）

- [ ] **Step 5: `WarpManager.set(...)`で同じ修正を行う**

`managers/WarpManager.java:50-78`の該当部分（59〜66行目）をStep 4と同じパターンで置き換える:

```java
        double cost = plugin.getConfigManager().getDouble("warp.cost", 0);
        if (cost > 0) {
            net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, cost);
            if (!response.transactionSuccess()) {
                return SetResult.INSUFFICIENT_FUNDS;
            }
        }
```

- [ ] **Step 6: `LandManager`のclaim処理で同じ修正を行う**

`managers/LandManager.java:226-239`の該当部分（232〜239行目）を次のように置き換える:

```java
        double cost = plugin.getConfigManager().getDouble("land.cost-per-chunk", 500);
        if (cost > 0) {
            net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, cost);
            if (!response.transactionSuccess()) {
                return ClaimOutcome.of(ClaimResult.INSUFFICIENT_FUNDS);
            }
        }
```

- [ ] **Step 7: 既知の残課題をメモしておく（このタスクでは直さない）**

`HomeManager.set`/`WarpManager.set`/`LandManager`のclaim処理は、いずれも「所持数が上限未満か」のチェック（`count(owner) >= max`等）と実際のINSERTの間にTOCTOUレースが残る（同時に2回叩くと上限を超えて作成できる可能性がある）。これはCodexのC-2指摘had明示的に含めていない別種の問題であり、今回のスコープ（Critical/High 18件）には含まれないため修正しない。将来のMedium整理タスクで扱う。

- [ ] **Step 8: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/EconomyManager.java \
        src/main/java/org/craftcore/stellaria/managers/HomeManager.java \
        src/main/java/org/craftcore/stellaria/managers/WarpManager.java \
        src/main/java/org/craftcore/stellaria/managers/LandManager.java
git commit -m "fix: 経済処理を条件付きUPDATEでアトミック化し、呼び出し側で失敗を正しく扱う"
```

---

## Task 3: H-12 — AdminShopGuiでインベントリ満杯時に課金だけされる問題を修正

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/gui/AdminShopGui.java:66-79`
- Modify: `src/main/resources/messages.yml`（`adminshop:`セクション）

**Interfaces:** N/A（GUIクリックハンドラ内部の処理順序変更のみ）

**現在のコード（`gui/AdminShopGui.java:66-79`）:**
```java
        EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, shopItem.price());
        if (response.transactionSuccess()) {
            ItemStack itemStack = new ItemStack(shopItem.material());
            player.getInventory().addItem(itemStack);
            Component purchasedMessage = ColorUtil.component(plugin.getConfigManager().getMessage("adminshop.purchased", player)
                    .replace("%price%", plugin.getEconomyManager().format(shopItem.price())))
                    .replaceText(builder -> builder.matchLiteral("%item%")
                            .replacement(Component.translatable(itemStack.getType().translationKey())));
            player.sendMessage(purchasedMessage);
            return;
        }

        player.sendMessage(plugin.getConfigManager().getMessage("adminshop.insufficient_funds", player)
                .replace("%price%", plugin.getEconomyManager().format(shopItem.price())));
```
`addItem(itemStack)`の戻り値（溢れて入らなかった分のMap）を無視しているため、インベントリが満杯だとお金だけ引かれてアイテムが消える。

- [ ] **Step 1: `messages.yml`に`adminshop.inventory_full`を追加する**

`src/main/resources/messages.yml`の`adminshop:`セクション（393行目`disabled:`の直後）に追記:

```yaml
  inventory_full: "&%cインベントリに空きがありません。購入をキャンセルしました。"
```

- [ ] **Step 2: 先にアイテムを試験的に付与し、入りきった場合のみ課金する順序に変更する**

`gui/AdminShopGui.java:66-79`を次のように置き換える:

```java
        ItemStack itemStack = new ItemStack(shopItem.material());
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(itemStack.clone());
        if (!leftover.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("adminshop.inventory_full", player));
            return;
        }

        EconomyResponse response = plugin.getEconomyManager().withdrawPlayer(player, shopItem.price());
        if (!response.transactionSuccess()) {
            player.getInventory().removeItem(itemStack);
            player.sendMessage(plugin.getConfigManager().getMessage("adminshop.insufficient_funds", player)
                    .replace("%price%", plugin.getEconomyManager().format(shopItem.price())));
            return;
        }

        Component purchasedMessage = ColorUtil.component(plugin.getConfigManager().getMessage("adminshop.purchased", player)
                .replace("%price%", plugin.getEconomyManager().format(shopItem.price())))
                .replaceText(builder -> builder.matchLiteral("%item%")
                        .replacement(Component.translatable(itemStack.getType().translationKey())));
        player.sendMessage(purchasedMessage);
```

`java.util.Map`が未importの場合は`gui/AdminShopGui.java`の先頭に`import java.util.Map;`を追加する。

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/gui/AdminShopGui.java src/main/resources/messages.yml
git commit -m "fix: アドミンショップでインベントリ満杯時に課金だけされる問題を修正"
```

---

## Task 4: C-3 — 境界パーティクル半径の上限を設ける

**Files:**
- Modify: `src/main/resources/config.yml`（`land.border-particle:`セクション）
- Modify: `src/main/java/org/craftcore/stellaria/commands/ChunkBorderCommand.java:58-69`
- Modify: `src/main/java/org/craftcore/stellaria/commands/LandCommand.java:313-324`
- Modify: `src/main/java/org/craftcore/stellaria/managers/LandBorderParticleManager.java:198-230`（`buildCache`冒頭）

**Interfaces:** N/A（内部バリデーションの追加のみ）

**問題の中身:** `ChunkBorderCommand`（誰でも実行可、専用権限なし）と`LandCommand`の半径パースは`radius < 1`しか弾かず上限が無い。`LandBorderParticleManager.buildCache`は`-radius`〜`radius`の二重forループでキャッシュを作るため、巨大な半径を渡すと実質無限ループに近い負荷がかかりサーバーが固まる。

- [ ] **Step 1: `config.yml`に上限設定を追加する**

`src/main/resources/config.yml`の`border-particle:`セクション（321〜327行目付近）を次のように変更する:

```yaml
  border-particle:
    enabled: true
    particle: "DUST"
    color: "#55FF55"
    size: 1.0
    duration-seconds: 3
    # /land border の常時表示用設定。半径はプレイヤーの現在地を中心にしたチャンク単位。
    toggle-radius-default: 3
    toggle-interval-ticks: 20
    # /chunkborder や /land border <半径> で指定できる半径の上限（チャンク単位）。
    # これを超えるキャッシュ構築要求はサーバー負荷が跳ね上がるため拒否する。
    max-radius: 64
```

- [ ] **Step 2: `ChunkBorderCommand`で半径に上限チェックを追加する**

`commands/ChunkBorderCommand.java:58-69`を次のように置き換える:

```java
        int radius = defaultRadius;
        if (args.length == 2) {
            int maxRadius = plugin.getConfigManager().getInt("land.border-particle.max-radius", 64);
            try {
                radius = Integer.parseInt(args[1]);
                if (radius < 1 || radius > maxRadius) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.getConfigManager().getUsageMessage("chunkborder.usage", player));
                return true;
            }
        }
```

- [ ] **Step 3: `LandCommand.parseBorderRadius`で同じ上限チェックを追加する**

`commands/LandCommand.java:313-324`を次のように置き換える:

```java
    private int parseBorderRadius(Player player, String value) {
        int maxRadius = plugin.getConfigManager().getInt("land.border-particle.max-radius", 64);
        try {
            int radius = Integer.parseInt(value);
            if (radius >= 1 && radius <= maxRadius) {
                return radius;
            }
        } catch (NumberFormatException ignored) {
            // 下のusageへ統一する。
        }
        player.sendMessage(plugin.getConfigManager().getUsageMessage("land.border_usage", player));
        return -1;
    }
```

- [ ] **Step 4: `LandBorderParticleManager.buildCache`にも防御的な上限クランプを入れる**

コマンド層のバリデーションを回避する呼び出し経路が将来増えても暴走しないよう、`managers/LandBorderParticleManager.java`の`buildCache`（198行目付近）の先頭に1行追加する:

```java
    private BorderCache buildCache(LandManager.ChunkKey center, int radius, Mode mode) {
        radius = Math.min(radius, plugin.getConfigManager().getInt("land.border-particle.max-radius", 64));
        // (この後は既存のロジックをそのまま)
```

- [ ] **Step 5: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: コミット**

```bash
git add src/main/resources/config.yml \
        src/main/java/org/craftcore/stellaria/commands/ChunkBorderCommand.java \
        src/main/java/org/craftcore/stellaria/commands/LandCommand.java \
        src/main/java/org/craftcore/stellaria/managers/LandBorderParticleManager.java
git commit -m "fix: 境界パーティクル半径に上限を設け、無制限半径によるフリーズを防ぐ"
```

---

## Task 5: C-4 — シャットダウン時にプレイ時間をflushする

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/PlaytimeManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java:517-524`

**Interfaces:**
- Produces: `PlaytimeManager#flushAll()` — `onDisable()`から呼ぶ、戻り値なし。

**問題の中身:** プレイ時間は`onQuit(Player)`でしか永続化されない。`onDisable()`は`discordBotManager.stop()`→`DatabaseManager.disconnect()`を呼ぶだけで、オンライン中のプレイヤーの`sessionStart`分は同期的にflushされないため、通常の再起動でもプレイ時間が消える。

- [ ] **Step 1: `PlaytimeManager`に`flushAll()`を追加する**

`managers/PlaytimeManager.java`の先頭に`import org.bukkit.Bukkit;`を追加し、`onQuit`メソッド（41〜53行目）の直後に次のメソッドを追加する:

```java
    /**
     * シャットダウン時に呼ぶ。オンライン中の全プレイヤーのセッションを同期的に確定保存する
     * （DB接続が閉じられる前に完了させる必要があるため、onQuitと違い非同期にしない）。
     */
    public void flushAll() {
        for (UUID uuid : List.copyOf(sessionStart.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            Long start = sessionStart.remove(uuid);
            if (start == null) {
                continue;
            }
            long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - start) / 1000L);
            long newPlaytime = getStoredPlaytimeSeconds(uuid) + elapsedSeconds;
            DatabaseManager.update(
                "player_stats",
                Map.of("playtime_seconds", newPlaytime, "last_logout", System.currentTimeMillis()),
                "uuid = ?", uuid.toString()
            );
        }
    }
```

- [ ] **Step 2: `StellariaCore#onDisable()`から`flushAll()`をDB切断前に呼ぶ**

`StellariaCore.java:517-524`を次のように置き換える:

```java
    public void onDisable() {
        if (configManager.getBoolean("discord.bot.enabled",true)){
            discordBotManager.stop();
        }
        playtimeManager.flushAll();
        // プラグイン停止時は Vault から自動解除されるため、DB切断だけでOK
        DatabaseManager.disconnect();
        ConsoleUtil.printDisabledMessage();
    }
```

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/PlaytimeManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "fix: シャットダウン時にオンラインプレイヤーのプレイ時間を同期flushする"
```

---

## Task 6: H-1 — DatabaseManagerの共有Connectionアクセスを直列化する

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/DatabaseManager.java`

**Interfaces:** N/A（メソッドシグネチャは変えず`synchronized`修飾子を追加するのみ）

**問題の中身:** 単一の`static Connection`を、メインスレッド・`Bukkit.getAsyncScheduler()`経由の非同期処理・`transaction()`（`autoCommit`を一時的にfalseへ変更する）が無防備に共有している。`transaction()`実行中に別スレッドから`execute`/`query`等が割り込むと、意図しないコミットやJDBC例外、`autoCommit`状態の不整合が起きうる。

- [ ] **Step 1: 接続を直接操作するメソッドに`synchronized`を追加する**

`managers/DatabaseManager.java`の以下のメソッド宣言に`synchronized`を追加する（`static`の後、戻り値型の前）。`connect`/`disconnect`は既に`synchronized`済みなので変更不要。

```java
    public static synchronized int execute(String sql, Object... params) {
```
（189行目）

```java
    public static synchronized <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
```
（274行目）

```java
    public static synchronized <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
```
（321行目）

```java
    public static synchronized boolean transaction(Consumer<Connection> action) {
```
（370行目）

```java
    public static synchronized void createTableIfNotExists(String table, String... columnDefs) {
```
（138行目）

```java
    public static synchronized void addColumnIfNotExists(String table, String columnDef) {
```
（155行目）

`insert`/`update`/`exists`は内部で`execute`/`queryOne`を呼ぶだけなので変更不要（Javaの`synchronized`はモニター単位で再入可能——同じ`DatabaseManager.class`モニターを保持したスレッドが`execute`から呼ばれた`prepare`等を呼んでもブロックしない）。

- [ ] **Step 2: `executeAsync`/`insertAsync`/`updateAsync`/`queryAsync`/`queryOneAsync`は変更しないことを確認する**

これらは`runAsync(() -> execute(...))`のように、Step 1で`synchronized`化した同期メソッドを非同期スレッドから呼び出すだけの薄いラッパー。呼び出し先が`synchronized`になったことで、複数の非同期呼び出し・同期呼び出しが混在しても接続へのアクセスは自動的に直列化される。コード変更は不要。

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/DatabaseManager.java
git commit -m "fix: DatabaseManagerの主要メソッドをsynchronized化し共有Connectionへのアクセスを直列化する"
```

---

## Task 7: H-2 — HUD/プレースホルダー解決の過剰な同期DBアクセスを削減する

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/PlaceholderManager.java:80-110`
- Modify: `src/main/java/org/craftcore/stellaria/managers/EconomyManager.java`（`getBalance`系、`withdrawPlayer`/`depositPlayer`/`transfer`/`setBalance`）
- Modify: `src/main/java/org/craftcore/stellaria/managers/PlaytimeManager.java`（`getStoredPlaytimeSeconds`、`onQuit`、`flushAll`）

**Interfaces:** N/A（戻り値・シグネチャは変えず内部にキャッシュを追加）

**問題の中身:** `resolveBuiltIn`はテンプレートに`%money%`/`%playtime%`が含まれるか関係なく毎回`getBalance`/`getPlaytimeSeconds`を同期DB問い合わせしている。`TabListManager`/`ScoreboardManager`は全viewer×全targetでこれを呼ぶため、オンライン100人でタブ更新1回あたり概算1万件オーダーの同期SQLiteクエリが発生しうる。

- [ ] **Step 1: `resolveBuiltIn`でテンプレートに無いプレースホルダーの計算をスキップする**

`managers/PlaceholderManager.java:80-110`を次のように置き換える:

```java
    private String resolveBuiltIn(String template, Player player, Player viewer) {
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();

        String money = "";
        if (template.contains("%money%")) {
            money = shouldHideBalance(player, viewer)
                    ? plugin.getConfigManager().getMessage("profile.balance_hidden", player)
                    : plugin.getEconomyManager().format(plugin.getEconomyManager().getBalance(player));
        }
        String playtime = template.contains("%playtime%")
                ? DurationParser.formatDuration(plugin.getPlaytimeManager().getPlaytimeSeconds(player.getUniqueId()))
                : "";

        String worldDisplayName = WorldNameUtil.displayName(plugin.getConfigManager(), player.getWorld());

        return template
                .replace("%afk%", resolveAfkTag(player))
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max_online%", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("%tps%", String.format(Locale.ROOT, "%.1f", Math.min(20.0, Bukkit.getTPS()[0])))
                .replace("%ping%", String.valueOf(player.getPing()))
                .replace("%world%", worldDisplayName)
                .replace("%server%", plugin.getConfigManager().getString("server.name", ""))
                .replace("%x%", String.valueOf(x))
                .replace("%y%", String.valueOf(y))
                .replace("%z%", String.valueOf(z))
                .replace("%location%", worldDisplayName + " (" + x + ", " + y + ", " + z + ")")
                .replace("%date%", LocalDateTime.now().format(DATE_FORMAT))
                .replace("%time%", LocalDateTime.now().format(TIME_FORMAT))
                .replace("%money%", money)
                .replace("%playtime%", playtime)
                .replace("%health%", trimTrailingZero(player.getHealth() / 2.0))
                .replace("%max_health%", trimTrailingZero(player.getMaxHealth() / 2.0));
    }
```

- [ ] **Step 2: `EconomyManager.getBalance`に短TTLのインメモリキャッシュを追加する**

`managers/EconomyManager.java`の先頭に`import java.util.concurrent.ConcurrentHashMap;`を追加し、クラスフィールドに以下を追加する（`private final StellariaCore plugin;`の直後）:

```java
    private static final long BALANCE_CACHE_TTL_MILLIS = 2000;
    private final Map<UUID, long[]> balanceCache = new ConcurrentHashMap<>(); // [coins, cachedAtMillis]
```

`getBalance(OfflinePlayer player)`（74〜83行目）を次のように置き換える:

```java
    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long[] cached = balanceCache.get(uuid);
        if (cached != null && now - cached[1] < BALANCE_CACHE_TTL_MILLIS) {
            return cached[0];
        }
        Long coins = DatabaseManager.queryOne(
            "SELECT coins FROM players WHERE uuid = ?",
            rs -> rs.getLong("coins"),
            uuid.toString()
        );
        long value = (coins != null) ? coins : 0;
        balanceCache.put(uuid, new long[]{value, now});
        return value;
    }
```

書き込み系メソッドの最後（DB更新の直後）でキャッシュを無効化する。`withdrawPlayer(OfflinePlayer, double)`・`depositPlayer(OfflinePlayer, double)`・`transfer(...)`・`setBalance(...)`それぞれの`DatabaseManager.execute(...)`呼び出しの直後に1行追加する:

```java
        balanceCache.remove(player.getUniqueId());
```

（`transfer`の場合は`from`と`to`両方に対して`balanceCache.remove(from.getUniqueId());`・`balanceCache.remove(to.getUniqueId());`を、トランザクションのラムダ内・各UPDATEの直後に追加する。）

- [ ] **Step 3: `PlaytimeManager.getStoredPlaytimeSeconds`に短TTLキャッシュを追加する**

`managers/PlaytimeManager.java`の先頭に`import java.util.concurrent.ConcurrentHashMap;`を追加し、クラスフィールドに以下を追加する:

```java
    private static final long PLAYTIME_CACHE_TTL_MILLIS = 5000;
    private final Map<UUID, long[]> storedPlaytimeCache = new ConcurrentHashMap<>(); // [seconds, cachedAtMillis]
```

`getStoredPlaytimeSeconds`を次のように置き換える:

```java
    private long getStoredPlaytimeSeconds(UUID uuid) {
        long now = System.currentTimeMillis();
        long[] cached = storedPlaytimeCache.get(uuid);
        if (cached != null && now - cached[1] < PLAYTIME_CACHE_TTL_MILLIS) {
            return cached[0];
        }
        Long value = DatabaseManager.queryOne(
            "SELECT playtime_seconds FROM player_stats WHERE uuid = ?",
            rs -> rs.getLong("playtime_seconds"),
            uuid.toString()
        );
        long seconds = value != null ? value : 0;
        storedPlaytimeCache.put(uuid, new long[]{seconds, now});
        return seconds;
    }
```

`onQuit`と（Task 5で追加した）`flushAll`のDB書き込み直後に、それぞれ`storedPlaytimeCache.remove(uuid);`を追加する。

- [ ] **Step 4: 残る構造的な問題を記録しておく（このタスクでは直さない）**

`TabListManager`/`ScoreboardManager`のtickが全viewer×全targetでプレースホルダーを解決するN²構造自体はこのタスクでは変更しない。キャッシュ追加により`%money%`/`%playtime%`を含むテンプレートでのDB負荷は「同期クエリ1回」から「2〜5秒に1回」まで大きく減るが、根本的なtick設計の見直しは別タスクとする。

- [ ] **Step 5: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/PlaceholderManager.java \
        src/main/java/org/craftcore/stellaria/managers/EconomyManager.java \
        src/main/java/org/craftcore/stellaria/managers/PlaytimeManager.java
git commit -m "perf: プレースホルダー解決の不要なDB問い合わせを削減し、残高/プレイ時間に短TTLキャッシュを追加"
```

---

## Task 8: H-3 & H-5 — MuteManagerのスレッド安全性とDB順序逆転を修正

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/MuteManager.java`

**Interfaces:** N/A（`mute`/`unmute`/`getRecord`等のシグネチャは変更なし）

**問題の中身:**
- (H-3) `cache`が素の`HashMap`で、`ChatListener`の`AsyncChatEvent`ハンドラ（非同期スレッド）から`getRestrictingRecord`経由で読まれる一方、`/mute`コマンド（メインスレッド）や期限切れ自動失効から書かれる。非スレッドセーフな`HashMap`への並行読み書きは未定義動作（`ConcurrentModificationException`やデータ破損の恐れ）。
- (H-5) `mute()`/`unmuteInternal()`はキャッシュを即座に変更してからDB書き込みを`Bukkit.getAsyncScheduler().runNow(...)`で非同期投入する。この非同期タスクは投入順とスレッドプール上の実行順が一致する保証が無いため、「mute → 期限切れ自動unmute」のような短時間の連続操作でDB書き込みが逆順に実行されうる（再muteした直後にDB行だけ消える等）。

- [ ] **Step 1: `cache`を`ConcurrentHashMap`にする**

`managers/MuteManager.java`の先頭で`import java.util.HashMap;`を`import java.util.concurrent.ConcurrentHashMap;`に変更する（他で`HashMap`を使っていなければ）。39行目を次のように変更する:

```java
    private final Map<UUID, MuteRecord> cache = new ConcurrentHashMap<>();
```

- [ ] **Step 2: `mute`/`unmuteInternal`のDB書き込みを同期実行にし、書き込み順序をキャッシュ変更順と一致させる**

mute/unmuteはモデレーターの操作起点であり、チャット等のホットパスではないため、非同期化による恩恵より順序保証の方が重要。`managers/MuteManager.java:97-113`を次のように置き換える:

```java
    /** ミュートを設定する（既存のミュートは上書き）。DB書き込みは同期（順序保証のため）。 */
    public void mute(UUID uuid, int level, long expiresAt, String reason, String mutedBy) {
        long mutedAt = System.currentTimeMillis();
        cache.put(uuid, new MuteRecord(level, expiresAt, reason, mutedBy, mutedAt));
        DatabaseManager.execute(
            "INSERT OR REPLACE INTO mutes (uuid, level, expires_at, reason, muted_by, muted_at) VALUES (?, ?, ?, ?, ?, ?)",
            uuid.toString(), level, expiresAt, reason, mutedBy, mutedAt
        );
    }

    public void unmute(UUID uuid) {
        unmuteInternal(uuid);
    }

    private void unmuteInternal(UUID uuid) {
        cache.remove(uuid);
        DatabaseManager.execute("DELETE FROM mutes WHERE uuid = ?", uuid.toString());
    }
```

Task 6で`DatabaseManager.execute`は`synchronized`化されているため、これらの呼び出しはメインスレッドをブロックしうるが、SQLiteへの単発UPDATE/DELETEは局所的で、mute/unmuteの頻度自体が低いため許容する。

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/MuteManager.java
git commit -m "fix: MuteManagerのキャッシュをConcurrentHashMap化し、DB書き込みを同期化して順序を保証する"
```

---

## Task 9: H-6 — TpaCoreのリクエストリークを修正

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/commands/tpa/TpaCore.java:54-93`

**Interfaces:** N/A（`resetPlayerTeleportRequests`のシグネチャは変更なし）

**問題の中身:** `tpaPendingSender`/`tpHerePendingSender`は「送信者UUID → 送信先UUID」のマップ。Bが退出したとき`resetPlayerTeleportRequests(B)`は`tpaPendingSender.remove(B)`（Bが送信者だった場合のみ）しか行わず、AがBに送っていたリクエスト（`tpaPendingSender`の**キーがA、値がB**のエントリ）は消えない。結果、Aの`tpaPendingSender.containsKey(A)`が`true`のまま残り続け、Aは以後`/tpa`を一切送信できなくなる（自分が再ログインするまで）。`tpRequest`/`tpHere`（受信箱、キー=受信者・値=送信者リスト）についても、退出したプレイヤーが他プレイヤーの受信箱に送信者として残り続ける同種の問題がある。

- [ ] **Step 1: `resetPlayerTeleportRequests`の末尾に、他プレイヤー視点の後始末を追加する**

`commands/tpa/TpaCore.java:84-93`を次のように置き換える（Step冒頭の`UUID playerId = ...`から57〜83行目までの既存コードは変更しない）:

```java
        tpRequest.remove(playerId);
        tpHere.remove(playerId);
        tpaPendingSender.remove(playerId);
        tpHerePendingSender.remove(playerId);
        ScheduledTask task = pendingTeleport.remove(playerId);
        if (task != null) task.cancel();
        ScheduledTask countdownTask = pendingCountdown.remove(playerId);
        if (countdownTask != null) countdownTask.cancel();

        // playerId宛て（destination）に送信中だった他プレイヤーの送信状態を解除する
        // （tpaPendingSender/tpHerePendingSenderは 送信者UUID -> 送信先UUID のマップなので、
        //   playerIdをキーで消すだけでは「playerId宛てに送っていた別の誰か」は消えない）
        tpaPendingSender.values().removeIf(destination -> destination.equals(playerId));
        tpHerePendingSender.values().removeIf(destination -> destination.equals(playerId));

        // playerIdが送信者として残っている、他プレイヤーの受信箱（inbox）エントリも除去する
        for (List<UUID> requesters : tpRequest.values()) {
            requesters.remove(playerId);
        }
        for (List<UUID> requesters : tpHere.values()) {
            requesters.remove(playerId);
        }
    }
```

- [ ] **Step 2: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/commands/tpa/TpaCore.java
git commit -m "fix: プレイヤー退出時にTPAリクエストの逆参照エントリもすべて解除しリークを防ぐ"
```

---

## Task 10: H-8 — Kikori/Mineの pass が木/鉱脈まるごとバイパスできる問題を修正

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/KikoriManager.java:226-235`
- Modify: `src/main/java/org/craftcore/stellaria/managers/MineManager.java:225-234`

**Interfaces:** N/A（内部ロジックのみ）

**問題の中身:** `/kikori pass`（`/mine pass`）は「人工物1個だけバイパスする」想定だが、BFS走査中に人工物を検知するたび`if (passAvailable) { passUsed = true; }`しているだけで、`passAvailable`を`false`に戻していない。そのため1回のpassで同一伐採/採掘操作中に何個でも人工物をバイパスできてしまう。

- [ ] **Step 1: `KikoriManager.tryStartFelling`でpass使用後に`passAvailable`をfalseに戻す**

`managers/KikoriManager.java:226-235`を次のように置き換える:

```java
            if (!current.equals(origin) && isArtificialLog(current)) {
                if (passAvailable) {
                    passAvailable = false;
                    passUsed = true;
                } else {
                    aborted = true;
                    break;
                }
            }
```

- [ ] **Step 2: `MineManager.tryStartMining`で同じ修正を行う**

`managers/MineManager.java:225-234`を次のように置き換える:

```java
            if (!current.equals(origin) && isArtificialOre(current)) {
                if (passAvailable) {
                    passAvailable = false;
                    passUsed = true;
                } else {
                    aborted = true;
                    break;
                }
            }
```

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/KikoriManager.java src/main/java/org/craftcore/stellaria/managers/MineManager.java
git commit -m "fix: kikori/mineのpassを人工物1個だけの一時バイパスに制限する"
```

---

## Task 11: H-9 — 人工物PDCタグがキャンセルされた設置イベントでも記録されてしまう問題を修正

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/listeners/KikoriListener.java:29-35`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/MineListener.java:28-34`

**Interfaces:** N/A（イベントハンドラのアノテーション追加のみ）

**問題の中身:** `onBlockPlace`に`@EventHandler(ignoreCancelled = true)`が付いていない（`onBlockBreak`側には付いている）。保護プラグイン等によって設置がキャンセルされた`BlockPlaceEvent`でも`markArtificialLog`/`markArtificialOre`が呼ばれ、実際には置かれていない座標が人工物としてタグ付けされてしまう。

- [ ] **Step 1: `KikoriListener.onBlockPlace`に`ignoreCancelled = true`を追加する**

`listeners/KikoriListener.java:29-35`を次のように置き換える:

```java
    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (TreeUtil.isLog(block.getType())) {
            plugin.getKikoriManager().markArtificialLog(block);
        }
    }
```

- [ ] **Step 2: `MineListener.onBlockPlace`に同じ修正を行う**

`listeners/MineListener.java:28-34`を次のように置き換える:

```java
    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (OreUtil.isOre(block.getType())) {
            plugin.getMineManager().markArtificialOre(block);
        }
    }
```

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/listeners/KikoriListener.java src/main/java/org/craftcore/stellaria/listeners/MineListener.java
git commit -m "fix: キャンセルされたBlockPlaceEventで人工物タグが誤って付く問題を修正"
```

---

## Task 12: H-11 — LandManagerでDB書き込み前にキャッシュを変更している箇所をトランザクション順に修正

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/LandManager.java:296-320,327-356,564-602`

**Interfaces:** N/A（内部ロジックのみ、`ActionResult`の意味は変更なし）

**問題の中身:** `mergeAreas`（166行目付近の既存コメントで「トランザクション保護されていない」と自認）、`unclaim`（`claimsByChunk.remove`がDB DELETEより先）、`trust`/`untrust`（in-memory Setの変更がDB INSERT/DELETEより先）のいずれも、DB書き込みが失敗した場合にメモリ上の保護状態とDBの内容が食い違う。

- [ ] **Step 1: `unclaim`をDB削除→成否確認→キャッシュ更新の順に変更する**

`managers/LandManager.java:327-356`を次のように置き換える:

```java
    public ActionResult unclaim(Player player, boolean adminOverride) {
        ChunkKey key = ChunkKey.of(player.getLocation());
        Claim claim = claimsByChunk.get(key);
        if (claim == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (!adminOverride && !claim.owner().equals(player.getUniqueId())) {
            return ActionResult.NOT_OWNER;
        }

        int affected = DatabaseManager.execute("DELETE FROM land_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                key.world(), key.chunkX(), key.chunkZ());
        if (affected <= 0) {
            return ActionResult.NOT_CLAIMED;
        }

        claimsByChunk.remove(key);
        claimsVersion++;

        if (plugin.getConfigManager().getBoolean("land.refund-on-unclaim", true)) {
            double cost = plugin.getConfigManager().getDouble("land.cost-per-chunk", 500);
            plugin.getEconomyManager().depositPlayer(Bukkit.getOfflinePlayer(claim.owner()), cost);
        }

        boolean areaStillUsed = claimsByChunk.values().stream()
                .anyMatch(c -> c.areaId().equals(claim.areaId()));
        if (!areaStillUsed) {
            areas.remove(claim.areaId());
            DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ?", claim.areaId());
            DatabaseManager.execute("DELETE FROM land_territories WHERE territory_id = ?", claim.areaId());
        }

        return ActionResult.SUCCESS;
    }
```

- [ ] **Step 2: `trust`/`untrust`をDB書き込み→成否確認→Set更新の順に変更する**

`managers/LandManager.java:564-602`を次のように置き換える:

```java
    /** 現在地のエリアに信頼プレイヤーを追加する。実行者がオーナーである必要がある。自分自身は追加できない。 */
    public ActionResult trust(Player owner, UUID target) {
        if (target.equals(owner.getUniqueId())) {
            return ActionResult.SELF_TARGET;
        }
        Claim claim = claimsByChunk.get(ChunkKey.of(owner.getLocation()));
        ActionResult error = validateOwnedClaim(claim, owner);
        if (error != null) {
            return error;
        }
        Area area = areas.get(claim.areaId());
        if (area == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (!area.trusted.contains(target)) {
            int inserted = DatabaseManager.insert("land_trusts", Map.of(
                    "territory_id", claim.areaId(),
                    "trusted_uuid", target.toString()
            ));
            if (inserted > 0) {
                area.trusted.add(target);
            }
        }
        return ActionResult.SUCCESS;
    }

    /** 現在地のエリアから信頼プレイヤーを外す。実行者がオーナーである必要がある。 */
    public ActionResult untrust(Player owner, UUID target) {
        Claim claim = claimsByChunk.get(ChunkKey.of(owner.getLocation()));
        ActionResult error = validateOwnedClaim(claim, owner);
        if (error != null) {
            return error;
        }
        Area area = areas.get(claim.areaId());
        if (area == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (area.trusted.contains(target)) {
            int deleted = DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ? AND trusted_uuid = ?",
                    claim.areaId(), target.toString());
            if (deleted > 0) {
                area.trusted.remove(target);
            }
        }
        return ActionResult.SUCCESS;
    }
```

- [ ] **Step 3: `mergeAreas`を`DatabaseManager.transaction`で包み、成功後のみキャッシュを更新する**

`managers/LandManager.java:296-320`（`mergeAreas`メソッド全体）を次のように置き換える:

```java
    /** mergedIdの全claim・信頼リストをcanonicalIdへ付け替え、mergedId側のエリアは削除する。 */
    private void mergeAreas(String mergedId, String canonicalId) {
        Area canonical = areas.get(canonicalId);
        Area merged = areas.get(mergedId);

        boolean success = DatabaseManager.transaction(conn -> {
            DatabaseManager.execute("UPDATE land_claims SET territory_id = ? WHERE territory_id = ?", canonicalId, mergedId);
            if (merged != null) {
                for (UUID trustedUuid : merged.trusted) {
                    DatabaseManager.execute(
                            "INSERT OR IGNORE INTO land_trusts (territory_id, trusted_uuid) VALUES (?, ?)",
                            canonicalId, trustedUuid.toString());
                }
            }
            DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ?", mergedId);
            DatabaseManager.execute("DELETE FROM land_territories WHERE territory_id = ?", mergedId);
        });

        if (!success) {
            plugin.getLogger().severe("エリアのマージに失敗しました（DBはロールバック済み、キャッシュは変更していません）: " + mergedId + " -> " + canonicalId);
            return;
        }

        areas.remove(mergedId);
        if (merged != null) {
            canonical.trusted.addAll(merged.trusted);
        }
        for (Map.Entry<ChunkKey, Claim> entry : claimsByChunk.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.areaId().equals(mergedId)) {
                entry.setValue(claim.withAreaId(canonicalId));
            }
        }
    }
```

- [ ] **Step 4: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/LandManager.java
git commit -m "fix: LandManagerのclaim/trust/mergeをDB成功確認後にキャッシュ更新する順序へ変更"
```

---

## Task 13: H-13 — Discord Bot起動のブロッキングとスレッド安全性を修正

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/DiscordBotManager.java:54,63-93`

**Interfaces:** N/A（`startBot()`の呼び出し側シグネチャは変更なし。ただし起動完了が非同期になるため、呼び出し直後にBotが必ず使える状態とは限らなくなる点に注意——既存の呼び出し元は`startBot()`を`onEnable`から呼んで結果を待たない設計であることを確認済み）

**問題の中身:** `jda.awaitReady()`がメインスレッド（`onEnable`）を、Discordゲートウェイとのハンドシェイクが終わるまでブロックする。ネットワーク遅延時にサーバー起動そのものが詰まる。また`webhookUrls`が素の`HashMap`で、メインスレッド（`initializeWebhooks`）と非同期chatスレッド（`mcChatToDiscord`）から並行アクセスされる。

- [ ] **Step 1: `webhookUrls`を`ConcurrentHashMap`にする**

`managers/DiscordBotManager.java`の`import`に`java.util.concurrent.ConcurrentHashMap;`を追加し、54行目を次のように変更する:

```java
    private final Map<String, String> webhookUrls = new ConcurrentHashMap<>();
```

- [ ] **Step 2: `startBot()`のJDA接続確立以降を非同期化する**

`managers/DiscordBotManager.java:63-93`を次のように置き換える:

```java
    public void startBot() {
        token = plugin.getConfigManager().getString("discord.bot.token", "");
        if (token.isEmpty()) {
            plugin.getLogger().warning("botのtokenが指定されていません。");
            return;
        }
        try {
            jda = JDABuilder.createDefault(token)
                    .addEventListeners(new DiscordListener(plugin))
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .build();
        } catch (Exception e) {
            plugin.getLogger().warning("DiscordBotの起動に失敗しました: " + e.getMessage());
            jda = null;
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                jda.awaitReady();
                initializeWebhooks();
                registerAdminCommands();
                registerPublicCommands();
                startPresenceUpdates();
                sendStartupLog();
                plugin.getLogger().info("DiscordBotを起動しました。");
            } catch (Exception e) {
                plugin.getLogger().warning("DiscordBotの起動に失敗しました: " + e.getMessage());
                if (presenceTask != null) {
                    presenceTask.cancel();
                    presenceTask = null;
                }
                if (jda != null) {
                    jda.shutdown();
                    jda = null;
                }
            }
        });
    }
```

`import org.bukkit.Bukkit;`が未importの場合は`managers/DiscordBotManager.java`の先頭に追加する（`startPresenceUpdates()`が既に`Bukkit.getGlobalRegionScheduler()`を使っているため、多くの場合既にimport済みのはず——確認すること）。

`initializeWebhooks`/`registerAdminCommands`/`registerPublicCommands`/`startPresenceUpdates`/`sendStartupLog`はいずれもJDA(Discord)のAPI呼び出しのみで、`startPresenceUpdates`内の`Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)`はPaperのスケジューラAPIとしてどのスレッドから呼んでも安全（タスク自体は登録後グローバルスレッドで実行される）ため、非同期スレッドから呼び出しても問題ない。

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/DiscordBotManager.java
git commit -m "fix: Discord Bot起動をプラグインのonEnableをブロックしない非同期処理にする"
```

---

## Task 14: H-14 — /timevote・/weathervoteのコンソール実行時ClassCastExceptionを修正

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/commands/TimeVoteCommand.java:103`
- Modify: `src/main/java/org/craftcore/stellaria/commands/WeatherVoteCommand.java:105`
- Modify: `src/main/resources/messages.yml`（`timevote:`/`weathervote:`セクション、無ければ追加）

**Interfaces:** N/A（`onCommand`内部のガード追加のみ）

**問題の中身:** どちらも`onCommand`冒頭で`Player player = (Player) sender;`と無条件キャストしており、コンソール/RCONから実行すると`ClassCastException`で例外を吐く。同種の他コマンド（`ChunkBorderCommand`等）は既に`if (!(sender instanceof Player player)) { ... return true; }`パターンでガードしている。

- [ ] **Step 1: `messages.yml`に`must_be_player`メッセージを追加する（無ければ）**

`grep -n "timevote:\|weathervote:" src/main/resources/messages.yml`で既存セクションを確認し、無ければ追加する。`timevote:`セクションに:

```yaml
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
```

`weathervote:`セクションにも同様に追加する。既存キーとして既にあればこのStepは不要（`ChunkBorderCommand`用の`chunkborder.must_be_player`とは別セクションなので、`timevote`/`weathervote`それぞれの配下に無いか確認すること）。

- [ ] **Step 2: `TimeVoteCommand.onCommand`にガードを追加する**

`commands/TimeVoteCommand.java:102-104`を次のように置き換える:

```java
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("timevote.must_be_player", null));
            return true;
        }
```

- [ ] **Step 3: `WeatherVoteCommand.onCommand`にガードを追加する**

`commands/WeatherVoteCommand.java:104-106`を次のように置き換える:

```java
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("weathervote.must_be_player", null));
            return true;
        }
```

- [ ] **Step 4: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/commands/TimeVoteCommand.java \
        src/main/java/org/craftcore/stellaria/commands/WeatherVoteCommand.java \
        src/main/resources/messages.yml
git commit -m "fix: /timevote・/weathervoteをコンソールから実行した際のClassCastExceptionを修正"
```

---

## Task 15: 手動E2E確認（runServer）

**Files:** なし（動作確認のみ）

- [ ] **Step 1: テストサーバーを起動する**

Run: `./gradlew runServer`

- [ ] **Step 2: C-1を確認する**

`config.yml`で`action-bar.persistent.enabled: true`にしてサーバーを再起動し、コンソールに例外(NPE)が出ないこと、対象プレイヤーにアクションバーが表示され続けることを確認する。

- [ ] **Step 3: C-2/H-12を確認する**

`/sethome`・`/setwarp`・`/land claim`・アドミンショップでの購入を、残高が足りるギリギリのラインで試し、成功/失敗が正しく判定されること、失敗時に課金・作成物が発生しないことを確認する。アドミンショップはインベントリを満杯にした状態で購入を試み、課金されずキャンセルされることを確認する。

- [ ] **Step 4: C-3を確認する**

`/chunkborder on 999999`のような極端な値を試し、usageメッセージで弾かれること（サーバーがフリーズしないこと）を確認する。`/chunkborder on 10`のような妥当な値は従来通り動作することを確認する。

- [ ] **Step 5: C-4を確認する**

プレイヤーとしてログインしてしばらく待ち、`/stop`（またはCtrl+C）でサーバーを止めた後、再起動して`/playtime`（または該当コマンド）でプレイ時間が保存されていることを確認する。

- [ ] **Step 6: H-6を確認する**

A・B2アカウントで、Aが`/tpa B`→ Bがログアウト → Aが再度`/tpa`を送れることを確認する（修正前はここで送信不可になっていた）。

- [ ] **Step 7: H-8を確認する**

人工的に設置した丸太を2本以上含む木を作り、`/kikori pass`を1回使って伐採し、2本目の人工丸太で伐採が中断される（`kikori.artificial_detected`警告が出る）ことを確認する。

- [ ] **Step 8: H-13を確認する**

Discord連携が有効な設定でサーバーを起動し、起動処理がDiscordの応答を待たずに完了する（コンソールのプラグイン起動ログが早く出る）こと、その後Botがオンラインになりコマンド登録・Webhook中継が動くことを確認する。

- [ ] **Step 9: H-14を確認する**

サーバーコンソールから`/timevote`・`/weathervote`を実行し、例外が出ずにガードメッセージが表示されることを確認する。

- [ ] **Step 10: 最終コミット確認**

Run: `git log --oneline -15` で、Task 1〜14の各コミットが揃っていることを確認する。
