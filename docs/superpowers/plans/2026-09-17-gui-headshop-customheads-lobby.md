# GUI操作・ヘッドショップ・カスタムヘッド・ロビー実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GUIのプレイヤーインベントリ操作を安全に許可し、ヘッドショップ管理・即時抽選、設定式カスタムヘッド、`/lobby`を提供する。

**Architecture:** `GuiListener`をイベント境界としてトップGUIを保護し、下部インベントリの危険な転送だけを拒否する。カスタムヘッドは外部依存なしの`CustomHeadUtil`で`customhead.yml`のbase64テクスチャをItemStackへ変換する。ヘッドショップの抽選候補選定は純粋なUtilへ分離し、DB永続化とコマンドは既存の`HeadshopManager`/`HeadshopCommand`に残す。

**Tech Stack:** Java 21、Paper/Folia 1.21.11 API、SQLite JDBC、JUnit 5、Gradle。

**Spec:** `docs/superpowers/specs/2026-09-17-gui-headshop-customheads-lobby-design.md`

## Global Constraints

- GUI上部のアイテムは移動・持ち出し・ドラッグでの変更を常に防ぐ。
- 下部インベントリの通常クリックは許可し、Shiftクリック・ダブルクリックによるGUIへの転送だけを防ぐ。
- カスタムヘッドは外部プラグイン・HTTP APIを使わず、`customhead.yml`のbase64 `textures`値だけを使う。
- `/headshop reset` は`stellaria.headshop.admin`を必要とし、候補が十分なら現在表示中のヘッドを選ばない。
- Claudeの未コミット変更である`src/main/resources/config.yml`と`DiscordBotManager.java`の既存差分を上書き・ステージしない。

---

### Task 1: GUIイベント境界を安全にする

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/gui/GuiListener.java`
- Create: `src/test/java/org/craftcore/stellaria/gui/GuiListenerTest.java`

**Interfaces:**
- Produces: `GuiListener.shouldCancelBottomClick(boolean shiftClick, ClickType click)`（package-private static）
- Produces: `GuiListener.shouldCancelDrag(Set<Integer> rawSlots, int topInventorySize)`（package-private static）
- Consumes: 全`Gui`の既存`onClick(InventoryClickEvent)`。

- [ ] **Step 1: 失敗するGUI境界テストを書く**

```java
package org.craftcore.stellaria.gui;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiListenerTest {
    @Test
    void permitsOrdinaryBottomInventoryClicks() {
        assertFalse(GuiListener.shouldCancelBottomClick(false, ClickType.LEFT));
    }

    @Test
    void blocksBottomInventoryTransfersThatCouldTouchTheGui() {
        assertTrue(GuiListener.shouldCancelBottomClick(true, ClickType.LEFT));
        assertTrue(GuiListener.shouldCancelBottomClick(false, ClickType.DOUBLE_CLICK));
    }

    @Test
    void blocksOnlyDragsThatEnterTopInventory() {
        assertTrue(GuiListener.shouldCancelDrag(Set.of(4, 60), 54));
        assertFalse(GuiListener.shouldCancelDrag(Set.of(54, 60), 54));
    }
}
```

- [ ] **Step 2: テストが未実装で失敗することを確認する**

Run: `./gradlew test --tests org.craftcore.stellaria.gui.GuiListenerTest`

Expected: FAIL。`shouldCancelBottomClick` と `shouldCancelDrag` が存在しないためコンパイルエラーになる。

- [ ] **Step 3: 最小限のイベント振り分けを実装する**

`GuiListener`に`InventoryDragEvent`のハンドラを追加する。クリック時は`event.getView().getTopInventory().getHolder()`が`Gui`でない場合は何もしない。トップをクリックした場合だけ`gui.onClick(event)`を呼び、下部では次の純粋ヘルパーがtrueの時だけキャンセルする。

```java
static boolean shouldCancelBottomClick(boolean shiftClick, ClickType click) {
    return shiftClick || click == ClickType.DOUBLE_CLICK;
}

static boolean shouldCancelDrag(Set<Integer> rawSlots, int topInventorySize) {
    return rawSlots.stream().anyMatch(slot -> slot >= 0 && slot < topInventorySize);
}
```

ドラッグ時はトップのholderが`Gui`の場合にだけ`shouldCancelDrag(event.getRawSlots(), top.getSize())`を使う。トップ側クリックは既存の各GUIがキャンセルするため、各GUIクラスには変更を加えない。

- [ ] **Step 4: GUI境界テストを通す**

Run: `./gradlew test --tests org.craftcore.stellaria.gui.GuiListenerTest`

Expected: PASS（3 tests）。

- [ ] **Step 5: このタスクだけをコミットする**

```bash
git add src/main/java/org/craftcore/stellaria/gui/GuiListener.java src/test/java/org/craftcore/stellaria/gui/GuiListenerTest.java
git commit -m "fix: allow player inventory movement in GUIs"
```

### Task 2: 設定式カスタムヘッドと`/menu`・`/world`連携を追加する

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/CustomHeadUtil.java`
- Create: `src/main/resources/customhead.yml`
- Create: `src/test/java/org/craftcore/stellaria/utils/CustomHeadUtilTest.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/java/org/craftcore/stellaria/gui/MenuGui.java`
- Modify: `src/main/java/org/craftcore/stellaria/gui/WorldSelectGui.java`
- Modify: `src/main/resources/config.yml`

**Interfaces:**
- Produces: `CustomHeadUtil.resolveTexture(ConfigurationSection heads, String id): Optional<String>`。
- Produces: `CustomHeadUtil.create(StellariaCore plugin, String id): @Nullable ItemStack`。
- Consumes: `ConfigManager#get("customhead.yml")`、`menu.items[].custom-head`、`world.gui-custom-heads.<world-name>`。

- [ ] **Step 1: カスタムヘッドの設定解決テストを書く**

```java
package org.craftcore.stellaria.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomHeadUtilTest {
    @Test
    void resolvesConfiguredNonBlankTexture() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("heads.lobby-compass.texture", "base64-value");

        assertEquals("base64-value", CustomHeadUtil.resolveTexture(config.getConfigurationSection("heads"), "lobby-compass").orElseThrow());
    }

    @Test
    void rejectsMissingAndBlankTextures() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("heads.empty.texture", "  ");

        assertTrue(CustomHeadUtil.resolveTexture(config.getConfigurationSection("heads"), "missing").isEmpty());
        assertTrue(CustomHeadUtil.resolveTexture(config.getConfigurationSection("heads"), "empty").isEmpty());
    }
}
```

- [ ] **Step 2: テストが未実装で失敗することを確認する**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.CustomHeadUtilTest`

Expected: FAIL。`CustomHeadUtil`が未作成のためコンパイルエラーになる。

- [ ] **Step 3: `CustomHeadUtil` とテンプレートを実装する**

`resolveTexture`はnullのsection、空ID、存在しないID、trim後に空の`texture`を`Optional.empty()`にする。`create`は`customhead.yml`の`heads` sectionを渡し、空ならnullを返す。成功時は`PLAYER_HEAD`、`SkullMeta`、`Bukkit.createProfile(UUID.nameUUIDFromBytes((id + ":" + texture).getBytes(StandardCharsets.UTF_8)))`、`new ProfileProperty("textures", texture)`を使う。既存`HeadshopManager#createHeadItem`と同じPaper profile APIを使う。

`customhead.yml`の初期内容は次のとおりにする。

```yaml
# heads.<id>.texture にヘッド配布サイト等のbase64 textures値を設定します。
# 変更後は /stellariareload を実行してください。
heads: {}
```

`StellariaCore#onEnable`で`configManager.register("customhead.yml")`を`config.yml`/`messages.yml`の直後に追加する。

- [ ] **Step 4: `/menu`と`/world`をカスタムヘッドへ接続する**

`MenuGui.MenuEntry`に`@Nullable String customHeadId`を加える。`loadEntries`は既存の`material`を常に検証し、任意の文字列`custom-head`を記録する。`populate`は`customHeadId`があれば`CustomHeadUtil.create(plugin, customHeadId)`を先に使い、nullなら従来の`new ItemStack(material)`へフォールバックする。作成後のdisplay name設定と、通常の`PLAYER_HEAD`に対するviewer skin設定は、カスタムヘッドを上書きしないように「カスタムヘッドでない場合」に限定する。

`WorldSelectGui#worldItem`は、`config.yml`の`world.gui-custom-heads` sectionからワールド名のIDを取得する。`CustomHeadUtil.create`が成功すればそのItemStack、未指定/失敗なら既存の`iconFor(environment)`を使う。どちらも既存の表示名・ロアを設定する。

`config.yml`の`world`に、空の既定マッピングを追加する。既存のClaude変更を含む行は編集せず、この独立ブロックだけを追加する。

```yaml
  gui-custom-heads: {}
```

- [ ] **Step 5: カスタムヘッドテストを通す**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.CustomHeadUtilTest`

Expected: PASS（2 tests）。

- [ ] **Step 6: このタスクだけをコミットする**

```bash
git add src/main/java/org/craftcore/stellaria/utils/CustomHeadUtil.java src/main/resources/customhead.yml src/test/java/org/craftcore/stellaria/utils/CustomHeadUtilTest.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/gui/MenuGui.java src/main/java/org/craftcore/stellaria/gui/WorldSelectGui.java src/main/resources/config.yml
git commit -m "feat: add configurable custom GUI heads"
```

### Task 3: ヘッドショップ管理の永続化と即時抽選を追加する

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/utils/HeadshopRotationUtil.java`
- Create: `src/test/java/org/craftcore/stellaria/utils/HeadshopRotationUtilTest.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/DatabaseManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/HeadshopManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/gui/HeadshopAdminGui.java`
- Modify: `src/main/java/org/craftcore/stellaria/commands/HeadshopCommand.java`
- Modify: `src/main/resources/messages.yml`

**Interfaces:**
- Produces: `HeadshopRotationUtil.select(List<PoolHead> pool, Set<Integer> previousIds, Set<Integer> currentIds, int limit, Random random): List<PoolHead>`。
- Produces: `DatabaseManager.insertAndGetId(String table, Map<String, Object> values): OptionalInt`。
- Produces: `HeadshopManager.addToPool(String displayName, String texture, UUID addedBy): @Nullable PoolHead`。
- Produces: `HeadshopManager.resetTodayRotation(): RotationResetResult` where `RotationResetResult` is `SUCCESS`, `EMPTY_POOL`, or `DATABASE_ERROR`.

- [ ] **Step 1: 即時抽選の候補除外テストを書く**

```java
package org.craftcore.stellaria.utils;

import org.craftcore.stellaria.managers.HeadshopManager.PoolHead;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadshopRotationUtilTest {
    @Test
    void avoidsPreviousAndCurrentHeadsWhenEnoughAlternativesExist() {
        List<PoolHead> pool = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(id -> new PoolHead(id, "head-" + id, "texture-" + id)).toList();

        List<PoolHead> selected = HeadshopRotationUtil.select(pool, Set.of(1, 2, 3, 4, 5), Set.of(6, 7, 8, 9, 10), 5, new Random(0));

        assertEquals(5, selected.size());
        assertTrue(selected.stream().allMatch(head -> head.id() >= 11));
    }

    @Test
    void fallsBackToPoolWhenThereAreTooFewAlternatives() {
        List<PoolHead> pool = java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(id -> new PoolHead(id, "head-" + id, "texture-" + id)).toList();

        assertEquals(3, HeadshopRotationUtil.select(pool, Set.of(), Set.of(1, 2, 3), 5, new Random(0)).size());
    }
}
```

- [ ] **Step 2: テストが未実装で失敗することを確認する**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.HeadshopRotationUtilTest`

Expected: FAIL。`HeadshopRotationUtil`が未作成のためコンパイルエラーになる。

- [ ] **Step 3: 純粋な抽選Utilを実装する**

`select`は、順に「前回と現在の両方を除外」「現在だけを除外」「全プール」の候補を試す。要求件数以上の候補がある最初の集合をshuffleして最大`limit`件返す。どの集合も件数不足なら全プールをshuffleして全件を返す。引数のList/Setを変更しない。

- [ ] **Step 4: ヘッドショップのDB書込みと即時リセットを実装する**

`DatabaseManager`にJDBCの`Statement.RETURN_GENERATED_KEYS`を使う`insertAndGetId`を追加する。INSERTが1行成功し、生成キーが存在する場合だけ`OptionalInt.of(id)`を返し、それ以外はemptyを返す。

`HeadshopManager#addToPool`はこのメソッドを用い、成功時に実IDを持つ`PoolHead`を返す。`HeadshopAdminGui#registerFromCursor`はnullなら`headshop.admin.save-failed`を送り、成功時だけ返却された`PoolHead`を画面表示と内部`pool`リストへ追加する。`pool`は追加・削除できる`ArrayList`に変更する。削除時は表示だけでなくリストからも対象を外す。

`HeadshopManager`の自動生成は`HeadshopRotationUtil.select(pool, previousIds, Set.of(), ROTATION_SIZE, new Random())`を使う。`resetTodayRotation`は現在日のpool IDを取得してから当日行を削除し、`select(pool, previousIds, currentIds, ...)`で選び直してINSERTする。空プールとINSERT失敗を結果に反映する。

`HeadshopCommand`は最初に`args.length == 1 && args[0].equalsIgnoreCase("reset")`を処理する。この分岐はPlayer制約より前に置き、`sender.hasPermission("stellaria.headshop.admin")`を検査する。結果を`headshop.reset-success`、`headshop.reset-empty`、`headshop.reset-failed`で通知する。タブ補完候補を`List.of("admin", "reset")`にする。

`messages.yml`へ次を追加する。

```yaml
  reset-success: "&%a本日のおすすめヘッドを再抽選しました。"
  reset-empty: "&%cヘッドプールが空のため再抽選できません。"
  reset-failed: "&%c再抽選の保存に失敗しました。コンソールを確認してください。"
  admin:
    save-failed: "&%cヘッドの登録を保存できませんでした。コンソールを確認してください。"
```

- [ ] **Step 5: 即時抽選テストを通す**

Run: `./gradlew test --tests org.craftcore.stellaria.utils.HeadshopRotationUtilTest`

Expected: PASS（2 tests）。

- [ ] **Step 6: このタスクだけをコミットする**

```bash
git add src/main/java/org/craftcore/stellaria/utils/HeadshopRotationUtil.java src/test/java/org/craftcore/stellaria/utils/HeadshopRotationUtilTest.java src/main/java/org/craftcore/stellaria/managers/DatabaseManager.java src/main/java/org/craftcore/stellaria/managers/HeadshopManager.java src/main/java/org/craftcore/stellaria/gui/HeadshopAdminGui.java src/main/java/org/craftcore/stellaria/commands/HeadshopCommand.java src/main/resources/messages.yml
git commit -m "fix: persist headshop admin entries and add reset"
```

### Task 4: `/lobby`コマンドを追加する

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/LobbyCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`

**Interfaces:**
- Produces: `/lobby` command.
- Consumes: `lobby.world`、`stellaria.lobby`、`Bukkit.getWorld(String)`。

- [ ] **Step 1: 失敗するワールド名解決テストを書く**

`LobbyCommand`にpackage-private staticの`configuredWorldName(String configured)`を設け、空白設定時には既定`"lobby"`を返す形にする。

```java
package org.craftcore.stellaria.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LobbyCommandTest {
    @Test
    void usesLobbyAsFallbackForBlankConfiguredWorld() {
        assertEquals("lobby", LobbyCommand.configuredWorldName("  "));
    }

    @Test
    void trimsConfiguredWorldName() {
        assertEquals("spawn", LobbyCommand.configuredWorldName(" spawn "));
    }
}
```

- [ ] **Step 2: テストが未実装で失敗することを確認する**

Run: `./gradlew test --tests org.craftcore.stellaria.commands.LobbyCommandTest`

Expected: FAIL。`LobbyCommand`が未作成のためコンパイルエラーになる。

- [ ] **Step 3: コマンド・設定・登録を実装する**

`LobbyCommand`は`CommandExecutor`を実装し、Player以外には`lobby.must-be-player`を送る。`configuredWorldName(plugin.getConfigManager().getString("lobby.world", "lobby"))`を`Bukkit.getWorld`に渡し、見つからなければ`lobby.world-not-found`の`%world%`を置換して送る。成功時は`player.teleportAsync(world.getSpawnLocation())`を呼ぶ。

`StellariaCore#onEnable`で`getCommand("lobby").setExecutor(new LobbyCommand(this))`を登録する。`plugin.yml`へ次を追加する。

```yaml
  lobby:
    permission: stellaria.lobby
```

```yaml
  stellaria.lobby:
    default: true
```

`config.yml`には以下を追加する。

```yaml
lobby:
  world: "lobby"
```

`messages.yml`には以下を追加する。

```yaml
lobby:
  must-be-player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  world-not-found: "&%cロビーワールドが見つかりません: &%e%world%"
```

- [ ] **Step 4: `/lobby`の単体テストを通す**

Run: `./gradlew test --tests org.craftcore.stellaria.commands.LobbyCommandTest`

Expected: PASS（2 tests）。

- [ ] **Step 5: このタスクだけをコミットする**

```bash
git add src/main/java/org/craftcore/stellaria/commands/LobbyCommand.java src/test/java/org/craftcore/stellaria/commands/LobbyCommandTest.java src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/resources/plugin.yml src/main/resources/config.yml src/main/resources/messages.yml
git commit -m "feat: add lobby teleport command"
```

### Task 5: 全体検証と実機確認を行う

**Files:**
- Modify: 実機確認で不具合が再現した場合のみ、原因ファイルを新しいTDDサイクルで変更する。

**Interfaces:**
- Consumes: Tasks 1–4の完成コード。
- Produces: テスト・ビルドの実行結果と手動確認結果。

- [ ] **Step 1: 全JUnitテストを実行する**

Run: `./gradlew test`

Expected: PASS。既存テストと今回追加した4テストクラスがすべて成功する。

- [ ] **Step 2: 配布JARをビルドする**

Run: `./gradlew build`

Expected: SUCCESS。`build/libs/StellariaCore-1.0.65.jar`が生成される。

- [ ] **Step 3: 実機でGUIとカスタムヘッドを確認する**

1. `/menu`、`/world`、`/headshop`、`/headshop admin`の各画面で、下部インベントリの通常クリックが使え、ShiftクリックでGUIへ入れられず、GUIアイテムを持ち出せないことを確認する。
2. `/headshop admin`でカスタムテクスチャを登録し、一度閉じて開き直して残ること、Shiftクリックで削除後に再度開いて消えていることを確認する。
3. 11件以上のプールを用意して`/headshop reset`を実行し、実行前の5件が再表示されないことを確認する。空プール時には失敗メッセージを確認する。
4. `customhead.yml`に有効なbase64を追加し、`config.yml`の`menu.items[].custom-head`と`world.gui-custom-heads.<world>`へ設定して`/stellariareload`後の表示を確認する。存在しないIDがマテリアルへフォールバックすることも確認する。
5. `lobby.world`を実在ワールドへ設定して`/lobby`がスポーン位置へ移動すること、存在しない名前ではエラーになることを確認する。

- [ ] **Step 4: 作業ツリーを確認して空コミットを作らない**

Run: `git status --short`

Expected: Tasks 1–4で既にコミット済みのため、Task 5の確認だけでは新しい変更がない。実機で原因が分かった場合は、このタスクを完了扱いにせず、その原因に対する新しい失敗テストから始める。

## Self-review

- GUI上部の保護、下部通常操作、Shift/ダブルクリック、ドラッグの全要件はTask 1で扱う。
- `customhead.yml`、reload登録、Menu/World参照、存在しないIDフォールバックはTask 2で扱う。
- 実IDでのheadshop管理、失敗時メッセージ、即時再抽選、現在表示の除外、権限・補完はTask 3で扱う。
- `/lobby`の設定・権限・エラー・スポーン移動はTask 4で扱う。
- 自動テスト・ビルド・実機チェックはTask 5で扱う。
- プレースホルダー（TBD/TODO/後で実装）は含まない。インターフェース名は各タスク間で一致している。
