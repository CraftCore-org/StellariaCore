# 高速トロッコシステム (Stellaria Rail) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** バニラのレール/トロッコの上に、駅から発車した特定のトロッコだけを対象にした独自の高速走行制御（速度・加速度・カーブ/坂道減速・駅到着停止・チャンク先読み）を追加する。通常のトロッコ挙動・既存機能には一切影響を与えない。

**Architecture:** 新規パッケージ `org.craftcore.stellaria.rail` に、駅データ(DB永続化)を管理する `RailStationManager`、レール形状→速度の純粋なジオメトリ計算を行う `RailSpeedController`、高速モード中のトロッコ1台分の状態を持つ `RailSession`、セッションのライフサイクルと毎tickの速度更新・チャンク先読みを行う `RailManager`、Bukkitイベントとの橋渡しをする `RailListener` を置く。config値は `RailConfig` にキャッシュして毎tickのホットパスから `ConfigManager` への文字列引きを避ける。`/rail` コマンド (`commands/RailCommand.java`、既存の `HomeCommand`/`WarpCommand` と同じ置き場所) で駅の登録・削除・一覧と `/rail depart` (MVPでの発車トリガー) を提供する。状態の真実源はメモリ上の `Map<UUID, RailSession>` (KikoriManager/TpaCoreと同じ「インメモリのみ・サーバー再起動でリセット」方式)。`PersistentDataContainer` の `stellaria:rail_mode` タグは外部から見て分かるようにするための付随マーカーであり、判定ロジックはセッションMapの有無で行う。

**Tech Stack:** Java 21 / PaperMC 1.21.11 API (`org.bukkit.block.data.Rail`, `VehicleMoveEvent`, `Minecart#setMaxSpeed`, `World#addPluginChunkTicket`)。**訂正:** 当初このPlanは「このリポジトリに自動テストは無い」という前提（CLAUDE.mdの記述）で書いたが、worktree作成後にベースライン確認で `src/test/java` 配下にJUnit 5のテストが28ファイル実在し、`build.gradle.kts` に `tasks.test { useJUnitPlatform() }` が設定済み、`./gradlew test` が通ることを確認した（CLAUDE.md側が古い）。既存テストはBukkitランタイムに依存しない純粋ロジック（`utils/`の関数、マネージャー内のstatic/package-privateなロジック）だけを対象にしており、Mockito等のモックライブラリは使っていない。このPlanでもその方針を踏襲する: **`RailSpeedController`（Task 4）のようにBukkitランタイムなしでテスト可能な純粋ロジックには実際にJUnitテストを書く**。`RailManager`/`RailStationManager`/`RailListener`/`RailCommand`のようにBlock/World/Player/DBといった実サーバー・DB状態に依存するクラスは、既存の`KikoriManager`/`WarpManager`等と同様に自動テスト対象外とし、`./gradlew build`でのコンパイル確認 + `./gradlew runServer`での実機確認に頼る。

**Spec:** 本Planはユーザーが別セッションのClaudeと合意した設計書（会話内で共有された「Stellaria 高速トロッコシステム 設計書」、以下「元設計書」）を元にしている。元設計書はこのファイルに添付されていないため、対応関係は各タスクの冒頭に元設計書のどの章を実装するかを明記する形で示す。

## Global Constraints

- 通常のトロッコ（PDCタグ無し = `RailManager`のセッションMapに存在しない）には一切干渉しない。判定は必ず `sessions.containsKey(minecart.getUniqueId())` を経由すること。
- 料金システムは実装しない（`rail.fare.enabled: false` の値だけ置いておく。元設計書「料金」章）。
- 自動分岐・路線検索・複数駅を跨ぐ目的地選択は実装しない（元設計書「初期実装範囲」で明示的に除外）。
- 全てのプレイヤー向け文言は `messages.yml` の `rail.*` キー経由、`ConfigManager.getMessage`/`getUsageMessage` を必ず通す（`plugin.getConfig()` 直読み禁止、CLAUDE.mdの既存注意事項と同じ）。
- 速度・加速度・チャンク先読み距離などの挙動値は全て `config.yml` の `rail.*` から取得し、ハードコードしない。
- Velocityに `NaN`/`Infinite` が混入した場合は即座にセッションを強制終了する（安全対策章）。
- `rail_stations` テーブルの追加は本番DBスキーマ変更に該当する。Task 1完了時に必ずコールアウトすること（CLAUDE.md「Production deployment notice」）。

---

## Task 1: `rail_stations` テーブルと `RailStationManager`

対応: 元設計書「駅システム」「クラス構成案 > RailStationManager」

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java` (DBテーブル作成ブロックにのみ追記。マネージャー配線はTask 8でまとめて行う)
- Create: `src/main/java/org/craftcore/stellaria/rail/RailStationManager.java`

**Interfaces:**
- Produces: `RailStationManager.Station` record (`name: String`, `location: Location`, `direction: BlockFace`)、`RailStationManager.CreateResult` enum (`SUCCESS`, `NAME_TAKEN`, `DATABASE_ERROR`)、`RailStationManager#loadAll()`, `#create(String, Location, BlockFace): CreateResult`, `#remove(String): boolean`, `#get(String): Station`(nullable), `#listAll(): List<Station>`, `#findWithin(Location, double): Station`(nullable, 最寄り1件)

- [ ] **Step 1: `rail_stations` テーブルをDB作成ブロックに追加する**

`src/main/java/org/craftcore/stellaria/StellariaCore.java` の `DatabaseManager.createTableIfNotExists("warps", ...)` の直後に追加:

```java
DatabaseManager.createTableIfNotExists("rail_stations",
    "name TEXT PRIMARY KEY",
    "world TEXT NOT NULL",
    "x REAL NOT NULL", "y REAL NOT NULL", "z REAL NOT NULL",
    "direction TEXT NOT NULL",
    "created_at INTEGER NOT NULL"
);
```

- [ ] **Step 2: `RailStationManager` を作成する**

```java
package org.craftcore.stellaria.rail;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.DatabaseManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * rail_stationsテーブル（name主キー）へのCRUDと、毎tickのホットパスから叩かれる駅検索を担当する。
 * MuteManagerと同様、判定頻度が高い（VehicleMoveEventのたび）ためDBに毎回問い合わせず、
 * 起動時に全件をメモリキャッシュしてから使う。
 */
public class RailStationManager {

    public record Station(String name, Location location, BlockFace direction) {
    }

    public enum CreateResult { SUCCESS, NAME_TAKEN, DATABASE_ERROR }

    private final StellariaCore plugin;
    private final Map<String, Station> stations = new ConcurrentHashMap<>();

    public RailStationManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** onEnableで1回呼ぶ。rail_stationsの全行をメモリに読み込む。 */
    public void loadAll() {
        stations.clear();
        List<Station> loaded = DatabaseManager.query(
                "SELECT name, world, x, y, z, direction FROM rail_stations",
                RailStationManager::mapStation
        );
        for (Station station : loaded) {
            if (station != null) {
                stations.put(station.name().toLowerCase(), station);
            }
        }
    }

    private static Station mapStation(ResultSet rs) throws SQLException {
        World world = Bukkit.getWorld(rs.getString("world"));
        if (world == null) {
            return null; // ワールド削除等で参照先が無い駅は無視する
        }
        Location location = new Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"));
        BlockFace direction = BlockFace.valueOf(rs.getString("direction"));
        return new Station(rs.getString("name"), location, direction);
    }

    /** /rail station add から呼ぶ。名前はサーバー全体でユニーク（大文字小文字区別なし）。 */
    public CreateResult create(String name, Location location, BlockFace direction) {
        String key = name.toLowerCase();
        if (stations.containsKey(key)) {
            return CreateResult.NAME_TAKEN;
        }
        World world = location.getWorld();
        if (world == null) {
            return CreateResult.DATABASE_ERROR;
        }
        int affected = DatabaseManager.insert("rail_stations", Map.of(
                "name", name, "world", world.getName(),
                "x", location.getX(), "y", location.getY(), "z", location.getZ(),
                "direction", direction.name(), "created_at", System.currentTimeMillis()
        ));
        if (affected != 1) {
            return CreateResult.DATABASE_ERROR;
        }
        stations.put(key, new Station(name, location.clone(), direction));
        return CreateResult.SUCCESS;
    }

    /** /rail station remove から呼ぶ。存在しなければfalse。 */
    public boolean remove(String name) {
        Station station = stations.get(name.toLowerCase());
        if (station == null) {
            return false;
        }
        DatabaseManager.execute("DELETE FROM rail_stations WHERE name = ?", station.name());
        stations.remove(name.toLowerCase());
        return true;
    }

    /** 無ければnull。 */
    public Station get(String name) {
        return stations.get(name.toLowerCase());
    }

    public List<Station> listAll() {
        return stations.values().stream()
                .sorted(Comparator.comparing(Station::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * point から radius 以内にある駅のうち最も近いものを返す（複数該当時は最短距離を優先）。
     * RailManagerが毎tick呼ぶ想定なので、DBには触れずキャッシュだけを線形走査する
     * （駅数は多くても数十件想定のため性能上問題ない）。
     */
    public Station findWithin(Location point, double radius) {
        Station nearest = null;
        double nearestDistSq = radius * radius;
        for (Station station : stations.values()) {
            World stationWorld = station.location().getWorld();
            if (stationWorld == null || !stationWorld.equals(point.getWorld())) {
                continue;
            }
            double distSq = station.location().distanceSquared(point);
            if (distSq <= nearestDistSq) {
                nearest = station;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }
}
```

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL（この時点では`RailStationManager`はまだどこからも呼ばれないので、未使用による警告のみで良い）

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/java/org/craftcore/stellaria/rail/RailStationManager.java
git commit -m "feat(rail): add rail_stations table and RailStationManager"
```

> **⚠️ 本番DB注意:** `rail_stations` は新規テーブル。既存インストールでも `DatabaseManager.createTableIfNotExists` により次回起動時に自動作成されるため手動作業は不要だが、デプロイのタイミングで一応再起動が必要な点は共有すること。

---

## Task 2: config.yml / messages.yml / plugin.yml への `rail.*` 追加

対応: 元設計書「速度制御」「加速方式」「チャンク対策」「駅システム」「料金」「表示」「安全対策」の設定値部分

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/messages.yml`
- Modify: `src/main/resources/plugin.yml`

- [ ] **Step 1: `config.yml` に `rail:` セクションを追加する**

`mine:` セクション（`max-ores: 128` の行）の直後、`land:` セクションの直前に挿入:

```yaml
rail:
  disabled-worlds: ["lobby"]
  speed:
    straight: 40.0  # 直線での最大速度 (blocks/s)
    curve: 12.0     # カーブでの最大速度 (blocks/s)
    slope: 20.0     # 坂道（上り/下り共通）での最大速度 (blocks/s)
  acceleration: 4.0       # 加速度 (blocks/s^2)
  deceleration: 8.0       # 減速度 (blocks/s^2)。カーブ・坂道・駅侵入前の制動に使う
  min-speed: 2.0          # 発車直後の初速、および「停止とみなす」しきい値 (blocks/s)
  max-velocity-clamp: 3.0 # 安全装置。1tickあたりの移動量の絶対上限 (blocks/tick)。Minecart#setMaxSpeedにも使う
  off-rail-grace-seconds: 2.0 # レールを外れてからこの秒数を超えたら高速モードを強制解除する
  chunk-preload:
    enabled: true
    distance: 2  # 進行方向に事前ロードするチャンク数
  station:
    activation-radius: 5.0  # /rail depart を実行できる、駅からの距離 (blocks)
    arrival-radius: 2.5     # この距離以内かつ速度が min-speed 以下になったら「到着」とみなす (blocks)
    slow-down-margin: 1.3   # 制動距離の計算に掛ける安全マージン（大きいほど早めに減速し始める）
  fare:
    enabled: false # 将来の有料化用。現状は未使用（常に無料）
```

- [ ] **Step 2: `messages.yml` に `rail:` セクションを追加する**

`mine:` セクション（`actionbar_enabled` の行）の直後、`features:` セクションの直前に挿入:

```yaml
rail:
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  world_disabled: "&%cこのワールドでは高速鉄道機能を利用できません。"
  usage_station_add: "&%c使用方法: /rail station add <名前>"
  usage_station_remove: "&%c使用方法: /rail station remove <名前>"
  usage_depart: "&%c使用方法: /rail depart <駅名>"
  station_created: "&%a駅「%name%」を作成しました。"
  station_name_taken: "&%c駅名「%name%」は既に使われています。"
  station_removed: "&%a駅「%name%」を削除しました。"
  station_not_found: "&%c駅「%name%」が見つかりません。"
  station_list_header: "&%b----- 駅一覧 -----"
  station_list_entry: "&%f%name% &%7(%world%)"
  station_list_empty: "&%7登録されている駅はありません。"
  must_ride_minecart: "&%cトロッコに乗った状態で実行してください。"
  not_on_rail: "&%cレールの上にいる必要があります。"
  too_far_from_station: "&%c駅「%name%」から離れすぎています。"
  already_rail_mode: "&%cこのトロッコは既に高速モードです。"
  departed: "&%b高速鉄道モード"
  arrived: "&%a%station%駅に到着しました"
  off_rail_cancelled: "&%7レールを外れたため、高速モードを解除しました。"
```

- [ ] **Step 3: `plugin.yml` にコマンドと権限を追加する**

`commands:` の `shop:` の直後に追加:

```yaml
  rail:
```

（kikori/mine と同じく、コマンド全体を権限でゲートせずコマンド内で細かく`hasPermission`する方針に合わせる）

`permissions:` の `stellaria.mine:` の直後に追加:

```yaml
  stellaria.rail:
    default: true
  stellaria.rail.admin:
    default: op
```

`stellaria.admin` の `children:` ブロックに1行追加:

```yaml
      stellaria.rail.admin: true
```

- [ ] **Step 4: コンパイル確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（YAML構文エラーが無いことも含めてShadow jarが生成されればOK）

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/config.yml src/main/resources/messages.yml src/main/resources/plugin.yml
git commit -m "feat(rail): add rail.* config, messages and permissions"
```

> **⚠️ config.yml/messages.yml 注意:** 既存本番環境の `config.yml`/`messages.yml` には `rail.*`/`rail:` キーが無い。`ConfigManager` はファイルの自動マイグレーションを行わないため、デプロイ時に既存ファイルへ手動でこのセクションを追記するか、ファイルごと差し替える必要がある（追記し忘れると `ConfigManager` が「キーが見つかりません」警告を出し、デフォルト値にフォールバックする）。

---

## Task 3: `RailConfig`（config値のキャッシュ）

対応: 元設計書「速度制御」内 "数値はconfig.ymlから変更可能にする"

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/rail/RailConfig.java`

**Interfaces:**
- Consumes: `StellariaCore#getConfigManager()` の `getDouble/getInt/getBoolean/getStringList`
- Produces: getter群（`getStraightSpeedBps()` 等）。`reload()` は `StellariaCore#reloadFeatureManagers()` からTask 8で呼ぶ。

- [ ] **Step 1: `RailConfig` を作成する**

```java
package org.craftcore.stellaria.rail;

import org.craftcore.stellaria.StellariaCore;

import java.util.List;

/**
 * config.yml の rail.* を読み込みキャッシュするクラス。
 * RailManagerの毎tickの速度計算はVehicleMoveEventのたびに走るホットパスのため、
 * ConfigManager(ひいてはYamlConfiguration)への文字列引きを毎回行わずここでまとめて保持する。
 * /stellariareload 時は StellariaCore#reloadFeatureManagers() から reload() が呼ばれる。
 */
public final class RailConfig {

    private final StellariaCore plugin;

    private volatile double straightSpeedBps;
    private volatile double curveSpeedBps;
    private volatile double slopeSpeedBps;
    private volatile double accelerationBps2;
    private volatile double decelerationBps2;
    private volatile double minSpeedBps;
    private volatile double maxVelocityClampBpt;
    private volatile long offRailGraceMillis;
    private volatile boolean chunkPreloadEnabled;
    private volatile int chunkPreloadDistance;
    private volatile double stationActivationRadius;
    private volatile double stationArrivalRadius;
    private volatile double slowDownMargin;
    private volatile List<String> disabledWorlds;

    public RailConfig(StellariaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfigManager();
        straightSpeedBps = cfg.getDouble("rail.speed.straight", 40.0);
        curveSpeedBps = cfg.getDouble("rail.speed.curve", 12.0);
        slopeSpeedBps = cfg.getDouble("rail.speed.slope", 20.0);
        accelerationBps2 = Math.max(0.1, cfg.getDouble("rail.acceleration", 4.0));
        decelerationBps2 = Math.max(0.1, cfg.getDouble("rail.deceleration", 8.0));
        minSpeedBps = Math.max(0.0, cfg.getDouble("rail.min-speed", 2.0));
        maxVelocityClampBpt = Math.max(0.1, cfg.getDouble("rail.max-velocity-clamp", 3.0));
        offRailGraceMillis = (long) (Math.max(0.0, cfg.getDouble("rail.off-rail-grace-seconds", 2.0)) * 1000L);
        chunkPreloadEnabled = cfg.getBoolean("rail.chunk-preload.enabled", true);
        chunkPreloadDistance = Math.max(0, cfg.getInt("rail.chunk-preload.distance", 2));
        stationActivationRadius = Math.max(0.5, cfg.getDouble("rail.station.activation-radius", 5.0));
        stationArrivalRadius = Math.max(0.5, cfg.getDouble("rail.station.arrival-radius", 2.5));
        slowDownMargin = Math.max(1.0, cfg.getDouble("rail.station.slow-down-margin", 1.3));
        disabledWorlds = cfg.getStringList("rail.disabled-worlds");
    }

    public double getStraightSpeedBps() { return straightSpeedBps; }
    public double getCurveSpeedBps() { return curveSpeedBps; }
    public double getSlopeSpeedBps() { return slopeSpeedBps; }
    public double getAccelerationBps2() { return accelerationBps2; }
    public double getDecelerationBps2() { return decelerationBps2; }
    public double getMinSpeedBps() { return minSpeedBps; }
    public double getMaxVelocityClampBpt() { return maxVelocityClampBpt; }
    public long getOffRailGraceMillis() { return offRailGraceMillis; }
    public boolean isChunkPreloadEnabled() { return chunkPreloadEnabled; }
    public int getChunkPreloadDistance() { return chunkPreloadDistance; }
    public double getStationActivationRadius() { return stationActivationRadius; }
    public double getStationArrivalRadius() { return stationArrivalRadius; }
    public double getSlowDownMargin() { return slowDownMargin; }
    public List<String> getDisabledWorlds() { return disabledWorlds; }
}
```

- [ ] **Step 2: コンパイル確認**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/rail/RailConfig.java
git commit -m "feat(rail): add RailConfig cache for rail.* settings"
```

---

## Task 4: `RailSpeedController`（レール形状のジオメトリと速度計算の純粋ロジック）

対応: 元設計書「速度制御」「加速方式」「レール判定」「安全対策」の中核アルゴリズム

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/rail/RailSpeedController.java`
- Test: `src/test/java/org/craftcore/stellaria/rail/RailSpeedControllerTest.java`

**Interfaces:**
- Consumes: `RailConfig`（Task 3で定義したgetter群）
- Produces: `shapeAt(Block): Rail.Shape`(nullable), `endpointsOf(Rail.Shape): BlockFace[]`(nullable), `isCurve/isSlope(Rail.Shape): boolean`, `maxSpeedFor(Rail.Shape, RailConfig): double`, `nextDirection(Rail.Shape, BlockFace incomingDirection): BlockFace`(nullable), `brakingDistance(double speedBps, double decelBps2): double`, `nextSpeed(double current, double target, double accel, double decel): double` — これらは Task 5 の `RailManager` から呼ばれる。

`shapeAt`/`maxSpeedFor` は `Block`/`RailConfig`（実サーバー・実プラグインインスタンスが必要）に依存するためユニットテスト対象外。それ以外（`endpointsOf`/`isCurve`/`isSlope`/`nextDirection`/`brakingDistance`/`nextSpeed`）は `Rail.Shape`/`BlockFace` という純粋なenumだけで完結するため、このリポジトリの既存テスト（`WorldBlacklistUtilTest`等）と同じ方針でJUnit 5テストを書く。

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/org/craftcore/stellaria/rail/RailSpeedControllerTest.java` を作成する:

```java
package org.craftcore.stellaria.rail;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailSpeedControllerTest {

    @Test
    void classifiesCurveAndSlopeShapes() {
        assertTrue(RailSpeedController.isCurve(Rail.Shape.NORTH_EAST));
        assertTrue(RailSpeedController.isCurve(Rail.Shape.SOUTH_WEST));
        assertFalse(RailSpeedController.isCurve(Rail.Shape.NORTH_SOUTH));
        assertFalse(RailSpeedController.isCurve(Rail.Shape.ASCENDING_NORTH));

        assertTrue(RailSpeedController.isSlope(Rail.Shape.ASCENDING_EAST));
        assertFalse(RailSpeedController.isSlope(Rail.Shape.EAST_WEST));
        assertFalse(RailSpeedController.isSlope(Rail.Shape.NORTH_EAST));
    }

    @Test
    void endpointsOfReturnsTheTwoConnectedDirections() {
        assertArrayEquals(new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH},
                RailSpeedController.endpointsOf(Rail.Shape.NORTH_SOUTH));
        assertArrayEquals(new BlockFace[]{BlockFace.SOUTH, BlockFace.EAST},
                RailSpeedController.endpointsOf(Rail.Shape.SOUTH_EAST));
    }

    @Test
    void nextDirectionContinuesStraightThroughAStraightRail() {
        // 北向きに走行中、直線区間(NORTH_SOUTH)を通過しても北向きのまま。
        assertEquals(BlockFace.NORTH,
                RailSpeedController.nextDirection(Rail.Shape.NORTH_SOUTH, BlockFace.NORTH));
    }

    @Test
    void nextDirectionTurnsThroughACurve() {
        // SOUTH_EASTは南隣・東隣を接続するカーブ。北向きに進入(=南側から入る)すると東向きに曲がる。
        assertEquals(BlockFace.EAST,
                RailSpeedController.nextDirection(Rail.Shape.SOUTH_EAST, BlockFace.NORTH));
        // 西向きに進入(=東側から入る)すると南向きに曲がる。
        assertEquals(BlockFace.SOUTH,
                RailSpeedController.nextDirection(Rail.Shape.SOUTH_EAST, BlockFace.WEST));
    }

    @Test
    void nextDirectionReturnsNullWhenConnectionIsBroken() {
        // NORTH_SOUTHの直線区間に東向きで進入するのは接続不整合（脱線扱い）
        assertNull(RailSpeedController.nextDirection(Rail.Shape.NORTH_SOUTH, BlockFace.EAST));
    }

    @Test
    void brakingDistanceUsesKinematicFormula() {
        // v=20bps, a=10bps^2 -> 20^2/(2*10) = 20 blocks
        assertEquals(20.0, RailSpeedController.brakingDistance(20.0, 10.0), 1e-9);
        assertEquals(Double.MAX_VALUE, RailSpeedController.brakingDistance(20.0, 0.0));
    }

    @Test
    void nextSpeedRampsTowardTargetWithoutOvershooting() {
        // 加速: 1tickあたりaccel/20 = 4.0/20 = 0.2 bps ずつ増える。目標を超えない。
        assertEquals(0.2, RailSpeedController.nextSpeed(0.0, 40.0, 4.0, 8.0), 1e-9);
        assertEquals(5.0, RailSpeedController.nextSpeed(4.9, 5.0, 4.0, 8.0), 1e-9);

        // 減速: 1tickあたりdecel/20 = 8.0/20 = 0.4 bps ずつ減る。目標を下回らない。
        assertEquals(9.6, RailSpeedController.nextSpeed(10.0, 0.0, 4.0, 8.0), 1e-9);
        assertEquals(0.0, RailSpeedController.nextSpeed(0.3, 0.0, 4.0, 8.0), 1e-9);
    }
}
```

- [ ] **Step 2: テストを実行し、失敗することを確認する**

Run: `./gradlew test --tests "org.craftcore.stellaria.rail.RailSpeedControllerTest"`
Expected: FAIL（`RailSpeedController` クラスが存在しないためコンパイルエラー）

- [ ] **Step 3: `RailSpeedController` を実装する**

```java
package org.craftcore.stellaria.rail;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * レール1マスぶんの形状(Rail.Shape)から「進行方向がどう変わるか」「最大速度はいくつか」を
 * 導出する純粋ロジック。Bukkitイベントやセッション状態には触れない（RailManagerが呼び出す側）。
 *
 * 方向の考え方: BlockFaceは「これから進む先」を表す。ある区間の形状には必ず2つの端点
 * （例: NORTH_EAST は北隣・東隣と接続）があり、入ってきた側（進行方向の逆）と一致する端点の
 * 反対側が、通過後の新しい進行方向になる。
 */
public final class RailSpeedController {

    private RailSpeedController() {
    }

    private static final Map<Rail.Shape, BlockFace[]> ENDPOINTS = new EnumMap<>(Rail.Shape.class);
    static {
        ENDPOINTS.put(Rail.Shape.NORTH_SOUTH, new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH});
        ENDPOINTS.put(Rail.Shape.EAST_WEST, new BlockFace[]{BlockFace.EAST, BlockFace.WEST});
        ENDPOINTS.put(Rail.Shape.ASCENDING_NORTH, new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH});
        ENDPOINTS.put(Rail.Shape.ASCENDING_SOUTH, new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH});
        ENDPOINTS.put(Rail.Shape.ASCENDING_EAST, new BlockFace[]{BlockFace.EAST, BlockFace.WEST});
        ENDPOINTS.put(Rail.Shape.ASCENDING_WEST, new BlockFace[]{BlockFace.EAST, BlockFace.WEST});
        ENDPOINTS.put(Rail.Shape.NORTH_EAST, new BlockFace[]{BlockFace.NORTH, BlockFace.EAST});
        ENDPOINTS.put(Rail.Shape.NORTH_WEST, new BlockFace[]{BlockFace.NORTH, BlockFace.WEST});
        ENDPOINTS.put(Rail.Shape.SOUTH_EAST, new BlockFace[]{BlockFace.SOUTH, BlockFace.EAST});
        ENDPOINTS.put(Rail.Shape.SOUTH_WEST, new BlockFace[]{BlockFace.SOUTH, BlockFace.WEST});
    }

    private static final Set<Rail.Shape> CURVE_SHAPES = Set.of(
            Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST
    );

    /** ブロックがレール（通常/パワード/ディテクター/アクティベーター）なら形状を返す。それ以外はnull。 */
    public static Rail.Shape shapeAt(Block block) {
        return block.getBlockData() instanceof Rail rail ? rail.getShape() : null;
    }

    /** その形状が接続する2方向。交差点等バニラに存在しない形状ならnull。 */
    public static BlockFace[] endpointsOf(Rail.Shape shape) {
        BlockFace[] endpoints = ENDPOINTS.get(shape);
        return endpoints != null ? endpoints.clone() : null;
    }

    public static boolean isCurve(Rail.Shape shape) {
        return shape != null && CURVE_SHAPES.contains(shape);
    }

    public static boolean isSlope(Rail.Shape shape) {
        return shape != null && shape.name().startsWith("ASCENDING_");
    }

    /** その区間の設定上の最大速度 (blocks/s)。形状がnullなら0。 */
    public static double maxSpeedFor(Rail.Shape shape, RailConfig config) {
        if (shape == null) {
            return 0.0;
        }
        if (isCurve(shape)) {
            return config.getCurveSpeedBps();
        }
        if (isSlope(shape)) {
            return config.getSlopeSpeedBps();
        }
        return config.getStraightSpeedBps();
    }

    /**
     * shapeの区間を通過した後の進行方向。
     * 入ってきた方向（incomingDirectionの逆）がこの形状の端点に含まれなければ、
     * T字分岐や脱線などバニラの直線接続が壊れている状態を意味するのでnullを返す
     * （呼び出し側はこれを「脱線」として扱う）。
     */
    public static BlockFace nextDirection(Rail.Shape shape, BlockFace incomingDirection) {
        BlockFace[] endpoints = ENDPOINTS.get(shape);
        if (endpoints == null) {
            return null;
        }
        BlockFace enteredFrom = incomingDirection.getOppositeFace();
        if (endpoints[0] == enteredFrom) {
            return endpoints[1];
        }
        if (endpoints[1] == enteredFrom) {
            return endpoints[0];
        }
        return null;
    }

    /** 等加速度運動の制動距離 (v^2 / 2a)。blocks単位。decelerationBps2が0以下ならMAX_VALUE。 */
    public static double brakingDistance(double speedBps, double decelerationBps2) {
        if (decelerationBps2 <= 0) {
            return Double.MAX_VALUE;
        }
        return (speedBps * speedBps) / (2.0 * decelerationBps2);
    }

    /**
     * 現在速度をtargetBpsへ、1tick(=1/20秒)あたりaccelerationBps2/decelerationBps2の範囲で近づける。
     * 加速中と減速中で異なる変化率を使うことで「急停止しない」を満たす。
     */
    public static double nextSpeed(double currentBps, double targetBps, double accelerationBps2, double decelerationBps2) {
        boolean speedingUp = targetBps >= currentBps;
        double ratePerSecond = speedingUp ? accelerationBps2 : decelerationBps2;
        double deltaPerTick = ratePerSecond / 20.0;
        if (speedingUp) {
            return Math.min(targetBps, currentBps + deltaPerTick);
        }
        return Math.max(targetBps, currentBps - deltaPerTick);
    }
}
```

- [ ] **Step 4: テストを実行し、成功することを確認する**

Run: `./gradlew test --tests "org.craftcore.stellaria.rail.RailSpeedControllerTest"`
Expected: BUILD SUCCESSFUL（7テスト全て成功）

- [ ] **Step 5: フルビルド確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/rail/RailSpeedController.java src/test/java/org/craftcore/stellaria/rail/RailSpeedControllerTest.java
git commit -m "feat(rail): add RailSpeedController geometry and speed math with tests"
```

---

## Task 5: `RailSession` と `RailManager`（セッションのライフサイクル・毎tick制御・チャンク先読み）

対応: 元設計書「高速モード」「高速モードへの切り替え」「高速モード解除条件」「チャンク対策」「駅システム」「安全対策」

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/rail/RailSession.java`
- Create: `src/main/java/org/craftcore/stellaria/rail/RailManager.java`

**Interfaces:**
- Consumes: `RailConfig`(Task 3), `RailSpeedController`(Task 4), `RailStationManager`(Task 1)
- Produces: `RailManager#isRailMode(UUID): boolean`, `#startSession(Minecart, RailStationManager.Station): boolean`, `#endSession(Minecart, EndReason): void`, `#tickMovement(Minecart): void`, `#shutdown(): void` — Task 6のRailListenerと、Task 7のRailCommandから呼ばれる。

- [ ] **Step 1: `RailSession` を作成する**

```java
package org.craftcore.stellaria.rail;

import org.bukkit.block.BlockFace;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 高速モード中のトロッコ1台ぶんの状態。KikoriManagerの伐採状態・TpaCoreの保留リクエストと同じく
 * インメモリのみで永続化しない（サーバー再起動やRailManager#shutdown()でリセットされる）。
 */
public final class RailSession {

    private final UUID minecartId;
    private final String originStationName;

    private double currentSpeedBps;
    private BlockFace direction;
    private long lastOnRailMillis;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private final Set<Long> heldChunkTickets = new HashSet<>();

    public RailSession(UUID minecartId, String originStationName, BlockFace direction, double initialSpeedBps) {
        this.minecartId = minecartId;
        this.originStationName = originStationName;
        this.direction = direction;
        this.currentSpeedBps = initialSpeedBps;
        this.lastOnRailMillis = System.currentTimeMillis();
    }

    public UUID minecartId() { return minecartId; }

    public String originStationName() { return originStationName; }

    public double currentSpeedBps() { return currentSpeedBps; }

    public void setCurrentSpeedBps(double value) { this.currentSpeedBps = value; }

    public BlockFace direction() { return direction; }

    public void setDirection(BlockFace value) { this.direction = value; }

    public long lastOnRailMillis() { return lastOnRailMillis; }

    public void markOnRailNow() { this.lastOnRailMillis = System.currentTimeMillis(); }

    /** チャンク先読みの再計算を「チャンクをまたいだ時だけ」にするための比較。 */
    public boolean hasEnteredChunk(int chunkX, int chunkZ) {
        return chunkX != lastChunkX || chunkZ != lastChunkZ;
    }

    public void rememberChunk(int chunkX, int chunkZ) {
        this.lastChunkX = chunkX;
        this.lastChunkZ = chunkZ;
    }

    public Set<Long> heldChunkTickets() { return heldChunkTickets; }
}
```

- [ ] **Step 2: `RailManager` を作成する**

```java
package org.craftcore.stellaria.rail;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 高速鉄道セッションの状態管理と、毎tick(VehicleMoveEventのたび)の速度更新・チャンク先読みを行う。
 * 「対象トロッコかどうか」は sessions.containsKey() だけで判定する
 * （PersistentDataContainerの stellaria:rail_mode タグは外部から見て分かるようにする付随マーカーで、
 * 判定ロジックの真実源はこのMapの方）。
 */
public class RailManager {

    public enum EndReason { ARRIVED, OFF_RAIL, DESTROYED, WORLD_DISABLED, ERROR }

    private final StellariaCore plugin;
    private final RailConfig config;
    private final RailStationManager stationManager;
    private final NamespacedKey railModeKey;
    private final Map<UUID, RailSession> sessions = new HashMap<>();

    public RailManager(StellariaCore plugin, RailConfig config, RailStationManager stationManager) {
        this.plugin = plugin;
        this.config = config;
        this.stationManager = stationManager;
        this.railModeKey = new NamespacedKey(plugin, "rail_mode");
    }

    public boolean isRailMode(UUID minecartId) {
        return sessions.containsKey(minecartId);
    }

    /**
     * 駅originから高速モードを開始する。cartが現在乗っているレールの形状から初期進行方向を決める。
     * レールが無い、または端点が判別できない場合はfalseを返す（呼び出し側がエラーメッセージを出す）。
     */
    public boolean startSession(Minecart cart, RailStationManager.Station origin) {
        if (sessions.containsKey(cart.getUniqueId())) {
            return false;
        }
        Block block = cart.getLocation().getBlock();
        Rail.Shape shape = RailSpeedController.shapeAt(block);
        if (shape == null) {
            return false;
        }
        BlockFace[] endpoints = RailSpeedController.endpointsOf(shape);
        if (endpoints == null) {
            return false;
        }
        // 駅の登録時の向きがこの区間の端点に一致すればそちらを初期進行方向にする。
        // 一致しなければ（プラットフォームの向きと実際のレールが斜めにずれている等）endpoints[0]にフォールバック。
        BlockFace direction = (endpoints[0] == origin.direction() || endpoints[1] == origin.direction())
                ? origin.direction() : endpoints[0];

        RailSession session = new RailSession(cart.getUniqueId(), origin.name(), direction, config.getMinSpeedBps());
        sessions.put(cart.getUniqueId(), session);
        cart.getPersistentDataContainer().set(railModeKey, PersistentDataType.BOOLEAN, true);
        cart.setMaxSpeed(config.getMaxVelocityClampBpt());
        return true;
    }

    public void endSession(Minecart cart, EndReason reason) {
        RailSession session = sessions.remove(cart.getUniqueId());
        if (session == null) {
            return;
        }
        releaseChunkTickets(cart.getWorld(), session);
        if (cart.isValid()) {
            cart.getPersistentDataContainer().remove(railModeKey);
            cart.setVelocity(new Vector(0, 0, 0));
        }
        notifyEnd(cart, reason);
    }

    private void notifyEnd(Minecart cart, EndReason reason) {
        String key = switch (reason) {
            case ARRIVED -> "rail.arrived";
            case OFF_RAIL -> "rail.off_rail_cancelled";
            default -> null; // DESTROYED/WORLD_DISABLED/ERRORは無言で終了する
        };
        if (key == null) {
            return;
        }
        for (Entity passenger : cart.getPassengers()) {
            if (passenger instanceof Player player) {
                player.sendMessage(plugin.getConfigManager().getMessage(key, player));
            }
        }
    }

    /** VehicleMoveEventから毎tick呼ぶ。対象外のトロッコなら何もしない。 */
    public void tickMovement(Minecart cart) {
        RailSession session = sessions.get(cart.getUniqueId());
        if (session == null) {
            return;
        }
        if (WorldBlacklistUtil.isBlacklisted(config.getDisabledWorlds(), cart.getWorld().getName())) {
            endSession(cart, EndReason.WORLD_DISABLED);
            return;
        }

        Block currentBlock = cart.getLocation().getBlock();
        Rail.Shape shape = RailSpeedController.shapeAt(currentBlock);
        if (shape == null) {
            if (System.currentTimeMillis() - session.lastOnRailMillis() > config.getOffRailGraceMillis()) {
                endSession(cart, EndReason.OFF_RAIL);
            }
            return;
        }
        session.markOnRailNow();

        BlockFace nextDirection = RailSpeedController.nextDirection(shape, session.direction());
        if (nextDirection == null) {
            // T字分岐・接続不整合。バニラ側で何が起きるか予測できないため安全側に倒して制御を手放す。
            endSession(cart, EndReason.OFF_RAIL);
            return;
        }
        session.setDirection(nextDirection);

        double targetSpeed = computeTargetSpeed(currentBlock, shape, session);
        double newSpeed = RailSpeedController.nextSpeed(
                session.currentSpeedBps(), targetSpeed, config.getAccelerationBps2(), config.getDecelerationBps2());
        session.setCurrentSpeedBps(newSpeed);

        RailStationManager.Station nearStation = stationManager.findWithin(cart.getLocation(), config.getStationArrivalRadius());
        if (nearStation != null && newSpeed <= config.getMinSpeedBps()) {
            endSession(cart, EndReason.ARRIVED);
            return;
        }

        applyVelocity(cart, session, newSpeed);
        updateChunkPreload(cart, session);
    }

    /**
     * 現在ブロックから進行方向へ辿りながら、制動距離内に迫っているカーブ/坂道/駅の制限速度を
     * 反映した今tickの目標速度を返す（元設計書「カーブ進入前に減速する」を満たすための先読み）。
     * 制動距離ぶん先まで見れば十分なので、それを超えて延々と辿ることはしない。
     */
    private double computeTargetSpeed(Block currentBlock, Rail.Shape currentShape, RailSession session) {
        double limit = RailSpeedController.maxSpeedFor(currentShape, config);
        double brakingNeeded = RailSpeedController.brakingDistance(session.currentSpeedBps(), config.getDecelerationBps2())
                * config.getSlowDownMargin();
        double maxLookahead = Math.max(brakingNeeded, 1.0) + 2.0;

        Block scanBlock = currentBlock;
        BlockFace scanDirection = session.direction();
        double distance = 0.0;

        while (distance < maxLookahead) {
            RailStationManager.Station station = stationManager.findWithin(
                    scanBlock.getLocation().add(0.5, 0.5, 0.5), config.getStationArrivalRadius());
            if (station != null && distance <= brakingNeeded) {
                return 0.0;
            }

            Block nextBlock = scanBlock.getRelative(scanDirection);
            Rail.Shape nextShape = RailSpeedController.shapeAt(nextBlock);
            if (nextShape == null) {
                break; // この先はレールが無い（行き止まり・未敷設）。今のブロックの制限だけで判断する
            }
            double aheadLimit = RailSpeedController.maxSpeedFor(nextShape, config);
            if (aheadLimit < limit && distance <= brakingNeeded) {
                limit = Math.min(limit, aheadLimit);
            }
            BlockFace nextDirection = RailSpeedController.nextDirection(nextShape, scanDirection);
            if (nextDirection == null) {
                break;
            }
            scanBlock = nextBlock;
            scanDirection = nextDirection;
            distance += 1.0;
        }
        return limit;
    }

    private void applyVelocity(Minecart cart, RailSession session, double speedBps) {
        double blocksPerTick = speedBps / 20.0;
        Vector velocity = new Vector(session.direction().getModX(), 0, session.direction().getModZ())
                .multiply(blocksPerTick);

        double clamp = config.getMaxVelocityClampBpt();
        if (velocity.lengthSquared() > clamp * clamp) {
            velocity.normalize().multiply(clamp);
        }
        if (!Double.isFinite(velocity.getX()) || !Double.isFinite(velocity.getY()) || !Double.isFinite(velocity.getZ())) {
            plugin.getLogger().log(Level.WARNING, "レール高速モードで異常なVelocityを検知したため強制解除しました: " + cart.getUniqueId());
            endSession(cart, EndReason.ERROR);
            return;
        }
        cart.setVelocity(velocity);
    }

    /** チャンクをまたいだ時だけ、進行方向側のチャンクにチケットを張り替える。 */
    private void updateChunkPreload(Minecart cart, RailSession session) {
        if (!config.isChunkPreloadEnabled()) {
            return;
        }
        Chunk currentChunk = cart.getLocation().getChunk();
        if (!session.hasEnteredChunk(currentChunk.getX(), currentChunk.getZ())) {
            return;
        }
        World world = cart.getWorld();
        releaseChunkTickets(world, session);

        int chunkDx = (int) Math.signum(session.direction().getModX());
        int chunkDz = (int) Math.signum(session.direction().getModZ());
        for (int i = 1; i <= config.getChunkPreloadDistance(); i++) {
            int cx = currentChunk.getX() + chunkDx * i;
            int cz = currentChunk.getZ() + chunkDz * i;
            world.addPluginChunkTicket(cx, cz, plugin);
            session.heldChunkTickets().add(packChunk(cx, cz));
        }
        session.rememberChunk(currentChunk.getX(), currentChunk.getZ());
    }

    private void releaseChunkTickets(World world, RailSession session) {
        for (long packed : session.heldChunkTickets()) {
            world.removePluginChunkTicket((int) (packed >> 32), (int) packed, plugin);
        }
        session.heldChunkTickets().clear();
    }

    private static long packChunk(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /** onDisableから呼ぶ。保持中のチャンクチケットを全て解放し、セッションを破棄する。 */
    public void shutdown() {
        for (Map.Entry<UUID, RailSession> entry : sessions.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof Minecart cart) {
                releaseChunkTickets(cart.getWorld(), entry.getValue());
            }
        }
        sessions.clear();
    }
}
```

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/rail/RailSession.java src/main/java/org/craftcore/stellaria/rail/RailManager.java
git commit -m "feat(rail): add RailSession and RailManager session lifecycle"
```

---

## Task 6: `RailListener`（Bukkitイベント配線）

対応: 元設計書「クラス構成案 > RailListener」

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/rail/RailListener.java`

**Interfaces:**
- Consumes: `StellariaCore#getRailManager()`（Task 8で追加するgetter。このタスク時点ではまだ存在しないため、Task 8完了までは未使用コードとして残る。Task 8でコンパイルが通ることを確認する）

- [ ] **Step 1: `RailListener` を作成する**

```java
package org.craftcore.stellaria.rail;

import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.craftcore.stellaria.StellariaCore;

/**
 * 高速鉄道セッションの毎tick更新とライフサイクル終了のきっかけとなるBukkitイベントを拾う。
 * 対象外のMinecart（RailManagerにセッションが無いもの）への影響はRailManager側の
 * sessions.containsKey()チェックに一任し、ここでは種別だけを見て素通しする。
 */
public class RailListener implements Listener {

    private final StellariaCore plugin;

    public RailListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            plugin.getRailManager().tickMovement(cart);
        }
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            plugin.getRailManager().endSession(cart, RailManager.EndReason.DESTROYED);
        }
    }
}
```

- [ ] **Step 2: Commit**

（`StellariaCore#getRailManager()` はTask 8まで存在しないため、この時点ではコンパイルエラーになる。Task 8のStep完了後にまとめてビルド確認するので、ここでは単純にファイルを追加してコミットするだけでよい。）

```bash
git add src/main/java/org/craftcore/stellaria/rail/RailListener.java
git commit -m "feat(rail): add RailListener vehicle event wiring"
```

---

## Task 7: `/rail` コマンド

対応: 元設計書「高速モードへの切り替え」（初期実装での発車トリガー）「駅システム」（駅登録UI相当）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/commands/RailCommand.java`

**Interfaces:**
- Consumes: `StellariaCore#getRailStationManager()`, `#getRailManager()`（Task 8で追加）
- Produces: `/rail station add|remove|list`, `/rail depart <station>` のCommandExecutor/TabCompleter

- [ ] **Step 1: `RailCommand` を作成する**

```java
package org.craftcore.stellaria.commands;

import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.rail.RailStationManager;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /rail station add|remove|list（駅の管理。stellaria.rail.admin）と
 * /rail depart <駅名>（高速モードの発車。stellaria.rail）をまとめて処理するExecutor。
 * 駅または専用の発車地点から開始したトロッコだけを高速化するという元設計書の方針に沿い、
 * 「駅の登録地点から一定距離以内で、既に何かのトロッコに乗っている」ことを起動条件にする
 * （サイン/ボタン等の将来的なトリガーは元設計書でも初期実装の対象外とされている）。
 */
public class RailCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public RailCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("rail.must_be_player", null));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_depart", player));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "station" -> handleStation(player, args);
            case "depart" -> handleDepart(player, args);
            default -> player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_depart", player));
        }
        return true;
    }

    private void handleStation(Player player, String[] args) {
        if (!player.hasPermission("stellaria.rail.admin")) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.no_permission", player));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_add", player));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "add" -> handleStationAdd(player, args);
            case "remove" -> handleStationRemove(player, args);
            case "list" -> handleStationList(player);
            default -> player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_add", player));
        }
    }

    private void handleStationAdd(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_add", player));
            return;
        }
        String name = args[2];
        BlockFace direction = yawToBlockFace(player.getLocation().getYaw());
        RailStationManager.CreateResult result = plugin.getRailStationManager().create(name, player.getLocation(), direction);
        switch (result) {
            case SUCCESS -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_created", player), "%name%", name));
            case NAME_TAKEN -> player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_name_taken", player), "%name%", name));
            case DATABASE_ERROR -> player.sendMessage(
                    plugin.getConfigManager().getMessage("rail.station_not_found", player));
        }
    }

    private void handleStationRemove(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_station_remove", player));
            return;
        }
        String name = args[2];
        boolean removed = plugin.getRailStationManager().remove(name);
        String key = removed ? "rail.station_removed" : "rail.station_not_found";
        player.sendMessage(FormatUtil.replace(plugin.getConfigManager().getMessage(key, player), "%name%", name));
    }

    private void handleStationList(Player player) {
        List<RailStationManager.Station> stations = plugin.getRailStationManager().listAll();
        if (stations.isEmpty()) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.station_list_empty", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("rail.station_list_header", player));
        for (RailStationManager.Station station : stations) {
            String line = plugin.getConfigManager().getMessage("rail.station_list_entry", player);
            line = FormatUtil.replace(line, "%name%", station.name());
            String worldName = station.location().getWorld() != null ? station.location().getWorld().getName() : "?";
            line = FormatUtil.replace(line, "%world%", worldName);
            player.sendMessage(line);
        }
    }

    private void handleDepart(Player player, String[] args) {
        if (!player.hasPermission("stellaria.rail")) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.no_permission", player));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("rail.usage_depart", player));
            return;
        }
        if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("rail.disabled-worlds", true), player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.world_disabled", player));
            return;
        }
        if (!(player.getVehicle() instanceof Minecart cart)) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.must_ride_minecart", player));
            return;
        }
        String stationName = args[1];
        RailStationManager.Station station = plugin.getRailStationManager().get(stationName);
        if (station == null) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.station_not_found", player), "%name%", stationName));
            return;
        }
        double activationRadius = plugin.getConfigManager().getDouble("rail.station.activation-radius", 5.0);
        if (!station.location().getWorld().equals(cart.getWorld())
                || station.location().distanceSquared(cart.getLocation()) > activationRadius * activationRadius) {
            player.sendMessage(FormatUtil.replace(
                    plugin.getConfigManager().getMessage("rail.too_far_from_station", player), "%name%", stationName));
            return;
        }
        if (plugin.getRailManager().isRailMode(cart.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.already_rail_mode", player));
            return;
        }
        if (!plugin.getRailManager().startSession(cart, station)) {
            player.sendMessage(plugin.getConfigManager().getMessage("rail.not_on_rail", player));
            return;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("rail.departed", player));
    }

    /** プレイヤーの向き(yaw)を東西南北4方向にスナップする。Bukkit標準のLocation#getFacing()と同じ境界。 */
    private static BlockFace yawToBlockFace(float yaw) {
        float normalized = (yaw % 360 + 360) % 360;
        if (normalized >= 45 && normalized < 135) return BlockFace.WEST;
        if (normalized >= 135 && normalized < 225) return BlockFace.NORTH;
        if (normalized >= 225 && normalized < 315) return BlockFace.EAST;
        return BlockFace.SOUTH;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(List.of("station", "depart"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("station")) {
            return TabCompleteUtil.filterStartsWith(List.of("add", "remove", "list"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("depart")) {
            return TabCompleteUtil.filterStartsWith(stationNames(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("station") && args[1].equalsIgnoreCase("remove")) {
            return TabCompleteUtil.filterStartsWith(stationNames(), args[2]);
        }
        return List.of();
    }

    private List<String> stationNames() {
        return plugin.getRailStationManager().listAll().stream().map(RailStationManager.Station::name).toList();
    }
}
```

- [ ] **Step 2: Commit**

（このコマンドも `StellariaCore#getRailManager()`/`getRailStationManager()` に依存するため、Task 8完了までコンパイルは通らない。ここではファイル追加のみコミットする。）

```bash
git add src/main/java/org/craftcore/stellaria/commands/RailCommand.java
git commit -m "feat(rail): add /rail command (station add/remove/list, depart)"
```

---

## Task 8: `StellariaCore` 配線・最終ビルド確認・実機テスト

対応: 元設計書「重要事項」全体

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`

**Interfaces:**
- Produces: `StellariaCore#getRailManager(): RailManager`, `#getRailStationManager(): RailStationManager`, `#getRailConfig(): RailConfig`

- [ ] **Step 1: import を追加する**

`StellariaCore.java` の import群に追加:

```java
import org.craftcore.stellaria.commands.RailCommand;
import org.craftcore.stellaria.rail.RailConfig;
import org.craftcore.stellaria.rail.RailListener;
import org.craftcore.stellaria.rail.RailManager;
import org.craftcore.stellaria.rail.RailStationManager;
```

- [ ] **Step 2: フィールドを追加する**

`private WorldResetManager worldResetManager;` の直後に追加:

```java
private RailConfig railConfig;
private RailStationManager railStationManager;
private RailManager railManager;
```

- [ ] **Step 3: onEnable内でマネージャーを構築する**

`this.kikoriManager = new KikoriManager(this);` の直後（`this.mineManager = new MineManager(this);` の直前でも可、どちらでも依存関係上は問題ない）に追加:

```java
this.railConfig = new RailConfig(this);
this.railStationManager = new RailStationManager(this);
this.railStationManager.loadAll();
this.railManager = new RailManager(this, railConfig, railStationManager);
```

- [ ] **Step 4: リスナーとコマンドを登録する**

`getServer().getPluginManager().registerEvents(new MineListener(this), this);` の直後に追加:

```java
getServer().getPluginManager().registerEvents(new RailListener(this), this);
```

コマンド登録は他のコマンド群と同じ並びに追加（例えば `getCommand("mine")...` ブロックの直後）:

```java
RailCommand railCommand = new RailCommand(this);
getCommand("rail").setExecutor(railCommand);
getCommand("rail").setTabCompleter(railCommand);
```

- [ ] **Step 5: getterを追加する**

`getKikoriManager()`/`getMineManager()` などと同じ並びに追加:

```java
public RailManager getRailManager() {
    return this.railManager;
}

public RailStationManager getRailStationManager() {
    return this.railStationManager;
}

public RailConfig getRailConfig() {
    return this.railConfig;
}
```

- [ ] **Step 6: `reloadFeatureManagers()` に追加する**

`rankManager.reload();` の直後に追加:

```java
railConfig.reload();
```

- [ ] **Step 7: `onDisable()` に追加する**

`DatabaseManager.disconnect();` の直前に追加:

```java
railManager.shutdown();
```

- [ ] **Step 8: フルビルド確認**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。`build/libs/StellariaCore-<version>.jar` が生成され、`run/plugins/` にコピーされること。

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/craftcore/stellaria/StellariaCore.java
git commit -m "feat(rail): wire RailManager/RailStationManager/RailListener/RailCommand into StellariaCore"
```

- [ ] **Step 10: 実機での手動テスト**

Run: `./gradlew runServer` でローカルテストサーバーを起動し、以下を確認する（元設計書「重要事項」のテスト項目に対応）。

1. 直線レールを敷設し、`/rail station add start` → 別の場所に `/rail station add end` を実行。
2. トロッコを設置して乗車し、`start` 駅の近くで `/rail depart start` を実行 → アクションバーに「高速鉄道モード」が出て加速していくこと。
3. 直線区間で `rail.speed.straight`（デフォルト40）近くまで速度が乗ること、脱線しないこと。
4. カーブ区間の手前で自動的に減速し、カーブでバニラの追従を外れないこと。
5. 坂道区間で速度が下がること、上り/下り双方で安定して追従すること。
6. `end` 駅に近づくと自動減速し、停止後に高速モードが解除されメッセージが出ること（アクションバー/チャット双方）。
7. 途中でレールを壊す・線路の途切れた場所を作って通過させ、`rail.off-rail-grace-seconds` 経過後に安全に解除されること（異常なVelocity/座標にならないこと）。
8. 複数のトロッコを同時に高速モードで走らせ、互いに干渉しないこと。
9. 高速モード中でないトロッコ（`/rail depart` を使っていない普通のトロッコ）に速度や挙動の変化が一切無いこと。
10. `/stellariareload` 実行後も `rail.*` の変更が反映されること（`RailConfig#reload()`）。
11. サーバーを再起動し、走行中だった高速トロッコが（セッションがメモリ上にしか無いため）通常のトロッコとして安全に振る舞う、またはその場で停止していること（クラッシュ・例外が出ないこと）。
12. `/rail station remove <name>` 後に、その駅を目的地にした減速が起きなくなること。

問題が見つかった場合は該当タスクに戻って修正し、Step 8から再検証する。

---

## Self-Review メモ

- 元設計書の「初期実装範囲」1〜9は Task 5(1,2,6)・Task 4+5(3,4,5)・Task 1+7(8)・Task 5(6,9) でそれぞれ対応済み。
- 「自動分岐・路線検索・運賃システム」は実装対象外のまま（`rail.fare.enabled: false` を置くのみ）。
- 型の一貫性: `RailManager`/`RailCommand`/`RailListener` はいずれも `RailStationManager.Station`・`RailManager.EndReason` の名前をそのまま使っており、タスク間でのメソッド名の揺れ（`startSession`/`endSession`/`tickMovement`/`isRailMode`）は統一されている。
- プレースホルダーなし: 全タスクのコードは実際にコンパイル可能な完全な内容。
