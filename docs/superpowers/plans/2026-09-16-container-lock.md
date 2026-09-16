# 個別コンテナロック機能（`/lock`）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** チェスト等の個別保護、共同利用、二連チェスト整合性、ホッパー対策を実装する。

**Architecture:** `ContainerLock` をテスト可能なドメインモデル、`ContainerLockManager` をDB/キャッシュ、`LockCommand` を操作、`ContainerLockListener` を保護イベントとして分離する。

**Tech Stack:** Java 21, Paper API 1.21.11, SQLite, JUnit Jupiter 5.

**Spec:** `docs/superpowers/specs/2026-09-16-container-lock-design.md`

## Global Constraints

- `messages.yml` と `ConfigManager` を全ユーザー文言に使い、`plugin.getConfig()`を使わない。
- 対象はチェスト、トラップチェスト、樽、`*_SHULKER_BOX` のみ。ホッパーはロック対象外だがロック済みコンテナとのアイテム移動は止める。
- 土地保護を優先する。新listenerのプレイヤー操作ハンドラは `ignoreCancelled = true` を使う。
- 所有者はロックしたまま壊せず、`/unlock`後に壊す。管理者の直接破壊時だけ座標を消す。
- 共有ツリーの対象外変更をステージしない。コミットはこの環境の読み取り専用`.git`には行わない。

---

### Task 1: テスト基盤とロックドメイン

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/java/org/craftcore/stellaria/managers/ContainerLock.java`
- Create: `src/test/java/org/craftcore/stellaria/managers/ContainerLockTest.java`

**Interfaces:** `ContainerLock.BlockKey(world,x,y,z)`、`ContainerLock(lockId,owner,blocks,members)`、`canAccess(UUID, boolean)`、`canManage(UUID, boolean)`、`addMember`、`removeMember`、`addBlock`、`removeBlock`、`isEmpty`。

- [ ] **Step 1: JUnitを設定する**

`build.gradle.kts`に以下を追加する。

```kotlin
testImplementation(platform("org.junit:junit-bom:5.11.4"))
testImplementation("org.junit.jupiter:junit-jupiter")

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: 失敗するドメインテストを書く**

`ContainerLockTest`で次を検証する。

```java
assertTrue(lock.canAccess(owner, false));
assertTrue(lock.canAccess(member, false));
assertFalse(lock.canManage(member, false));
assertTrue(lock.canManage(admin, true));
assertFalse(lock.addMember(owner));
assertTrue(lock.addMember(member));
assertFalse(lock.addMember(member));
assertTrue(lock.removeBlock(left));
assertFalse(lock.isEmpty());
assertTrue(lock.removeBlock(right));
assertTrue(lock.isEmpty());
```

- [ ] **Step 3: REDを確認する**

Run: `./gradlew test --tests org.craftcore.stellaria.managers.ContainerLockTest`

Expected: `ContainerLock` 未定義のコンパイル失敗。

- [ ] **Step 4: 最小実装を追加する**

`LinkedHashSet`の防御的コピーを保持し、アクセスは次で判定する。

```java
public boolean canAccess(UUID player, boolean admin) {
    return admin || owner.equals(player) || members.contains(player);
}
public boolean canManage(UUID player, boolean admin) {
    return admin || owner.equals(player);
}
```

- [ ] **Step 5: GREENを確認する**

Run: `./gradlew test --tests org.craftcore.stellaria.managers.ContainerLockTest`

Expected: 0 failures。

---

### Task 2: `ContainerLockManager`のDBとキャッシュ

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/ContainerLockManager.java`
- Create: `src/test/java/org/craftcore/stellaria/managers/ContainerLockManagerTest.java`

**Interfaces:** `find(Block|BlockKey)`、`isLocked(Block)`、`isLockable(Material)`、`canAccess(Block,Player)`、`canManage(Block,Player)`、`create(Player,Set<BlockKey>)`、`attachBlock(ContainerLock,BlockKey)`、`unlock(ContainerLock)`、`removeDestroyedBlock(BlockKey)`、`trust`、`untrust`。

- [ ] **Step 1: 二連チェストキャッシュの失敗テストを書く**

DB不要のパッケージ可視コンストラクタで、次をテストする。

```java
manager.registerLoadedLock(lock(owner, Set.of(left, right)));
assertSame(manager.findCached(left).orElseThrow(), manager.findCached(right).orElseThrow());
assertTrue(manager.detachFromCache(left));
assertTrue(manager.findCached(left).isEmpty());
assertTrue(manager.findCached(right).isPresent());
assertTrue(manager.detachFromCache(right));
assertTrue(manager.findCached(lockId).isEmpty());
```

- [ ] **Step 2: REDを確認する**

Run: `./gradlew test --tests org.craftcore.stellaria.managers.ContainerLockManagerTest`

Expected: `ContainerLockManager` 未定義のコンパイル失敗。

- [ ] **Step 3: キャッシュを実装してGREENにする**

```java
private final Map<BlockKey, ContainerLock> locksByBlock = new ConcurrentHashMap<>();
private final Map<UUID, ContainerLock> locksById = new ConcurrentHashMap<>();
```

`registerLoadedLock`はIDと全座標を索引化する。`detachFromCache`は一座標を外し、最後ならID索引も外す。

- [ ] **Step 4: DBロードと更新を実装する**

起動時に`container_locks`、`container_lock_blocks`、`container_lock_members`を読み、ロックID単位でモデルを再構築する。作成・二連化・共同利用者変更・解除・破壊時削除は `DatabaseManager.transaction` 内で書き込み、成功後だけキャッシュを更新する。`unlock`はblocks→members→locksを削除し、`removeDestroyedBlock`は最後の座標だけmembersとlock本体も削除する。

- [ ] **Step 5: 対象と権限判定を実装する**

```java
return material == Material.CHEST || material == Material.TRAPPED_CHEST
    || material == Material.BARREL || material.name().endsWith("SHULKER_BOX");
```

未ロックは許可、ロック済みは `player.hasPermission("stellaria.lock.admin")` とドメインモデルで判定する。

- [ ] **Step 6: テストとビルドを確認する**

Run: `./gradlew test && ./gradlew build`

Expected: `BUILD SUCCESSFUL`。

---

### Task 3: コマンド・DBテーブル・リソース・配線

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/LockCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/plugin.yml`
- Create: `src/test/java/org/craftcore/stellaria/commands/LockCommandTest.java`

**Interfaces:** `/lock`、`/unlock`、`StellariaCore#getContainerLockManager()`。

- [ ] **Step 1: コマンド対象マテリアルの失敗テストを書く**

`LockCommand.isActionableTarget(Material)`に対しCHEST/BARREL/WHITE_SHULKER_BOXはtrue、HOPPER/ENDER_CHEST/STONEはfalseを検証する。

- [ ] **Step 2: REDを確認する**

Run: `./gradlew test --tests org.craftcore.stellaria.commands.LockCommandTest`

Expected: `LockCommand` 未定義のコンパイル失敗。

- [ ] **Step 3: `/lock` と `/unlock` を実装する**

視線先は `player.getTargetBlockExact(5)` を使う。`/lock`は引数なしで作成、`trust <online player>`と`untrust <online player>`を受け付ける。`/unlock`は所有者か管理者だけに許可する。二連チェストは `Chest#getInventory().getHolder()` の `DoubleChest` から左右座標を集め、同じ新規lock IDに登録する。対象外、未ロック、権限不足、自己trust、重複をすべて `lock.*`メッセージへ対応付ける。

- [ ] **Step 4: テーブル・配線・文言を追加する**

`StellariaCore`に3テーブル（`container_locks`、`container_lock_blocks`、`container_lock_members`）を追加し、DB接続後にmanagerを生成してgetterを公開する。`lock`と`unlock`は同一`LockCommand`へ登録する。

`plugin.yml`に`lock:`、`unlock:`、`stellaria.lock`（true）、`stellaria.lock.admin`（op）、および`stellaria.admin.children`の`stellaria.lock.admin: true`を追加する。

`messages.yml`に、`must_be_player`、`usage`、`unlock_usage`、`look_at_container`、`created`、`already_locked`、`unlocked`、`not_locked`、`not_owner`、`trusted`、`untrusted`、`already_trusted`、`not_trusted`、`cannot_trust_self`、`protected`、`double_chest_denied`を持つ`lock:`を追加する。

- [ ] **Step 5: GREENとビルドを確認する**

Run: `./gradlew test && ./gradlew build`

Expected: `BUILD SUCCESSFUL`。

---

### Task 4: 保護リスナー・二連化・ホッパー対策

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/listeners/ContainerLockListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

- [ ] **Step 1: 開閉と破壊を保護する**

`PlayerInteractEvent`（右クリック・主手だけ）でアクセス不可ならキャンセルし、`ActionBarManager#flash(player, "container_lock", ColorUtil.component(lock.protected), 40L)`を送る。`BlockBreakEvent`は管理者以外をキャンセルする。管理者の破壊だけ、キャンセルされていないことを再確認した`MONITOR`ハンドラで`removeDestroyedBlock`を呼ぶ。

- [ ] **Step 2: 二連化を保護する**

`BlockPlaceEvent`を`HIGHEST, ignoreCancelled=true`で扱う。実際に`DoubleChest`となった時、片側だけがロック済みなら所有者/管理者による配置だけ新しい半分を`attachBlock`する。無権限、別lock ID同士、DB追加失敗はイベントをキャンセルして`lock.double_chest_denied`を送る。同じlock IDなら何もしない。

- [ ] **Step 3: 搬送・環境要因を保護する**

`InventoryMoveItemEvent`でsourceまたはdestinationのブロック位置がロック済みならキャンセルする。`EntityExplodeEvent`と`BlockExplodeEvent`では`blockList`からロック済みブロックを除く。`BlockPistonExtendEvent`と`BlockPistonRetractEvent`では移動対象にロック済みブロックが一つでもあればキャンセルする。

- [ ] **Step 4: listenerを登録して検証する**

`LandProtectionListener`登録直後に`new ContainerLockListener(this)`を登録する。

Run: `./gradlew test && ./gradlew build`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 実機確認する**

Run: `./gradlew runServer`

2人と管理者で、共同利用、共同利用者の解除拒否、ロック済み単体の二連化、別ロック同士の二連化拒否、二連の一括解除、管理者による片側/最後の破壊、通常ホッパーとホッパー付きトロッコ、TNT、ピストン、`/land`併用を確認する。

## Plan Self-Review

- 仕様の対象・権限・二連化・破壊後のクリーンアップ・搬送・環境保護を4タスクに割り当てた。
- 各ドメイン実装の前に失敗テストを実行し、その後にgreen確認を行う。
- 公開APIはTask 1–3で固定し、Task 4は既定APIだけを消費する。
