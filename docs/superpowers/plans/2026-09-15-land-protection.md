# 土地保護機能（/land）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/land`を実装する — プレイヤーがチャンク単位で土地をclaimし、非メンバーによるブロック破壊・設置・コンテナアクセス・爆発・延焼を防ぐ。隣接するclaimは自動で「縄張り（territory）」としてグルーピングされ、信頼リストとPvP許可を共有する。サーバー全体はデフォルトPvP無効で、縄張りごとにオーナーが`/land pvp on`で明示的に許可した場所だけPvPできる。

**Architecture:** 新規 `managers/LandManager`（`HomeManager`/`WarpManager`と同じ「`plugin`を保持し`DatabaseManager`を直接叩く」構造。起動時にDBから全件ロードするインメモリキャッシュ`Map<ChunkKey, Claim>`/`Map<String territoryId, Territory>`を持ち、`BlockBreakEvent`等のホットパスをSQLite往復なしで判定する）、新規 `listeners/LandProtectionListener`（Block/Interact/PvP/爆発/延焼の7イベントを保護判定でキャンセル）、新規 `commands/LandCommand`（`/land`本体 + claim/unclaim/info/trust/untrust/trustlist/pvp/list/helpサブコマンド）。既存`KikoriListener#onBlockBreak`に`ignoreCancelled = true`を1行追加することで、木こりの連鎖伐採（`player.breakBlock()`で本物の`BlockBreakEvent`を発火させる実装）が保護判定に自然に従うようにする。

**Tech Stack:** Java 21, Paper API 1.21.11-R0.1-SNAPSHOT（Folia対応スケジューラ）, SQLite（`sqlite-jdbc`）, Vault（`EconomyManager`経由）。

**Spec:** `docs/superpowers/specs/2026-09-15-land-protection-design.md`

## Global Constraints

- Java 21 / Paper API 1.21.11-R0.1-SNAPSHOT（`build.gradle.kts`）— 新規依存は追加しない。
- パッケージルート: `org.craftcore.stellaria`。新規クラスは既存のサブパッケージ分割に従う: `commands/`・`managers/`・`listeners/`。
- 設定値は必ず`ConfigManager`（`getString`/`getInt`/`getDouble`/`getBoolean`/`getStringList`/`getMessage`/`getMessageList`）経由。`plugin.getConfig()`を直接叩かない。
- ユーザー向け文言は全部`messages.yml`、`ConfigManager.getMessage(path, player)`経由で取得する（`%player%`置換・PlaceholderAPI・色変換が一括で乗る）。`%cost%`/`%owner%`/`%refund%`/`%max%`/`%target%`/`%name%`/`%pvp_state%`/`%chunks%`/`%territories%`のような独自プレースホルダは`FormatUtil.replace(...)`で置換する（`HomeCommand`の`%name%`/`%max%`と同じパターン）。
- 位置（チャンク境界パーティクル）に紐づく一時的な繰り返し処理は`Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, consumer, delay, period)`を使う（プレイヤーに紐づく処理は`player.getScheduler()`、ワールド全体は`Bukkit.getGlobalRegionScheduler()`という既存の使い分けにならう）。
- **本リポジトリに自動テストは無い**（`src/test`無し、テストGradleタスク無し）。各タスクの検証は基本的に`./gradlew build`が通ることのみ。**`./gradlew runServer`によるローカル確認は行わない**（実機確認は最終タスクでチェックリストとしてまとめて渡す）。
- 既存コードは`EventPriority`を一切使っていないが、`LandProtectionListener#onBlockBreak`だけは`EventPriority.LOW`で明示登録する（`KikoriListener#onBlockBreak`より先に評価させ、保護判定でキャンセルされたブロックの連鎖伐採起動を防ぐため）。この1箇所以外で新規に`EventPriority`は使わない。
- コミットメッセージは既存の慣習（`feat:`/`fix:`/`docs:`/`chore:`）に従う。

---

### Task 1: Config / Messages / plugin.yml 基盤 + DBテーブル作成

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Produces: config keys `land.enabled-worlds`(list) / `land.cost-per-chunk`(double) / `land.max-chunks-per-player`(int) / `land.refund-on-unclaim`(bool) / `land.protect.explosions`(bool) / `land.protect.fire`(bool) / `land.protected-interactables`(list) / `land.border-particle.*`(enabled/particle/color/size/duration-seconds)。
- Produces: message keys（Task 5で全キー使用）: `land.must_be_player` / `land.no_permission` / `land.usage` / `land.claimed` / `land.already-claimed` / `land.unclaimed` / `land.unclaimed-no-refund` / `land.not-claimed` / `land.not-your-claim` / `land.not-owner-trust` / `land.limit-reached` / `land.insufficient-funds` / `land.world-disabled` / `land.info-owner` / `land.info-unclaimed` / `land.pvp-state-on` / `land.pvp-state-off` / `land.trust-usage` / `land.untrust-usage` / `land.trust-added` / `land.trust-removed` / `land.trustlist-header` / `land.trustlist-entry` / `land.trustlist-empty` / `land.pvp-usage` / `land.pvp-enabled` / `land.pvp-disabled` / `land.pvp-blocked` / `land.protected-block` / `land.list` / `land.help`(リスト)。
- Produces: `plugin.yml`コマンド`land`、権限`stellaria.land`(default true)・`stellaria.land.admin`(default op)。
- Produces: DBテーブル `land_claims`(world, chunk_x, chunk_z, owner_uuid, territory_id, claimed_at / PK(world,chunk_x,chunk_z)) / `land_territories`(territory_id PK, pvp_enabled) / `land_trusts`(territory_id, trusted_uuid / PK(territory_id,trusted_uuid)) — Task 2で読み書きする。

- [ ] **Step 1: `config.yml`に`land`セクションを追加**

`src/main/resources/config.yml`の末尾（`kikori:`セクションの後）に追記:

```yaml

land:
  enabled-worlds: ["world"]
  cost-per-chunk: 500
  max-chunks-per-player: 20
  refund-on-unclaim: true
  protect:
    explosions: true
    fire: true
  protected-interactables: ["CHEST","TRAPPED_CHEST","BARREL","ENDER_CHEST","SHULKER_BOX","FURNACE","BLAST_FURNACE","SMOKER","HOPPER","DISPENSER","DROPPER","BREWING_STAND","ANVIL"]
  border-particle:
    enabled: true
    particle: "DUST"
    color: "#55FF55"
    size: 1.0
    duration-seconds: 3
```

- [ ] **Step 2: `messages.yml`に`land`セクションを追加**

`src/main/resources/messages.yml`の末尾（`kikori:`セクションの後）に追記:

```yaml

land:
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  usage: "&%c使用方法: /land <claim|unclaim|info|trust|untrust|trustlist|pvp|list|help>"
  claimed: "&%aこのチャンクを保護しました！（コスト: &%e%cost%&%a）"
  already-claimed: "&%cこのチャンクは既に &%e%owner% &%cさんが保護しています。"
  unclaimed: "&%aチャンクの保護を解除しました（&%e%refund% &%aを返金）。"
  unclaimed-no-refund: "&%aチャンクの保護を解除しました。"
  not-claimed: "&%7このチャンクは誰にも保護されていません。"
  not-your-claim: "&%c自分の土地ではありません。"
  not-owner-trust: "&%c所有者だけが操作できます。"
  limit-reached: "&%c保護できる上限（&%e%max%&%cチャンク）に達しています。"
  insufficient-funds: "&%c所持金が足りません。（必要: &%e%cost%&%c）"
  world-disabled: "&%cこのワールドでは土地保護を利用できません。"
  info-owner: "&%b所有者&%7: &%f%owner% &%7/ PvP&%7: %pvp_state%"
  info-unclaimed: "&%7このチャンクは誰にも保護されていません。"
  pvp-state-on: "&%a有効"
  pvp-state-off: "&%c無効"
  trust-usage: "&%c使用方法: /land trust <プレイヤー名>"
  untrust-usage: "&%c使用方法: /land untrust <プレイヤー名>"
  trust-added: "&%a%target% &%aさんをこの土地の信頼リストに追加しました。"
  trust-removed: "&%a%target% &%aさんを信頼リストから外しました。"
  trustlist-header: "&%9&l--- 信頼リスト ---"
  trustlist-entry: "&%7- &%f%name%"
  trustlist-empty: "&%7この縄張りには誰も信頼登録されていません。"
  pvp-usage: "&%c使用方法: /land pvp <on|off>"
  pvp-enabled: "&%aこの縄張りでのPvPを有効にしました。"
  pvp-disabled: "&%cこの縄張りでのPvPを無効にしました。"
  pvp-blocked: "&%cここではPvPできません。"
  protected-block: "&%cここはあなたの土地ではありません。"
  list: "&%b保護中のチャンク&%7: &%f%chunks%&%7/&%f%max% &%7（縄張り数: &%f%territories%&%7）"
  help:
    - "&%9&l| &%bland機能の説明"
    - "&%7/land claim で今いるチャンクを保護できます（コストがかかります）"
    - "&%7隣接するclaim同士は自動で同じ縄張りとして扱われ、信頼リストとPvP設定を共有します"
    - "&%7unclaimしても縄張りが分裂することはありません（信頼リスト等はそのまま残ります）"
    - "&%7PvPはサーバー全体でデフォルト無効です。&%e/land pvp on &%7で自分の縄張り内だけ有効化できます"
```

- [ ] **Step 3: `plugin.yml`にコマンドと権限を追加**

`src/main/resources/plugin.yml`の`commands:`ブロックの`kikori:`の直後に追加:

```yaml
  kikori:
  land:
```

`permissions:`ブロックの`stellaria.warp:`の直後に追加:

```yaml
  stellaria.warp:
    default: true
  stellaria.land:
    default: true
  stellaria.land.admin:
    default: op
```

- [ ] **Step 4: `StellariaCore`にテーブル作成を追加**

`src/main/java/org/craftcore/stellaria/StellariaCore.java`の`DatabaseManager.addColumnIfNotExists("players", "kikori_unlocked INTEGER NOT NULL DEFAULT 0");`の直後（`this.afkManager = new AfkManager(this);`の直前）に追加:

```java

        DatabaseManager.createTableIfNotExists("land_claims",
            "world TEXT NOT NULL",
            "chunk_x INTEGER NOT NULL",
            "chunk_z INTEGER NOT NULL",
            "owner_uuid TEXT NOT NULL",
            "territory_id TEXT NOT NULL",
            "claimed_at INTEGER NOT NULL",
            "PRIMARY KEY (world, chunk_x, chunk_z)"
        );

        DatabaseManager.createTableIfNotExists("land_territories",
            "territory_id TEXT PRIMARY KEY",
            "pvp_enabled INTEGER NOT NULL DEFAULT 0"
        );

        DatabaseManager.createTableIfNotExists("land_trusts",
            "territory_id TEXT NOT NULL",
            "trusted_uuid TEXT NOT NULL",
            "PRIMARY KEY (territory_id, trusted_uuid)"
        );
```

- [ ] **Step 5: ビルド確認**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`。YAMLの構文エラーが無いこと。

- [ ] **Step 6: コミット**

```bash
git add src/main/resources/config.yml src/main/resources/messages.yml src/main/resources/plugin.yml src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: 土地保護機能の設定/メッセージ/DBテーブル基盤を追加"
```

---

### Task 2: `LandManager`（データモデル・キャッシュロード・claim/unclaim + 縄張りマージ）+ StellariaCore配線

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/managers/LandManager.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `ConfigManager.getStringList/getInt/getDouble`（Task 1）、`DatabaseManager.query/insert/update/execute`（既存）、`EconomyManager.has/withdrawPlayer/depositPlayer`（既存）。
- Produces: `LandManager(StellariaCore plugin)`、`record LandManager.ChunkKey(String world, int chunkX, int chunkZ)` + `static ChunkKey ChunkKey.of(Location)`、`enum LandManager.ClaimResult { SUCCESS, ALREADY_CLAIMED, LIMIT_REACHED, INSUFFICIENT_FUNDS, WORLD_DISABLED }`、`enum LandManager.ActionResult { SUCCESS, NOT_CLAIMED, NOT_OWNER }`、`ClaimResult claim(Player player)`、`ActionResult unclaim(Player player, boolean adminOverride)`、`int countClaims(UUID owner)` — Task 3〜5で使う。
- Produces: `StellariaCore#getLandManager()` → `LandManager`。

- [ ] **Step 1: `LandManager`を作成（データモデル・キャッシュロード・claim/unclaim）**

Create `src/main/java/org/craftcore/stellaria/managers/LandManager.java`:

```java
package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 土地保護（/land）のチャンク所有権と縄張り（territory）を管理する。
 * BlockBreakEvent等のホットパスから毎回SQLiteへ問い合わせるのを避けるため、
 * 起動時にDBから全件ロードしたインメモリキャッシュ（claimsByChunk/territories）で判定する。
 * 更新系メソッドはDB書き込みとキャッシュ更新を同時に行う。
 */
public class LandManager {

    /** チャンク座標のキー。ワールド名 + チャンクX/Z（ブロック座標を4ビットシフトしたもの）。 */
    public record ChunkKey(String world, int chunkX, int chunkZ) {
        public static ChunkKey of(Location location) {
            return new ChunkKey(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
    }

    /** 縄張り（隣接するclaimの集合）。信頼リストとPvP許可を共有する単位。 */
    private static final class Territory {
        boolean pvpEnabled;
        final Set<UUID> trusted = new HashSet<>();

        Territory(boolean pvpEnabled) {
            this.pvpEnabled = pvpEnabled;
        }
    }

    private record Claim(UUID owner, String territoryId) {
    }

    public enum ClaimResult { SUCCESS, ALREADY_CLAIMED, LIMIT_REACHED, INSUFFICIENT_FUNDS, WORLD_DISABLED }

    public enum ActionResult { SUCCESS, NOT_CLAIMED, NOT_OWNER }

    private static final int[][] NEIGHBOR_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final StellariaCore plugin;
    private final Map<ChunkKey, Claim> claimsByChunk = new ConcurrentHashMap<>();
    private final Map<String, Territory> territories = new ConcurrentHashMap<>();

    public LandManager(StellariaCore plugin) {
        this.plugin = plugin;
        loadFromDatabase();
    }

    // ------------------------------------------------------------------
    // 起動時ロード
    // ------------------------------------------------------------------

    private record TerritoryRow(String territoryId, boolean pvpEnabled) {
    }

    private record ClaimRow(String world, int chunkX, int chunkZ, UUID owner, String territoryId) {
    }

    private record TrustRow(String territoryId, UUID trustedUuid) {
    }

    private void loadFromDatabase() {
        List<TerritoryRow> territoryRows = DatabaseManager.query(
                "SELECT territory_id, pvp_enabled FROM land_territories",
                rs -> new TerritoryRow(rs.getString("territory_id"), rs.getInt("pvp_enabled") != 0));
        for (TerritoryRow row : territoryRows) {
            territories.put(row.territoryId(), new Territory(row.pvpEnabled()));
        }

        List<ClaimRow> claimRows = DatabaseManager.query(
                "SELECT world, chunk_x, chunk_z, owner_uuid, territory_id FROM land_claims",
                rs -> new ClaimRow(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z"),
                        UUID.fromString(rs.getString("owner_uuid")), rs.getString("territory_id")));
        for (ClaimRow row : claimRows) {
            claimsByChunk.put(new ChunkKey(row.world(), row.chunkX(), row.chunkZ()),
                    new Claim(row.owner(), row.territoryId()));
        }

        List<TrustRow> trustRows = DatabaseManager.query(
                "SELECT territory_id, trusted_uuid FROM land_trusts",
                rs -> new TrustRow(rs.getString("territory_id"), UUID.fromString(rs.getString("trusted_uuid"))));
        for (TrustRow row : trustRows) {
            Territory territory = territories.get(row.territoryId());
            if (territory != null) {
                territory.trusted.add(row.trustedUuid());
            }
        }
    }

    // ------------------------------------------------------------------
    // claim / unclaim
    // ------------------------------------------------------------------

    public int countClaims(UUID owner) {
        int count = 0;
        for (Claim claim : claimsByChunk.values()) {
            if (claim.owner().equals(owner)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 現在地のチャンクをclaimする。ワールド許可・重複・上限・残高の順にチェックし、
     * 最初に失敗した理由を返す。全て通ればコストを引き落とし、隣接claimがあれば
     * 同じ縄張りに合流（複数の縄張りに隣接していればマージ）する。
     */
    public ClaimResult claim(Player player) {
        ChunkKey key = ChunkKey.of(player.getLocation());

        List<String> enabledWorlds = plugin.getConfigManager().getStringList("land.enabled-worlds");
        if (!enabledWorlds.contains(key.world())) {
            return ClaimResult.WORLD_DISABLED;
        }
        if (claimsByChunk.containsKey(key)) {
            return ClaimResult.ALREADY_CLAIMED;
        }

        UUID owner = player.getUniqueId();
        int max = plugin.getConfigManager().getInt("land.max-chunks-per-player", 20);
        if (countClaims(owner) >= max) {
            return ClaimResult.LIMIT_REACHED;
        }

        double cost = plugin.getConfigManager().getDouble("land.cost-per-chunk", 500);
        EconomyManager economy = plugin.getEconomyManager();
        if (cost > 0 && !economy.has(player, cost)) {
            return ClaimResult.INSUFFICIENT_FUNDS;
        }
        if (cost > 0) {
            economy.withdrawPlayer(player, cost);
        }

        String territoryId = resolveTerritoryForNewClaim(key, owner);

        DatabaseManager.insert("land_claims", Map.of(
                "world", key.world(),
                "chunk_x", key.chunkX(),
                "chunk_z", key.chunkZ(),
                "owner_uuid", owner.toString(),
                "territory_id", territoryId,
                "claimed_at", System.currentTimeMillis()
        ));
        claimsByChunk.put(key, new Claim(owner, territoryId));

        return ClaimResult.SUCCESS;
    }

    /**
     * 隣接4方向のうち自分が持つclaimの縄張りIDを集める。0件なら新規縄張り、1件ならそれを再利用、
     * 2件以上ならcanonical（最初に見つかったもの）へ全部マージする。
     */
    private String resolveTerritoryForNewClaim(ChunkKey key, UUID owner) {
        Set<String> adjacentTerritories = new LinkedHashSet<>();
        for (int[] offset : NEIGHBOR_OFFSETS) {
            ChunkKey neighborKey = new ChunkKey(key.world(), key.chunkX() + offset[0], key.chunkZ() + offset[1]);
            Claim neighborClaim = claimsByChunk.get(neighborKey);
            if (neighborClaim != null && neighborClaim.owner().equals(owner)) {
                adjacentTerritories.add(neighborClaim.territoryId());
            }
        }

        if (adjacentTerritories.isEmpty()) {
            String territoryId = UUID.randomUUID().toString();
            territories.put(territoryId, new Territory(false));
            DatabaseManager.insert("land_territories", Map.of("territory_id", territoryId, "pvp_enabled", 0));
            return territoryId;
        }

        Iterator<String> iterator = adjacentTerritories.iterator();
        String canonicalId = iterator.next();
        while (iterator.hasNext()) {
            mergeTerritory(iterator.next(), canonicalId);
        }
        return canonicalId;
    }

    /** mergedIdの全claim・信頼リストをcanonicalIdへ付け替え、mergedId側の縄張りは削除する。 */
    private void mergeTerritory(String mergedId, String canonicalId) {
        Territory canonical = territories.get(canonicalId);
        Territory merged = territories.remove(mergedId);
        if (merged != null) {
            canonical.trusted.addAll(merged.trusted);
        }

        for (Map.Entry<ChunkKey, Claim> entry : claimsByChunk.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.territoryId().equals(mergedId)) {
                entry.setValue(new Claim(claim.owner(), canonicalId));
            }
        }

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
    }

    /**
     * 現在地のチャンクのclaimを解除する。adminOverrideがfalseの場合、実行者がオーナーでなければ失敗する。
     * 返金は常に元のオーナーに対して行う（adminOverrideで他人のclaimを解除した場合も同じ）。
     * 解除後、その縄張りを参照するclaimが無くなったら縄張り・信頼リストも削除する。
     */
    public ActionResult unclaim(Player player, boolean adminOverride) {
        ChunkKey key = ChunkKey.of(player.getLocation());
        Claim claim = claimsByChunk.get(key);
        if (claim == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (!adminOverride && !claim.owner().equals(player.getUniqueId())) {
            return ActionResult.NOT_OWNER;
        }

        claimsByChunk.remove(key);
        DatabaseManager.execute("DELETE FROM land_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?",
                key.world(), key.chunkX(), key.chunkZ());

        if (plugin.getConfigManager().getBoolean("land.refund-on-unclaim", true)) {
            double cost = plugin.getConfigManager().getDouble("land.cost-per-chunk", 500);
            plugin.getEconomyManager().depositPlayer(Bukkit.getOfflinePlayer(claim.owner()), cost);
        }

        boolean territoryStillUsed = claimsByChunk.values().stream()
                .anyMatch(c -> c.territoryId().equals(claim.territoryId()));
        if (!territoryStillUsed) {
            territories.remove(claim.territoryId());
            DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ?", claim.territoryId());
            DatabaseManager.execute("DELETE FROM land_territories WHERE territory_id = ?", claim.territoryId());
        }

        return ActionResult.SUCCESS;
    }
}
```

- [ ] **Step 2: `StellariaCore`に配線**

`src/main/java/org/craftcore/stellaria/StellariaCore.java`で、`private KikoriManager kikoriManager;`の直後にフィールドを追加:

```java
    private LandManager landManager;
```

`this.kikoriManager = new KikoriManager(this);`の直後に構築を追加:

```java
        this.kikoriManager = new KikoriManager(this);
        this.landManager = new LandManager(this);
```

`getKikoriManager()`ゲッターの直後にゲッターを追加:

```java
    public LandManager getLandManager() {
        return this.landManager;
    }
```

- [ ] **Step 3: ビルド確認**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/LandManager.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: LandManagerのデータモデル/キャッシュロード/claim/unclaimを追加"
```

---

### Task 3: `LandManager`に信頼(trust)・PvPトグル・照会系メソッドを追加

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/managers/LandManager.java`

**Interfaces:**
- Consumes: Task 2で定義した`ChunkKey`/`Claim`/`Territory`/`ActionResult`/`claimsByChunk`/`territories`。
- Produces: `UUID ownerOf(Location location)`、`boolean canBuild(Location location, Player player)`、`boolean isPvpAllowed(Location location)`、`ActionResult trust(Player owner, UUID target)`、`ActionResult untrust(Player owner, UUID target)`、`Set<UUID> trustedPlayers(Location location)`、`ActionResult setPvpEnabled(Player owner, boolean enabled)`、`int territoryCountFor(UUID owner)` — Task 4・5で使う。

- [ ] **Step 1: 照会・信頼・PvPメソッドを追加**

（`HashSet`/`Set`/`Map`/`UUID`はTask 2で既にimport済みのため、追加のimportは不要。）

`LandManager.java`の`unclaim(...)`メソッドの直後（クラスの閉じ括弧の直前）に、以下をまるごと追加:

```java

    // ------------------------------------------------------------------
    // 照会
    // ------------------------------------------------------------------

    /** 現在地のオーナー。未claimならnull。 */
    public UUID ownerOf(Location location) {
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        return claim != null ? claim.owner() : null;
    }

    /**
     * このプレイヤーがこの場所でブロック操作できるか。
     * 管理者権限（stellaria.land.admin）・未claim地・オーナー本人・縄張りの信頼リストのいずれかでtrue。
     */
    public boolean canBuild(Location location, Player player) {
        if (player.hasPermission("stellaria.land.admin")) {
            return true;
        }
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        if (claim == null) {
            return true;
        }
        if (claim.owner().equals(player.getUniqueId())) {
            return true;
        }
        Territory territory = territories.get(claim.territoryId());
        return territory != null && territory.trusted.contains(player.getUniqueId());
    }

    /** この場所でPvPが許可されているか。claimが存在し、かつその縄張りのpvp_enabledがtrueの時だけtrue。 */
    public boolean isPvpAllowed(Location location) {
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        if (claim == null) {
            return false;
        }
        Territory territory = territories.get(claim.territoryId());
        return territory != null && territory.pvpEnabled;
    }

    /** 現在地の縄張りの信頼リスト。未claimなら空集合。 */
    public Set<UUID> trustedPlayers(Location location) {
        Claim claim = claimsByChunk.get(ChunkKey.of(location));
        if (claim == null) {
            return Set.of();
        }
        Territory territory = territories.get(claim.territoryId());
        return territory != null ? Set.copyOf(territory.trusted) : Set.of();
    }

    /** ownerが所有するclaimが属する縄張りの数（重複排除済み）。 */
    public int territoryCountFor(UUID owner) {
        Set<String> ids = new HashSet<>();
        for (Claim claim : claimsByChunk.values()) {
            if (claim.owner().equals(owner)) {
                ids.add(claim.territoryId());
            }
        }
        return ids.size();
    }

    // ------------------------------------------------------------------
    // 信頼(trust) / PvPトグル
    // ------------------------------------------------------------------

    /** 現在地の縄張りに信頼プレイヤーを追加する。実行者がオーナーである必要がある。 */
    public ActionResult trust(Player owner, UUID target) {
        Claim claim = claimsByChunk.get(ChunkKey.of(owner.getLocation()));
        if (claim == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (!claim.owner().equals(owner.getUniqueId())) {
            return ActionResult.NOT_OWNER;
        }
        Territory territory = territories.get(claim.territoryId());
        if (territory.trusted.add(target)) {
            DatabaseManager.insert("land_trusts", Map.of(
                    "territory_id", claim.territoryId(),
                    "trusted_uuid", target.toString()
            ));
        }
        return ActionResult.SUCCESS;
    }

    /** 現在地の縄張りから信頼プレイヤーを外す。実行者がオーナーである必要がある。 */
    public ActionResult untrust(Player owner, UUID target) {
        Claim claim = claimsByChunk.get(ChunkKey.of(owner.getLocation()));
        if (claim == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (!claim.owner().equals(owner.getUniqueId())) {
            return ActionResult.NOT_OWNER;
        }
        Territory territory = territories.get(claim.territoryId());
        if (territory.trusted.remove(target)) {
            DatabaseManager.execute("DELETE FROM land_trusts WHERE territory_id = ? AND trusted_uuid = ?",
                    claim.territoryId(), target.toString());
        }
        return ActionResult.SUCCESS;
    }

    /** 現在地の縄張りのPvP許可を切り替える。実行者がオーナーである必要がある。 */
    public ActionResult setPvpEnabled(Player owner, boolean enabled) {
        Claim claim = claimsByChunk.get(ChunkKey.of(owner.getLocation()));
        if (claim == null) {
            return ActionResult.NOT_CLAIMED;
        }
        if (!claim.owner().equals(owner.getUniqueId())) {
            return ActionResult.NOT_OWNER;
        }
        Territory territory = territories.get(claim.territoryId());
        territory.pvpEnabled = enabled;
        DatabaseManager.update("land_territories", Map.of("pvp_enabled", enabled ? 1 : 0),
                "territory_id = ?", claim.territoryId());
        return ActionResult.SUCCESS;
    }
```

- [ ] **Step 2: ビルド確認**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/managers/LandManager.java
git commit -m "feat: LandManagerに信頼/PvPトグル/照会系メソッドを追加"
```

---

### Task 4: `LandProtectionListener`（保護イベント7種）+ KikoriListener連携 + StellariaCore配線

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/listeners/LandProtectionListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/listeners/KikoriListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `LandManager.canBuild/isPvpAllowed/ownerOf`（Task 2・3）、`ConfigManager.getMessage/getBoolean/getStringList`（既存・Task 1）。
- Produces: なし（配線のみ）。

- [ ] **Step 1: `LandProtectionListener`を作成**

Create `src/main/java/org/craftcore/stellaria/listeners/LandProtectionListener.java`:

```java
package org.craftcore.stellaria.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.craftcore.stellaria.StellariaCore;

import java.util.List;

/**
 * 土地保護（/land）のイベント判定。判定の中心は
 * LandManager#canBuild（ブロック操作・PvP以外）とLandManager#isPvpAllowed（PvPのみ）の2つ。
 */
public class LandProtectionListener implements Listener {

    private final StellariaCore plugin;

    public LandProtectionListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /**
     * LOW優先度で登録する（StellariaCore側）。KikoriListener#onBlockBreakより先に評価させ、
     * ここでキャンセルしたブロックについては連鎖伐採の起動判定自体が走らないようにするため。
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isProtectedInteractable(block.getType())) {
            return;
        }
        if (!plugin.getLandManager().canBuild(block.getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getConfigManager().getMessage("land.protected-block", event.getPlayer()));
        }
    }

    private boolean isProtectedInteractable(Material material) {
        List<String> names = plugin.getConfigManager().getStringList("land.protected-interactables");
        return names.contains(material.name());
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.hasPermission("stellaria.land.admin")) {
            return;
        }
        if (!plugin.getLandManager().isPvpAllowed(victim.getLocation())) {
            event.setCancelled(true);
            attacker.sendMessage(plugin.getConfigManager().getMessage("land.pvp-blocked", attacker));
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.explosions", true)) {
            return;
        }
        event.blockList().removeIf(block -> plugin.getLandManager().ownerOf(block.getLocation()) != null);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.explosions", true)) {
            return;
        }
        event.blockList().removeIf(block -> plugin.getLandManager().ownerOf(block.getLocation()) != null);
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.fire", true)) {
            return;
        }
        if (plugin.getLandManager().ownerOf(event.getBlock().getLocation()) == null) {
            return;
        }
        Player igniter = event.getPlayer();
        if (igniter != null && plugin.getLandManager().canBuild(event.getBlock().getLocation(), igniter)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.fire", true)) {
            return;
        }
        if (plugin.getLandManager().ownerOf(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }
}
```

- [ ] **Step 2: `KikoriListener#onBlockBreak`に`ignoreCancelled = true`を追加**

`src/main/java/org/craftcore/stellaria/listeners/KikoriListener.java`の`onBlockBreak`メソッドのアノテーションを変更:

```java
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
```

（`@EventHandler` 1行だけを `@EventHandler(ignoreCancelled = true)` に置き換える。メソッド本体は変更しない。）

- [ ] **Step 3: `StellariaCore`にリスナー登録を追加**

`src/main/java/org/craftcore/stellaria/StellariaCore.java`のimportブロックに追加（`import org.craftcore.stellaria.listeners.KikoriListener;`の直後）:

```java
import org.craftcore.stellaria.listeners.LandProtectionListener;
```

`getServer().getPluginManager().registerEvents(new KikoriListener(this), this);`の直後に登録を追加:

```java
        getServer().getPluginManager().registerEvents(new LandProtectionListener(this), this);
```

- [ ] **Step 4: ビルド確認**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/listeners/LandProtectionListener.java src/main/java/org/craftcore/stellaria/listeners/KikoriListener.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: LandProtectionListenerを追加しブロック/PvP/爆発/延焼を保護"
```

---

### Task 5: `/land`コマンド + 境界パーティクル演出

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/LandCommand.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Consumes: `LandManager`の全メソッド（Task 2・3）、`ConfigManager.getMessage/getMessageList/getInt/getDouble/getBoolean/getString`（既存・Task 1）、`EconomyManager.format`（既存）、`FormatUtil.replace/text`（既存）、`TabCompleteUtil.filterStartsWith/onlinePlayerNames`（既存）、`ParticleUtil.spawnLine/parseColor`（既存）。
- Produces: なし（コマンドは末端機能）。

- [ ] **Step 1: `LandCommand`を作成**

Create `src/main/java/org/craftcore/stellaria/commands/LandCommand.java`:

```java
package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.LandManager;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.ParticleUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /land コマンド。claim/unclaim/info/trust/untrust/trustlist/pvp/list/helpの9サブコマンドを
 * command.getName()ではなくargs[0]でディスパッチする（コマンド自体は"land"の1つだけのため）。
 */
public class LandCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "claim", "unclaim", "info", "trust", "untrust", "trustlist", "pvp", "list", "help");

    private final StellariaCore plugin;

    public LandCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("land.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.land")) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.no_permission", player));
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "claim" -> handleClaim(player);
            case "unclaim" -> handleUnclaim(player);
            case "info" -> handleInfo(player);
            case "trust" -> handleTrust(player, args);
            case "untrust" -> handleUntrust(player, args);
            case "trustlist" -> handleTrustList(player);
            case "pvp" -> handlePvp(player, args);
            case "list" -> handleList(player);
            case "help" -> handleHelp(player);
            default -> player.sendMessage(plugin.getConfigManager().getMessage("land.usage", player));
        }
        return true;
    }

    private void handleClaim(Player player) {
        LandManager.ChunkKey key = LandManager.ChunkKey.of(player.getLocation());
        LandManager.ClaimResult result = plugin.getLandManager().claim(player);
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(FormatUtil.replace(
                        plugin.getConfigManager().getMessage("land.claimed", player), "%cost%", costText()));
                showClaimBorder(player, key);
            }
            case ALREADY_CLAIMED -> {
                UUID owner = plugin.getLandManager().ownerOf(player.getLocation());
                player.sendMessage(FormatUtil.replace(
                        plugin.getConfigManager().getMessage("land.already-claimed", player),
                        "%owner%", ownerName(owner)));
            }
            case LIMIT_REACHED -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.limit-reached", player),
                    "%max%", String.valueOf(plugin.getConfigManager().getInt("land.max-chunks-per-player", 20))));
            case INSUFFICIENT_FUNDS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.insufficient-funds", player), "%cost%", costText()));
            case WORLD_DISABLED -> player.sendMessage(
                    plugin.getConfigManager().getMessage("land.world-disabled", player));
        }
    }

    private void handleUnclaim(Player player) {
        boolean adminOverride = player.hasPermission("stellaria.land.admin");
        LandManager.ActionResult result = plugin.getLandManager().unclaim(player, adminOverride);
        switch (result) {
            case SUCCESS -> {
                boolean refunded = plugin.getConfigManager().getBoolean("land.refund-on-unclaim", true);
                String key = refunded ? "land.unclaimed" : "land.unclaimed-no-refund";
                String message = plugin.getConfigManager().getMessage(key, player);
                if (refunded) {
                    message = FormatUtil.replace(message, "%refund%", costText());
                }
                player.sendMessage(message);
            }
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-claimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-your-claim", player));
        }
    }

    private void handleInfo(Player player) {
        UUID owner = plugin.getLandManager().ownerOf(player.getLocation());
        if (owner == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.info-unclaimed", player));
            return;
        }
        boolean pvpAllowed = plugin.getLandManager().isPvpAllowed(player.getLocation());
        String pvpState = plugin.getConfigManager().getMessage(
                pvpAllowed ? "land.pvp-state-on" : "land.pvp-state-off", player);
        String message = plugin.getConfigManager().getMessage("land.info-owner", player);
        message = FormatUtil.replace(message, "%owner%", ownerName(owner));
        message = FormatUtil.replace(message, "%pvp_state%", pvpState);
        player.sendMessage(message);
    }

    private void handleTrust(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.trust-usage", player));
            return;
        }
        UUID target = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        LandManager.ActionResult result = plugin.getLandManager().trust(player, target);
        sendTrustResult(player, result, "land.trust-added", args[1]);
    }

    private void handleUntrust(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.untrust-usage", player));
            return;
        }
        UUID target = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        LandManager.ActionResult result = plugin.getLandManager().untrust(player, target);
        sendTrustResult(player, result, "land.trust-removed", args[1]);
    }

    private void sendTrustResult(Player player, LandManager.ActionResult result, String successKey, String targetName) {
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage(successKey, player), "%target%", targetName));
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-claimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-owner-trust", player));
        }
    }

    private void handleTrustList(Player player) {
        UUID owner = plugin.getLandManager().ownerOf(player.getLocation());
        if (owner == null) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.info-unclaimed", player));
            return;
        }
        Set<UUID> trusted = plugin.getLandManager().trustedPlayers(player.getLocation());
        if (trusted.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.trustlist-empty", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("land.trustlist-header", player));
        for (UUID uuid : trusted) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("land.trustlist-entry", player), "%name%", ownerName(uuid)));
        }
    }

    private void handlePvp(Player player, String[] args) {
        if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            player.sendMessage(plugin.getConfigManager().getMessage("land.pvp-usage", player));
            return;
        }
        boolean enable = args[1].equalsIgnoreCase("on");
        LandManager.ActionResult result = plugin.getLandManager().setPvpEnabled(player, enable);
        switch (result) {
            case SUCCESS -> player.sendMessage(plugin.getConfigManager().getMessage(
                    enable ? "land.pvp-enabled" : "land.pvp-disabled", player));
            case NOT_CLAIMED -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-claimed", player));
            case NOT_OWNER -> player.sendMessage(plugin.getConfigManager().getMessage("land.not-owner-trust", player));
        }
    }

    private void handleList(Player player) {
        int chunks = plugin.getLandManager().countClaims(player.getUniqueId());
        int territories = plugin.getLandManager().territoryCountFor(player.getUniqueId());
        int max = plugin.getConfigManager().getInt("land.max-chunks-per-player", 20);
        String message = plugin.getConfigManager().getMessage("land.list", player);
        message = FormatUtil.replace(message, "%chunks%", String.valueOf(chunks));
        message = FormatUtil.replace(message, "%max%", String.valueOf(max));
        message = FormatUtil.replace(message, "%territories%", String.valueOf(territories));
        player.sendMessage(message);
    }

    private void handleHelp(Player player) {
        for (String line : plugin.getConfigManager().getMessageList("land.help")) {
            player.sendMessage(FormatUtil.text(player, line));
        }
    }

    private String costText() {
        return plugin.getEconomyManager().format(plugin.getConfigManager().getDouble("land.cost-per-chunk", 500));
    }

    private String ownerName(UUID uuid) {
        if (uuid == null) {
            return "?";
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name != null ? name : uuid.toString();
    }

    /**
     * claim直後、そのチャンクの4辺の境界にパーティクルを一定時間（land.border-particle.duration-seconds）
     * 表示する。0.5秒間隔で描き直すことで持続しているように見せる（TpaCore/HomeCommandのテレポート演出と
     * 同じくParticleUtilを使うが、円ではなく矩形の4辺をspawnLineで描く点が異なる）。
     */
    private void showClaimBorder(Player player, LandManager.ChunkKey key) {
        if (!plugin.getConfigManager().getBoolean("land.border-particle.enabled", true)) {
            return;
        }
        World world = player.getWorld();
        double minX = key.chunkX() * 16.0;
        double minZ = key.chunkZ() * 16.0;
        double maxX = minX + 16.0;
        double maxZ = minZ + 16.0;
        double y = player.getLocation().getY();

        Location nw = new Location(world, minX, y, minZ);
        Location ne = new Location(world, maxX, y, minZ);
        Location se = new Location(world, maxX, y, maxZ);
        Location sw = new Location(world, minX, y, maxZ);

        Particle particle = Particle.valueOf(plugin.getConfigManager().getString("land.border-particle.particle", "DUST"));
        int durationSeconds = plugin.getConfigManager().getInt("land.border-particle.duration-seconds", 3);
        int totalRuns = Math.max(1, durationSeconds * 2);
        int[] runsLeft = {totalRuns};

        if (particle == Particle.DUST) {
            Color color = ParticleUtil.parseColor(plugin.getConfigManager().getString("land.border-particle.color", "#55FF55"));
            float size = (float) plugin.getConfigManager().getDouble("land.border-particle.size", 1.0);
            Bukkit.getRegionScheduler().runAtFixedRate(plugin, nw, task -> {
                ParticleUtil.spawnLine(nw, ne, 0.5, color, size);
                ParticleUtil.spawnLine(ne, se, 0.5, color, size);
                ParticleUtil.spawnLine(se, sw, 0.5, color, size);
                ParticleUtil.spawnLine(sw, nw, 0.5, color, size);
                if (--runsLeft[0] <= 0) {
                    task.cancel();
                }
            }, 1L, 10L);
        } else {
            Bukkit.getRegionScheduler().runAtFixedRate(plugin, nw, task -> {
                ParticleUtil.spawnLine(nw, ne, 0.5, particle);
                ParticleUtil.spawnLine(ne, se, 0.5, particle);
                ParticleUtil.spawnLine(se, sw, 0.5, particle);
                ParticleUtil.spawnLine(sw, nw, 0.5, particle);
                if (--runsLeft[0] <= 0) {
                    task.cancel();
                }
            }, 1L, 10L);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            return TabCompleteUtil.onlinePlayerNames(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pvp")) {
            return TabCompleteUtil.filterStartsWith(List.of("on", "off"), args[1]);
        }
        return List.of();
    }
}
```

- [ ] **Step 2: コマンド登録**

`src/main/java/org/craftcore/stellaria/StellariaCore.java`のimportブロックに追加（`import org.craftcore.stellaria.commands.KikoriCommand;`の直後）:

```java
import org.craftcore.stellaria.commands.LandCommand;
```

`KikoriCommand`登録ブロック（`getCommand("kikori").setTabCompleter(kikoriCommand);`）の直後、`this.autoBroadcastManager = new AutoBroadcastManager(this);`の直前に追加:

```java

        LandCommand landCommand = new LandCommand(this);
        getCommand("land").setExecutor(landCommand);
        getCommand("land").setTabCompleter(landCommand);
```

- [ ] **Step 3: ビルド確認**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: コミット**

```bash
git add src/main/java/org/craftcore/stellaria/commands/LandCommand.java src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat: /land コマンドと境界パーティクル演出を追加"
```

---

### Task 6: 実機での動作確認

**Files:** none（確認のみ）。

**Interfaces:** none。

このタスクはコードを書かない。ここまでのTask 1〜5で機能は実装済みなので、`./gradlew build`でjarを作り、実機（本番相当環境）に導入して以下を確認する。ユーザー側で実施する想定（`runServer`は使わない）。

- [ ] **Step 1: 最終ビルド**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`。`build/libs/StellariaCore-<version>.jar`が生成される。

- [ ] **Step 2: 実機チェックリスト**

以下を実機で確認する（`docs/superpowers/specs/2026-09-15-land-protection-design.md`のテスト方針セクションと同一）:

- claimの基本動作: 所持金を用意して`/land claim`→成功メッセージ+残高減少。同じチャンクで再度`/land claim`→`already-claimed`。上限まで埋めて`limit-reached`確認。残高不足で`insufficient-funds`確認。`land.enabled-worlds`に無いワールドで`world-disabled`確認
- 境界パーティクル: claim直後に境界に一瞬パーティクルが表示されること（`land.border-particle.duration-seconds`秒間）
- 保護動作: 別プレイヤーでclaim済みチャンクのブロックを壊す/置く→キャンセルされ`protected-block`表示。オーナー本人・`stellaria.land.admin`持ちは操作できること
- コンテナ保護: `land.protected-interactables`に含まれるブロック（チェスト等）を非オーナーが開けない、オーナー・trustedは開けること
- 爆発・延焼: claim済みチャンク付近でTNT爆破→claim内のブロックだけ残り、claim外は通常通り破壊されること。claim境界に燃え移る火が中に入らないこと
- 縄張りグルーピング: 隣接する2チャンクを同一プレイヤーがclaim→同じ縄張りとして扱われる（片方で`trust`した相手がもう片方でも建築できる）こと。離れたチャンクをclaimした場合は別縄張りになる（`trust`が反映されない）こと。2つの離れた縄張りを繋ぐようにclaimしたら統合され、統合前に個別に`trust`していた相手が両方の元claimで建築できるようになること
- unclaimと縄張りの非分裂: 縄張りの中間チャンクだけunclaimしても、残ったチャンク同士の信頼リスト・PvP設定が引き続き共有されること
- 信頼(trust): `/land trust`されたプレイヤーがブロック操作・コンテナアクセスできるようになること、`/land untrust`で解除されること、オーナー以外が`trust`実行→`not-owner-trust`。未claim地で`trust`実行→`not-claimed`
- PvP: 未claim地でプレイヤー同士を攻撃→常にキャンセルされ`pvp-blocked`。claim済みだが`pvp_enabled=false`の縄張り内→同様にキャンセル。`/land pvp on`後の同じ縄張り内→ダメージが通ること。`stellaria.land.admin`持ちは常に攻撃できる（被害者側）こと
- unclaimの返金: `/land unclaim`実行→`land.cost-per-chunk`分がプレイヤーの残高に戻ること（`land.refund-on-unclaim: false`にすると返金されないこと）
- Kikori連携: 他人のclaim内にある天然木の根元をclaim外から伐採しようとする起点ブロックが保護され`BlockBreakEvent`がキャンセルされること、連鎖伐採も発動しないこと。claim内・境界をまたぐ木を伐採→claim内の丸太だけ残り、claim外の丸太は連鎖伐採されること（`ignoreCancelled=true`の効果確認）
- `/land info`・`/land list`・`/land trustlist`・`/land help`が想定通り表示されること
- `/land`のタブ補完: 1個目の引数でサブコマンド一覧が出ること、`trust`/`untrust`の2個目でオンラインプレイヤー名が出ること、`pvp`の2個目で`on`/`off`が出ること

- [ ] **Step 3: 問題があれば個別に修正コミット**

チェックリストで問題が見つかった場合は、`fix:`プレフィックスで個別にコミットする。問題が無ければこのタスクでのコミットは不要。
