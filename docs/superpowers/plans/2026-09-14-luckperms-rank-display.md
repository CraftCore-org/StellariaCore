# LuckPermsランク表示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LuckPerms上のグループ所属（admin/mod/booster）を読み取り、チャット（送信者名前のランク色付き`"| "`＋ホバーツールチップへのランク名追加）とタブリスト（名前の前のランクタグ）にランクを表示する。

**Architecture:** 新設の`RankManager`が唯一LuckPerms APIに触れる場所になり、`ChatListener`と`TabListManager`はそこから`RankInfo`を受け取って表示に反映するだけにする。LuckPerms未導入でも例外を出さず「ランク無し」表示にフォールバックする（Vaultと同じ「無くても壊れない」パターン）。

**Tech Stack:** Java 21 / Paper API 1.21 / LuckPerms API 5.4（`compileOnly`、Maven Central）

**Spec:** `docs/superpowers/specs/2026-09-14-luckperms-rank-display-design.md`

## Global Constraints

- LuckPermsは`softdepend`（`depend`にしない）。未導入でもプラグイン全体が正常に起動・動作すること。
- ランクの優先順位は`config.yml`の`rank.groups`リストの並び順（上ほど優先）。
- ランク無し（デフォルト）の色は`&%7`。
- このリポジトリに自動テストは無い（`src/test`無し）。各タスクの自動検証は`./gradlew compileJava`。目視確認が要るタスクにはその手順を明記する。
- 全てのユーザー向け文言・設定は既存の`ConfigManager`経由で読む（`plugin.getConfig()`直呼び禁止）。

---

### Task 1: LuckPerms依存の追加

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/plugin.yml`

**Interfaces:**
- Consumes: なし
- Produces: `net.luckperms.api.LuckPerms`等のAPIクラスがコンパイル時に解決できるようになる（実行時のjarはサーバー側のLuckPermsプラグインが提供）

- [ ] **Step 1: build.gradle.ktsにLuckPerms APIを追加**

`build.gradle.kts`の`dependencies`ブロックを次のように変更する（末尾に1行追加、リポジトリ追加は不要 = Maven Central既存分でカバーされる）:

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    compileOnly("net.citizensnpcs:citizensapi:2.0.35-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.9")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("net.luckperms:api:5.4")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    
}
```

- [ ] **Step 2: plugin.ymlにsoftdependを追加**

`plugin.yml`の`depend: [Citizens, packetevents, Vault, PlaceholderAPI]`の直後に1行追加する:

```yaml
depend: [Citizens, packetevents, Vault, PlaceholderAPI]
softdepend: [LuckPerms]
```

- [ ] **Step 3: ビルド確認**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`（この時点ではまだLuckPermsのAPIクラスをどこからも参照していないので、依存関係解決だけの確認）

- [ ] **Step 4: コミット**

```bash
git add build.gradle.kts src/main/resources/plugin.yml
git commit -m "build: add LuckPerms API as a soft dependency"
```

---

### Task 2: config.ymlへのrank設定追加とConfigManagerの拡張

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/java/org/craftcore/stellaria/managers/ConfigManager.java`

**Interfaces:**
- Consumes: なし
- Produces: `ConfigManager.getMapList(String path)` → `List<Map<?, ?>>`（Task 3の`RankManager`が`rank.groups`を読むのに使う）

- [ ] **Step 1: config.ymlにrankセクションを追加**

`src/main/resources/config.yml`の`economy:`ブロックの直前（`discord:`ブロックの直後）に、次のブロックを挿入する。挿入位置の目印として、既存の該当箇所は:

```yaml
discord:
  invite: "https://discord.gg/xxxxx" # /discord で案内するサーバー招待リンク

economy:
  default-balance: 1000 # 新規プレイヤーの初期所持金
```

これを次のように変更する（`rank:`ブロックを`discord:`と`economy:`の間に挿入）:

```yaml
discord:
  invite: "https://discord.gg/xxxxx" # /discord で案内するサーバー招待リンク

rank:
  enabled: true
  default:
    color: "&%7"
  groups: # 上に書いた方が優先。luckperms-group はLuckPerms側の実グループ名
    - luckperms-group: "admin"
      color: "&%e"
      tablist-tag: "ᴀᴅᴍɪɴ"
      display-name: "管理者"
    - luckperms-group: "mod"
      color: "&%b"
      tablist-tag: "sᴛᴀꜰꜰ"
      display-name: "スタッフ"
    - luckperms-group: "booster"
      color: "&%d"
      tablist-tag: "ʙᴏᴏsᴛᴇʀ"
      display-name: "ブースター"

economy:
  default-balance: 1000 # 新規プレイヤーの初期所持金
```

- [ ] **Step 2: ConfigManagerにgetMapListを追加**

`src/main/java/org/craftcore/stellaria/managers/ConfigManager.java`の`getStringList`メソッドの直後（`getDouble`メソッドの前）に、次のメソッドを追加する:

```java
    /**
     * config.yml のマップリスト設定を取得する（例: rank.groups のような、複数キーを持つ
     * オブジェクトのリスト）。存在しない場合は空リスト。
     */
    public List<Map<?, ?>> getMapList(String path) {
        return get("config.yml").get().getMapList(path);
    }
```

`ConfigManager.java`の先頭のimportに`java.util.Map`が無ければ追加する（現状`java.util.LinkedHashMap`と`java.util.Map`は既にimport済みのはずなので、`import java.util.List;`の並びを確認するだけでよい）。

- [ ] **Step 3: ビルド確認**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: コミット**

```bash
git add src/main/resources/config.yml src/main/java/org/craftcore/stellaria/managers/ConfigManager.java
git commit -m "feat: add rank.* config schema and ConfigManager#getMapList"
```

---

### Task 3: RankManagerの新設とStellariaCoreへの組み込み

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/RankManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `ConfigManager.getBoolean/getString/getMapList`（Task 2で追加済み）
- Produces:
  - `RankManager.RankInfo`: `record RankInfo(String color, String tablistTag, String displayName)`（`tablistTag`/`displayName`はランク無しの場合は空文字`""`）
  - `RankManager#getRank(Player player)` → `RankInfo`（例外を投げない。LuckPerms未導入・rank.enabled=false・該当グループ無しは全て`RankInfo("&%7", "", "")`を返す）
  - `RankManager#reload()` → `void`（`config.yml`の`rank.*`を読み直す）
  - `StellariaCore#getRankManager()` → `RankManager`

- [ ] **Step 1: RankManager.javaを作成**

`src/main/java/org/craftcore/stellaria/managers/RankManager.java`を新規作成する:

```java
package org.craftcore.stellaria.managers;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LuckPermsのグループ所属を読み取り、config.yml の rank.* で定義した表示情報
 * （色・タブリストタグ・表示名）に変換する。LuckPerms API に触れるのはこのクラスだけにして、
 * ChatListener/TabListManager はここから RankInfo を受け取るだけにする。
 * LuckPermsが無くても（未導入 or rank.enabled=false）例外を出さず、常にデフォルトを返す。
 */
public class RankManager {

    /** 1グループ分の表示情報。tablistTag/displayNameが空文字ならランク無し扱い。 */
    public record RankInfo(String color, String tablistTag, String displayName) {
    }

    private record RankDefinition(String luckpermsGroup, RankInfo info) {
    }

    private final StellariaCore plugin;
    private LuckPerms luckPerms;
    private boolean enabled;
    private RankInfo defaultRank = new RankInfo("&%7", "", "");
    private List<RankDefinition> definitions = new ArrayList<>();

    public RankManager(StellariaCore plugin) {
        this.plugin = plugin;
        connectLuckPerms();
        reload();
    }

    /** LuckPermsプラグインが実際に導入されているか確認してから、ServicesManager経由でAPIを取得する。
     * 未導入なら luckPerms は null のままにし、以後 getRank は常にデフォルトを返す。 */
    private void connectLuckPerms() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().warning("LuckPerms が見つかりませんでした。ランク表示機能は無効化されます。");
            this.luckPerms = null;
            return;
        }
        this.luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
        if (this.luckPerms == null) {
            plugin.getLogger().warning("LuckPerms のAPI取得に失敗しました。ランク表示機能は無効化されます。");
        }
    }

    /** config.yml の rank.* を読み直す。/stellariareload から呼ばれる想定。 */
    public void reload() {
        ConfigManager config = plugin.getConfigManager();
        this.enabled = config.getBoolean("rank.enabled", true);
        this.defaultRank = new RankInfo(config.getString("rank.default.color", "&%7"), "", "");

        List<RankDefinition> loaded = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("rank.groups")) {
            Object group = entry.get("luckperms-group");
            if (!(group instanceof String groupName) || groupName.isBlank()) {
                plugin.getLogger().warning("rank.groups に luckperms-group の無いエントリがあるため、スキップします: " + entry);
                continue;
            }
            String color = String.valueOf(entry.getOrDefault("color", "&%7"));
            String tag = String.valueOf(entry.getOrDefault("tablist-tag", ""));
            String displayName = String.valueOf(entry.getOrDefault("display-name", groupName));
            loaded.add(new RankDefinition(groupName, new RankInfo(color, tag, displayName)));
        }
        this.definitions = loaded;
    }

    /**
     * プレイヤーのランク情報を返す。LuckPerms未導入・機能無効・該当グループ無しは
     * すべて {@code rank.default.color} をcolorに持つ「ランク無し」のRankInfoを返す（例外を投げない）。
     */
    public RankInfo getRank(Player player) {
        if (!enabled || luckPerms == null) {
            return defaultRank;
        }

        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return defaultRank;
        }

        QueryOptions options = luckPerms.getContextManager().getQueryOptions(player);
        Set<String> groupNames = user.getInheritedGroups(options).stream()
                .map(Group::getName)
                .collect(Collectors.toSet());

        for (RankDefinition definition : definitions) {
            if (groupNames.contains(definition.luckpermsGroup())) {
                return definition.info();
            }
        }
        return defaultRank;
    }
}
```

- [ ] **Step 2: StellariaCoreにフィールド・getter・生成処理を追加**

`src/main/java/org/craftcore/stellaria/StellariaCore.java`のフィールド宣言部分:

```java
    private PlaytimeManager playtimeManager;
```

の直後に1行追加する:

```java
    private PlaytimeManager playtimeManager;
    private RankManager rankManager;
```

`onEnable()`内、`this.playtimeManager = new PlaytimeManager(this);`の直後に1行追加する:

```java
        this.afkManager = new AfkManager(this);
        this.playtimeManager = new PlaytimeManager(this);
        this.rankManager = new RankManager(this);
```

getterを`getPlaytimeManager()`の直後に追加する:

```java
    public PlaytimeManager getPlaytimeManager() {
        return this.playtimeManager;
    }

    public RankManager getRankManager() {
        return this.rankManager;
    }
```

`reloadFeatureManagers()`メソッドの最後（`autoBroadcastManager.restart();`の直後、閉じ`}`の前）に1行追加する:

```java
        autoBroadcastManager.restart();
        rankManager.reload();
    }
```

`RankManager`は`org.craftcore.stellaria.managers.*`の一括importで既にカバーされている（`import org.craftcore.stellaria.managers.*;`が既存）ので、追加のimport文は不要。

- [ ] **Step 3: ビルド確認**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/RankManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: add RankManager (LuckPerms group -> rank display info)"
```

---

### Task 4: チャットへのランク色付き"| "とホバー表示

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/listeners/ChatListener.java`

**Interfaces:**
- Consumes: `plugin.getRankManager().getRank(Player)` → `RankManager.RankInfo`（Task 3で追加済み）、`ColorUtil.component(String)`（既存）
- Produces: なし（末端の表示変更）

- [ ] **Step 1: 送信者名の前にランク色の"| "を付ける**

`ChatListener.java`の`onChat`メソッド内、現状:

```java
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        Component message = mentionService.highlight(plainMessage, sender, colorCodesPermitted(config, sender));
        boolean clickToMessage = config.getBoolean("chat.click-to-message", true);
        Component tooltip = buildTooltip(config, sender, clickToMessage);

        event.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, ignoredMessage) -> {
            Component nameComponent = tooltip != null
                    ? sourceDisplayName.hoverEvent(HoverEvent.showText(tooltip))
                    : sourceDisplayName;
            if (clickToMessage) {
                nameComponent = nameComponent.clickEvent(ClickEvent.suggestCommand("/msg " + sender.getName() + " "));
            }
            return render(format, nameComponent, placeholder, message);
        }));
```

を次のように変更する（`RankManager.RankInfo`取得と、`sourceDisplayName`の前への"| "連結を追加）:

```java
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        Component message = mentionService.highlight(plainMessage, sender, colorCodesPermitted(config, sender));
        boolean clickToMessage = config.getBoolean("chat.click-to-message", true);
        RankManager.RankInfo rank = plugin.getRankManager().getRank(sender);
        Component tooltip = buildTooltip(config, sender, clickToMessage, rank);

        event.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, ignoredMessage) -> {
            Component rankPrefix = ColorUtil.component(rank.color() + "&l| ");
            Component nameComponent = rankPrefix.append(sourceDisplayName);
            nameComponent = tooltip != null
                    ? nameComponent.hoverEvent(HoverEvent.showText(tooltip))
                    : nameComponent;
            if (clickToMessage) {
                nameComponent = nameComponent.clickEvent(ClickEvent.suggestCommand("/msg " + sender.getName() + " "));
            }
            return render(format, nameComponent, placeholder, message);
        }));
```

- [ ] **Step 2: buildTooltipにランク名の行を追加**

`ChatListener.java`の`buildTooltip`メソッド、現状:

```java
    private Component buildTooltip(ConfigManager config, Player sender, boolean clickToMessage) {
        Component linesTooltip = config.getBoolean("chat.tooltip.enabled", true)
                ? plugin.getPlaceholderManager().resolveLines(config.getStringList("chat.tooltip.lines"), sender)
                : null;
        if (!clickToMessage) {
            return linesTooltip;
        }
        Component hint = ColorUtil.component(config.getMessage("chat.click_hint", sender));
        return linesTooltip != null ? linesTooltip.append(Component.newline()).append(hint) : hint;
    }
```

を次のように変更する（引数に`RankManager.RankInfo rank`を追加し、ランク名があれば1行目に差し込む）:

```java
    private Component buildTooltip(ConfigManager config, Player sender, boolean clickToMessage, RankManager.RankInfo rank) {
        Component rankLine = rank.displayName().isEmpty()
                ? null
                : ColorUtil.component("&%7ランク: " + rank.color() + rank.displayName());

        Component linesTooltip = config.getBoolean("chat.tooltip.enabled", true)
                ? plugin.getPlaceholderManager().resolveLines(config.getStringList("chat.tooltip.lines"), sender)
                : null;
        Component combined = rankLine;
        if (linesTooltip != null) {
            combined = combined != null ? combined.append(Component.newline()).append(linesTooltip) : linesTooltip;
        }

        if (!clickToMessage) {
            return combined;
        }
        Component hint = ColorUtil.component(config.getMessage("chat.click_hint", sender));
        return combined != null ? combined.append(Component.newline()).append(hint) : hint;
    }
```

- [ ] **Step 3: importを追加**

`ChatListener.java`先頭のimport群、`import org.craftcore.stellaria.managers.MuteManager;`の直後に1行追加する:

```java
import org.craftcore.stellaria.managers.MuteManager;
import org.craftcore.stellaria.managers.RankManager;
```

- [ ] **Step 4: ビルド確認**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 手動確認**

Run: `./gradlew runServer`（ローカルテストサーバー起動。LuckPermsを導入していない状態でよい）
手順:
1. サーバー起動後、任意のプレイヤーでログインしチャットに何か発言する
2. 発言者名の前に`&%7`色（グレー）の太字`"| "`が付いていることを確認する
3. 発言者名にマウスカーソルを合わせ、ホバーツールチップが（LuckPerms未導入なのでランク行は無いが）今まで通り表示されクラッシュしないことを確認する

- [ ] **Step 6: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/listeners/ChatListener.java
git commit -m "feat: show rank color and hover name in chat"
```

---

### Task 5: タブリストへのランクタグ表示

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/TabListManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `RankManager#getRank(Player)`（Task 3）
- Produces: なし（末端の表示変更）

- [ ] **Step 1: TabListManagerがRankManagerを受け取れるようにする**

`TabListManager.java`のコンストラクタとフィールド、現状:

```java
    private final PlaceholderManager placeholders;
    private volatile String headerTemplate;
    private volatile String footerTemplate;
    private volatile String valueTemplate;

    public TabListManager(PlaceholderManager placeholders, String headerTemplate, String footerTemplate, String valueTemplate) {
        this.placeholders = placeholders;
        this.headerTemplate = headerTemplate;
        this.footerTemplate = footerTemplate;
        this.valueTemplate = valueTemplate;
    }
```

を次のように変更する（`RankManager`フィールドをコンストラクタで受け取る。既存の`updateSettings`はテンプレート文字列だけを扱う設計なのでシグネチャは変えない）:

```java
    private final PlaceholderManager placeholders;
    private final RankManager rankManager;
    private volatile String headerTemplate;
    private volatile String footerTemplate;
    private volatile String valueTemplate;

    public TabListManager(PlaceholderManager placeholders, RankManager rankManager, String headerTemplate, String footerTemplate, String valueTemplate) {
        this.placeholders = placeholders;
        this.rankManager = rankManager;
        this.headerTemplate = headerTemplate;
        this.footerTemplate = footerTemplate;
        this.valueTemplate = valueTemplate;
    }
```

- [ ] **Step 2: tick()でplayerListNameを設定する**

`TabListManager.java`の`tick()`メソッド、現状:

```java
    public void tick() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        for (Player viewer : online) {
            viewer.setPlayerListHeaderFooter(
                    placeholders.resolve(headerTemplate, viewer),
                    placeholders.resolve(footerTemplate, viewer)
            );

            Objective objective = ensureValueObjective(BoardUtil.ensurePersonalBoard(viewer));
            for (Player target : online) {
                applyValue(objective, target);
            }
        }
    }
```

を次のように変更する（`applyPlayerListName`呼び出しを追加）:

```java
    public void tick() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        for (Player target : online) {
            applyPlayerListName(target);
        }

        for (Player viewer : online) {
            viewer.setPlayerListHeaderFooter(
                    placeholders.resolve(headerTemplate, viewer),
                    placeholders.resolve(footerTemplate, viewer)
            );

            Objective objective = ensureValueObjective(BoardUtil.ensurePersonalBoard(viewer));
            for (Player target : online) {
                applyValue(objective, target);
            }
        }
    }

    /** タブリストの名前欄に、ランクタグがあれば色付きで前置きする。ランク無しなら本来の表示名に戻す。 */
    private void applyPlayerListName(Player target) {
        RankManager.RankInfo rank = rankManager.getRank(target);
        if (rank.tablistTag().isEmpty()) {
            target.playerListName(null);
            return;
        }
        Component tag = ColorUtil.component(rank.color() + rank.tablistTag() + " ");
        target.playerListName(tag.append(Component.text(target.getName())));
    }
```

- [ ] **Step 3: importを追加**

`TabListManager.java`先頭のimport群、`import io.papermc.paper.scoreboard.numbers.NumberFormat;`の直後に1行追加する:

```java
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
```

（`RankManager`は`managers`パッケージ内の同一パッケージクラスなのでimport不要）

- [ ] **Step 4: StellariaCoreのTabListManager生成箇所を更新**

`StellariaCore.java`の`onEnable()`内、現状:

```java
        this.tabListManager = new TabListManager(
            placeholderManager,
            configManager.getString("tablist.header", ""),
            configManager.getString("tablist.footer", ""),
            configManager.getString("tablist.value", "")
        );
```

を次のように変更する（`rankManager`を第2引数に追加）:

```java
        this.tabListManager = new TabListManager(
            placeholderManager,
            rankManager,
            configManager.getString("tablist.header", ""),
            configManager.getString("tablist.footer", ""),
            configManager.getString("tablist.value", "")
        );
```

この呼び出しは`this.rankManager = new RankManager(this);`（Task 3で追加済み）より後に実行される必要がある。現状のコード順（`rankManager`の生成は`onEnable`の前半、`tabListManager`の生成はその後の「6. Scoreboard/Tablist/Belowname のインスタンス化」ブロック）では既にこの順序を満たしているため、コードの移動は不要。

- [ ] **Step 5: ビルド確認**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 手動確認**

Run: `./gradlew runServer`
手順:
1. LuckPerms未導入の状態でサーバー起動、ログインしてタブリスト（Tabキー）を開く
2. 自分の名前がタグ無しの通常表示（プレイヤー名そのまま）になっていることを確認する
3. コンソールに`LuckPerms が見つかりませんでした。ランク表示機能は無効化されます。`という警告が出ていて、他の機能（チャット・スコアボード等）が問題なく動くことを確認する

- [ ] **Step 7: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/TabListManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: show rank tag in tab list player names"
```

---

### Task 6: LuckPerms導入環境での最終確認

**Files:**
- なし（コード変更無し、動作確認のみ）

**Interfaces:**
- Consumes: Task 1〜5の全成果物
- Produces: なし

- [ ] **Step 1: ローカルテストサーバーにLuckPermsを導入する**

`run/plugins/`に[LuckPerms公式](https://luckperms.net/download)のPaper向けjarを配置する（このタスクはネットワークアクセスが要るため、CIではなく手元で実施する）。

- [ ] **Step 2: サーバー起動とグループ作成**

Run: `./gradlew runServer`

サーバーコンソールで以下を実行し、テスト用プレイヤーを各グループに所属させる（`<player>`は実際のオフライン/オンラインプレイヤー名に置き換える）:

```
lp creategroup admin
lp creategroup mod
lp creategroup booster
lp user <player> parent add admin
```

- [ ] **Step 3: チャット表示の確認**

`admin`グループのプレイヤーでログインし、チャットに発言する。
Expected: 名前の前に黄色（`&%e`）の太字`"| "`が付き、名前にホバーすると`ランク: 管理者`の行がツールチップに表示される。

`lp user <player> parent remove admin` → `lp user <player> parent add mod` として同様に確認する。
Expected: 水色（`&%b`）の`"| "`、ホバーで`ランク: スタッフ`。

`lp user <player> parent remove mod` として、どのグループにも属さない状態で発言する。
Expected: グレー（`&%7`）の`"| "`、ホバーにランク行は出ない。

- [ ] **Step 4: タブリスト表示の確認**

`lp user <player> parent add booster`のうえでタブリストを開く。
Expected: 名前の前にピンク（`&%d`）の`ʙᴏᴏsᴛᴇʀ`タグが付いている。

`lp user <player> parent remove booster`のうえでタブリストを開く。
Expected: タグ無し、通常のプレイヤー名。

- [ ] **Step 5: /stellariareloadでの反映確認**

`config.yml`の`rank.groups`の`admin`の`color`を`"&%c"`（赤）に変更し、`/stellariareload`を実行後、`admin`グループのプレイヤーで再度発言する。
Expected: 色が黄色から赤に変わっている。確認後、`"&%e"`に戻しておく。

- [ ] **Step 6: 完了確認**

ここまでの手動確認が全て期待通りであれば、このプランは完了。追加のコミットは無い（コード変更を伴わない検証タスクのため）。
