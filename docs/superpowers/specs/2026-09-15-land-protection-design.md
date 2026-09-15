# 土地保護機能（`/land`）設計

- 日付: 2026-09-15
- ステータス: 承認待ち

## 背景・位置づけ

プレイヤーがチャンク単位で土地を「claim（保護）」し、無関係なプレイヤーによるブロック破壊・設置やコンテナ荒らしを防げるようにする。あわせて、サーバー全体のPvPをデフォルト無効にし、claimした土地（縄張り）ごとにオーナーが明示的にPvPを許可した場所でしかプレイヤー同士が戦えないようにする。

`docs/superpowers/specs/2026-09-15-kikori-design.md` で「本機能はむしろ将来の土地保護と自然に連携する設計にする」と明記されている通り、木こりの連鎖伐採は`player.breakBlock()`で本物の`BlockBreakEvent`を発火させる実装のため、本機能のブロック破壊保護がそのまま連鎖伐採にも効く。

## スコープ

**対象:**
- `/land claim` / `unclaim` / `info` / `trust <player>` / `untrust <player>` / `trustlist` / `pvp <on|off>` / `list` / `help`
- チャンク単位のclaim（隣接制約なし、飛び地OK）。コストは所持金から引き落とし、上限チェックあり
- 隣接するclaim同士を自動的に同じ「縄張り（territory）」としてグルーピングし、信頼リストとPvPフラグを縄張り単位で共有する
- ブロック設置・破壊、コンテナ／ドア類へのアクセス、爆発・延焼の保護
- サーバー全体デフォルトPvP無効化 + 縄張り単位でのPvP有効化トグル
- claim時の境界パーティクル表示
- 管理者bypass権限（`stellaria.land.admin`）

**対象外:**
- 縄張りの自動分割（`unclaim`で物理的に縄張りが分断されても、信頼リスト・PvPフラグは自動では分かれない。`/land help`で明記する運用上の割り切り）
- チャンク以外の任意形状範囲選択（WorldGuard的な機能）
- 管理者専用の独立PvPゾーン（claimと別概念の「area」は導入しない。claim = areaとして扱う）

## アーキテクチャ

### 新規ファイル

```
commands/
  LandCommand.java
managers/
  LandManager.java
listeners/
  LandProtectionListener.java
```

`HomeManager`/`WarpManager`（`plugin`を保持し`DatabaseManager`を直接叩くManager）と`HomeCommand`（1クラスで複数サブコマンドを`switch`分岐）の構造を踏襲する。

`BlockBreakEvent`/`BlockPlaceEvent`等は既存のどのリスナーの関心事にも属さないため、`LandProtectionListener`を新規に立てる（`KikoriListener`とは別リスナー、後述の優先度調整で連携）。

### `LandManager`のインメモリキャッシュ

`BlockBreakEvent`/`BlockPlaceEvent`は毎tick大量に飛ぶホットパスであり、判定のたびにSQLiteへ問い合わせるのは避けたい。`EconomyManager`等の「キャッシュなし」方針からの意図的な逸脱として、起動時にDBから全件ロードしオンメモリで判定する:

```java
record ChunkKey(String world, int x, int z) {}

Map<ChunkKey, LandClaim> claimsByChunk;      // world+chunkX+chunkZ -> オーナー等
Map<String, Territory> territories;          // territoryId -> {pvpEnabled, trustedUuids}
```

claim/unclaim/trust/untrust/pvpトグルのたびに、SQLite書き込みとインメモリキャッシュ更新を同時に行う（`transaction()`ヘルパーで一貫性を保つ）。

### `StellariaCore#onEnable`への追加

1. `land_claims` / `land_territories` / `land_trusts` テーブル作成（`DatabaseManager`のテーブル作成ステップに追加）
2. `LandManager`を構築し、コンストラクタ内で上記3テーブルを全件ロードしてキャッシュを構築
3. `LandProtectionListener`を登録
   - `BlockBreakEvent`ハンドラは`EventPriority.LOW`で登録する（`KikoriListener#onBlockBreak`より先に評価させるため。既存コードはEventPriorityを一切使っていないが、この1箇所だけ意図的に指定する）
4. **既存`KikoriListener#onBlockBreak`の`@EventHandler`に`ignoreCancelled = true`を追加**する1行修正。これにより`LandProtectionListener`が保護判定でイベントをキャンセルした場合、伐採の起点判定自体がスキップされる。連鎖伐採中の各ブロック（`player.breakBlock()`経由）も同じ理屈で自動的に保護が効く（cancelされたブロックはkikori側のキュー処理で個別スキップされる、kikori設計書の記載通り）
5. `LandCommand`を`land`コマンドに登録（`CommandExecutor`と`TabCompleter`の両方）
6. 退出時のクリーンアップは不要（永続データのみで、プレイヤーごとの一時状態を持たないため）

`plugin.yml`の`commands:`に`land`を追加。権限:
- `stellaria.land`（デフォルト`true`）— 基本コマンド一式
- `stellaria.land.admin`（デフォルト`op`）— 保護判定・PvP制限のbypass。他人のclaimを強制解除する等の管理操作もこの権限で統一する

## データ永続化

### テーブル定義

```sql
CREATE TABLE IF NOT EXISTS land_claims (
  world TEXT NOT NULL,
  chunk_x INTEGER NOT NULL,
  chunk_z INTEGER NOT NULL,
  owner_uuid TEXT NOT NULL,
  territory_id TEXT NOT NULL,
  claimed_at INTEGER NOT NULL,
  PRIMARY KEY (world, chunk_x, chunk_z)
);

CREATE TABLE IF NOT EXISTS land_territories (
  territory_id TEXT PRIMARY KEY,
  pvp_enabled INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS land_trusts (
  territory_id TEXT NOT NULL,
  trusted_uuid TEXT NOT NULL,
  PRIMARY KEY (territory_id, trusted_uuid)
);
```

`territory_id`は`UUID.randomUUID().toString()`で発行する内部識別子（プレイヤーには見せない。`/land info`等ではオーナー名のみ表示）。

### 縄張り（territory）のグルーピングロジック（`LandManager#claim`）

1. 対象チャンクが既にclaim済みなら`ALREADY_CLAIMED`
2. ワールドが`land.enabled-worlds`のホワイトリストに無ければ`WORLD_DISABLED`
3. `land.max-chunks-per-player`を超えるなら`LIMIT_REACHED`
4. `land.cost-per-chunk`分の残高が無ければ`INSUFFICIENT_FUNDS`（`EconomyManager.has()`で事前チェック→`withdrawPlayer`）
5. 対象チャンクの4方向隣接チャンク（同ワールド）を`claimsByChunk`から引き、**同一プレイヤーが所有する**claimの`territory_id`を重複排除して集める
   - 0件 → 新規`territory_id`を発行、`land_territories`に`pvp_enabled=0`で新規行を挿入
   - 1件 → その`territory_id`をそのまま再利用
   - 2件以上（複数の縄張りを繋ぐclaim） → 最初に見つかった1件を採用先（canonical）とし、他の`territory_id`を持つ`land_claims`行を全てcanonicalへ`UPDATE`、`land_trusts`も同様に付け替え（重複行は無視）、採用されなかった`land_territories`行は削除。キャッシュ側も同じ手順でマージする。PvPフラグはcanonical側の値を維持する（マージ元の値は破棄）
6. `land_claims`に新規行をINSERT、キャッシュに反映
7. `land.border-particle`設定に従い、claimしたチャンクの境界にパーティクルを一定時間表示（`ParticleUtil`を流用、`HomeManager`のテレポート演出と同じ設計）

### `unclaim`

- 実行者がオーナーであること（`stellaria.land.admin`があれば他人のclaimも解除可）
- `land.refund-on-unclaim`がtrueなら`land.cost-per-chunk`を全額返金
- `land_claims`から行を削除。削除後、その`territory_id`を参照する`land_claims`行が0件になったら`land_territories`・`land_trusts`からも該当`territory_id`の行を削除（縄張りの自動分割はしない、上記スコープ外の通り）

## 保護ロジック（`LandProtectionListener`）

判定の中心は`LandManager.canBuild(ChunkKey, Player)`（オーナー本人 or 縄張りのtrustedリストに含まれる or `stellaria.land.admin`を持つ、のいずれかでtrue）と`LandManager.isPvpAllowed(Location)`（claimが存在し、かつその縄張りの`pvp_enabled`がtrue、または`stellaria.land.admin`を持つ、のいずれかでtrue）の2つ。

| イベント | 優先度 | 挙動 |
|---|---|---|
| `BlockBreakEvent` | `LOW` | `canBuild`がfalseならキャンセル + `land.protected-block`メッセージ |
| `BlockPlaceEvent` | 既定 | 同上 |
| `PlayerInteractEvent` | 既定 | 右クリック対象ブロックが`land.protected-interactables`（config設定のMaterial名リスト）に含まれ、かつ`canBuild`がfalseならキャンセル |
| `EntityDamageByEntityEvent` | 既定 | 加害者・被害者を解決（加害者がPlayer、または`Projectile#getShooter()`がPlayer）し、両者ともPlayerなら被害者の現在地で`isPvpAllowed`を判定。falseならキャンセル + `land.pvp-blocked`（クールダウン付きでスパム防止） |
| `EntityExplodeEvent` / `BlockExplodeEvent` | 既定 | イベント全体はキャンセルせず、`event.blockList()`から対象チャンクがclaim済みのブロックだけ`removeIf`で除去 |
| `BlockIgniteEvent` | 既定 | 着火対象ブロックのチャンクがclaim済みならキャンセル（`IgniteCause.FLINT_AND_STEEL`等でプレイヤー原因の場合は`canBuild`で判定し、オーナー・trusted自身の着火は許可） |
| `BlockBurnEvent` | 既定 | 延焼対象ブロックのチャンクがclaim済みなら常にキャンセル |

## コマンド設計（`LandCommand`）

| サブコマンド | 動作 | 権限 | 備考 |
|---|---|---|---|
| `/land claim` | 現在地のチャンクをclaim | `stellaria.land` | 結果に応じ`land.claimed`/`land.already-claimed`/`land.limit-reached`/`land.insufficient-funds`/`land.world-disabled` |
| `/land unclaim` | 現在地のチャンクの保護解除 | `stellaria.land`（他人のclaimは`stellaria.land.admin`） | `land.unclaimed`/`land.not-your-claim` |
| `/land info` | 現在地の所有者・縄張りのPvP状態を表示 | `stellaria.land` | `land.info-owner`/`land.info-unclaimed` |
| `/land trust <player>` | 現在地の縄張りに信頼プレイヤー追加 | `stellaria.land`（オーナーのみ） | `land.trust-added`/`land.not-owner-trust` |
| `/land untrust <player>` | 信頼リストから削除 | 同上 | `land.trust-removed` |
| `/land trustlist` | 現在地の縄張りの信頼リストを表示 | `stellaria.land` | claim外なら`land.info-unclaimed` |
| `/land pvp <on\|off>` | 現在地の縄張りのPvP許可を切替 | `stellaria.land`（オーナーのみ） | `land.pvp-enabled`/`land.pvp-disabled`/`land.not-owner-trust`を流用 |
| `/land list` | 自分の所有claim数・縄張り数を表示 | `stellaria.land` | MVPでは一覧ではなく件数のみ |
| `/land help` | 仕組み（縄張りのグルーピング・unclaimしても自動分裂しないこと・PvPのデフォルト無効）を説明 | `stellaria.land` | `messages.yml`の`land.help`（複数行リスト） |

タブ補完は`TabCompleteUtil.filterStartsWith`をサブコマンド名・オンラインプレイヤー名に対して使う（`HomeCommand`と同方式）。

## Config / Messages キー一覧

### `config.yml` 追加分

```yaml
land:
  enabled-worlds: ["world"]       # ホワイトリスト。ここに書いたワールドだけでclaim可能
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

（`protected-interactables`はドア・トラップドアを含めていない。`Material`名の完全一致リストで実装し、`*_DOOR`等のワイルドカードは今回対象外とする。将来必要になれば`ElevatorManager`の`ignoreblocks`ワイルドカード方式を参考に拡張する）

### カスタムトークンの置換

`%cost%`/`%owner%`/`%refund%`/`%max%`/`%target%`/`%pvp_state%`は`ConfigManager.getMessage()`が自動で置換する対象（`%player%`・PlaceholderAPI・色コード）に含まれないため、`HomeCommand`と同様に`FormatUtil.replace(...)`で個別に置換してから送信する。

### `messages.yml` 追加分

```yaml
land:
  claimed: "&%aこのチャンクを保護しました！（コスト: &%e%cost%&%a）"
  already-claimed: "&%cこのチャンクは既に &%e%owner% &%cさんが保護しています"
  unclaimed: "&%aチャンクの保護を解除しました（&%e%refund% &%aコインを返金）"
  not-your-claim: "&%c自分の土地ではありません"
  limit-reached: "&%c保護できる上限（&%e%max%&%cチャンク）に達しています"
  insufficient-funds: "&%c所持金が足りません（必要: &%e%cost%&%c）"
  world-disabled: "&%cこのワールドでは土地保護を利用できません"
  info-owner: "&%b所有者&%7: &%f%owner%&%7 / PvP&%7: &%f%pvp_state%"
  info-unclaimed: "&%7このチャンクは誰にも保護されていません"
  trust-added: "&%a%target% &%aさんをこの土地の信頼リストに追加しました"
  trust-removed: "&%a%target% &%aさんを信頼リストから外しました"
  not-owner-trust: "&%c所有者だけが操作できます"
  protected-block: "&%cここはあなたの土地ではありません"
  pvp-enabled: "&%aこの縄張りでのPvPを有効にしました"
  pvp-disabled: "&%cこの縄張りでのPvPを無効にしました"
  pvp-blocked: "&%cここではPvPできません"
  help:
    - "&%9&l| &%bland機能の説明"
    - "&%7隣接するclaim同士は自動で同じ縄張りとして扱われ、信頼リストとPvP設定を共有します"
    - "&%7unclaimしても縄張りが分裂することはありません（信頼リスト等はそのまま残ります）"
    - "&%7PvPはサーバー全体でデフォルト無効です。&%e/land pvp on &%7で自分の縄張り内だけ有効化できます"
```

## エラーハンドリング

- `/land`系コマンドをプレイヤー以外が実行 → 汎用の`must_be_player`相当を返す（既存コマンドと同様の共通処理があれば流用）
- 権限不足 → バニラの権限拒否メッセージに委ねる（他コマンドと同様、独自メッセージは用意しない）
- `claim`失敗時（上限・残高不足・ワールド対象外・既にclaim済み） → 対応するメッセージを返し、コインは引き落とさない
- `trust`/`untrust`/`pvp`をclaim外の場所で実行 → `land.info-unclaimed`
- 縄張りマージ中にDB書き込みが失敗した場合 → `transaction()`ヘルパーでロールバックし、キャッシュ側も更新しない（claim自体を失敗扱いにする）

## テスト方針

本リポジトリに自動テストは無く、`runServer`での実機確認を行う:

- `./gradlew build`が通ること
- **claimの基本動作**: 所持金を用意して`/land claim`→成功メッセージ+残高減少。同じチャンクで再度`/land claim`→`already-claimed`。上限まで埋めて`limit-reached`確認。残高不足で`insufficient-funds`確認。ホワイトリスト外ワールドで`world-disabled`確認
- **境界パーティクル**: claim直後に境界にパーティクルが一瞬表示されること
- **保護動作**: 別プレイヤーでclaim済みチャンクのブロックを壊す/置く→キャンセルされ`protected-block`表示。オーナー本人・`stellaria.land.admin`持ちは操作できること
- **コンテナ保護**: `protected-interactables`に含まれるブロック（チェスト等）を非オーナーが開けない、オーナー・trustedは開けること
- **爆発・延焼**: claim済みチャンク付近でTNT爆破→claim内のブロックだけ残り、claim外は通常通り破壊されること。claim境界に燃え移る火が中に入らないこと
- **縄張りグルーピング**: 隣接する2チャンクを同一プレイヤーがclaim→同じ縄張りとして扱われる（片方で`trust`した相手がもう片方でも建築できる）こと。離れたチャンクをclaimした場合は別縄張りになる（`trust`が反映されない）こと。2つの離れた縄張りを繋ぐようにclaimしたら統合されること
- **unclaimと縄張りの非分裂**: 縄張りの中間チャンクだけunclaimしても、残ったチャンク同士の信頼リスト・PvP設定が引き続き共有されること
- **信頼(trust)**: `/land trust`されたプレイヤーがブロック操作・コンテナアクセスできるようになること、`/land untrust`で解除されること、オーナー以外が`trust`実行→`not-owner-trust`
- **PvP**: 未claim地でプレイヤー同士を攻撃→常にキャンセルされ`pvp-blocked`。claim済みだが`pvp_enabled=false`の縄張り内→同様にキャンセル。`/land pvp on`後の同じ縄張り内→ダメージが通ること。`stellaria.land.admin`持ちは常に攻撃できること
- **Kikori連携**: 他人のclaim内にある天然木を、claim外から伐採コマンド起点で壊そうとする→保護されキャンセルされ、連鎖伐採も発動しないこと。claim内・境界をまたぐ木を伐採→claim内の丸太だけ残り、claim外の丸太は連鎖伐採されること（`ignoreCancelled=true`の効果確認）
- **unclaimの返金**: `/land unclaim`実行→`land.cost-per-chunk`分がプレイヤーの残高に戻ること
- **`/land help`**: 説明文が表示されること
