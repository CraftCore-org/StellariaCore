# home / warp機能 設計仕様

## 背景

サーバー運営の優先度検討の結果、まだ実装されていない候補機能（プロフィール、home、共有home的な
warp、警告/期間BAN、メニューGUI、Vanish、Discord連携、資源ワールド自動リセット）のうち、
「技術的な依存関係」と「プレイヤー体験」を軸に home / warp を最初の着手対象として選定した。

- home / warp は `commands/`・`managers/`・`DatabaseManager` の既存パターンにそのまま乗せられ、
  スコープが明確
- プレイヤーが日常的に触る機能で体感できる恩恵が大きい
- 将来のメニューGUI（`gui/Gui` / `gui/GuiListener` は用意済みだが未使用）の中身候補になる

警告/期間BAN・Vanishは「先回りの備え」であり緊急性がないため後回し。Discord連携は要件が未確定、
資源ワールド自動リセットは独立性の高いインフラタスクのため、いずれも本仕様の対象外。

## スコープ

**含む:**
- `/sethome`・`/home`・`/delhome`・`/homes` — プレイヤー専用の名前付きテレポート地点（最大5個/人）
- `/setwarp`・`/warp`・`/delwarp`・`/warps` — サーバー全体に公開されたテレポート地点（最大5個/人、
  名前はサーバー全体でユニーク）
- home・warp共通の「不安全な着地点」警告＋10秒以内の再実行で強制テレポートする安全チェック機構
- home・warp作成時の経済コスト（`EconomyManager`経由、金額はconfig設定・デフォルト0）

**含まない:**
- home共有（プレイヤー間で個人のhomeを貸し出す機能）— 検討したが不採用
- ランク/権限による上限数の可変化 — 上限は全員共通の固定値（config設定）
- `/homes`・`/warps`のGUI一覧表示 — 今回はチャットテキスト一覧のみ。GUIメニューへの統合は将来の
  メニューGUI実装時に検討
- 不安全な着地点の自動補正（周辺の安全な座標を探索するなど）— 保存座標へそのままテレポートする
  「自己責任」方式のみ
- warpの検索・カテゴリ分け・お気に入りなどの拡張機能

## データモデル

`StellariaCore#onEnable`のテーブル作成処理（`DatabaseManager.createTableIfNotExists`）に2つ追加する。

```sql
homes (
  uuid TEXT,      -- 所有者のプレイヤーUUID
  name TEXT,
  world TEXT,
  x REAL, y REAL, z REAL,
  yaw REAL, pitch REAL,
  PRIMARY KEY (uuid, name)
)

warps (
  name TEXT PRIMARY KEY,   -- サーバー全体でユニーク
  owner_uuid TEXT,
  world TEXT,
  x REAL, y REAL, z REAL,
  yaw REAL, pitch REAL
)
```

homeは`(uuid, name)`の複合主キーでプレイヤーごとに独立した名前空間、warpは`name`単体が主キーで
サーバー全体のユニーク制約を兼ねる（`INSERT`が主キー重合エラーになった場合は名前重複として扱う）。

## アーキテクチャ概要

新規クラス:
- `managers/HomeManager` — home の追加・削除・一覧取得・上限チェックを担当。`DatabaseManager`の
  静的メソッドを直接呼ぶ（`EconomyManager`と同様、DBアクセスをラップするクラスとして`managers/`に置く）
- `managers/WarpManager` — warp版。名前がサーバー全体でユニークな点以外はHomeManagerと同じ責務
- `commands/HomeCommand` — `TpaCore`と同じ「1つのCommandExecutorが複数コマンドをdispatchする」
  パターンで`sethome`・`home`・`delhome`・`homes`の4コマンドをまとめて処理
- `commands/WarpCommand` — 同上で`setwarp`・`warp`・`delwarp`・`warps`を処理
- `utils/TeleportSafetyUtil` — 着地点の安全判定と、不安全時の「警告→10秒以内再実行で強制テレポート」
  フローを提供する静的ヘルパー。HomeCommand・WarpCommand双方から共有

既存クラスの改修:
- `StellariaCore` — `homes`・`warps`テーブル作成、`HomeManager`・`WarpManager`のインスタンス化、
  `HomeCommand`・`WarpCommand`とその8コマンドの登録
- `config.yml` — `home.*`・`warp.*`セクション追加
- `messages.yml` — `home.*`・`warp.*`セクション追加
- `plugin.yml` — 8コマンド定義、`stellaria.home`・`stellaria.warp`・`stellaria.warp.delete.others`
  権限ノード追加

## コマンド仕様

| コマンド | 権限 | 振る舞い |
|---|---|---|
| `/sethome <name>` | `stellaria.home` | 現在地を保存。上限5件超過はエラー、名前重複はエラー（`/delhome`で先に削除するよう案内）、コスト不足はエラー。成功時にコストを消費 |
| `/home <name>` | `stellaria.home` | `TeleportSafetyUtil`経由でテレポート。存在しない名前はエラー |
| `/delhome <name>` | `stellaria.home` | 削除。存在しない名前はエラー |
| `/homes` | `stellaria.home` | 自分のhome一覧をチャットに表示（0件ならその旨を表示） |
| `/setwarp <name>` | `stellaria.warp` | 現在地を公開地点として保存。上限5件超過・名前重複（サーバー全体）・コスト不足はエラー |
| `/warp <name>` | `stellaria.warp` | `TeleportSafetyUtil`経由でテレポート。誰の所有かを問わず全員が使える |
| `/delwarp <name>` | `stellaria.warp`（自分のwarp）／`stellaria.warp.delete.others`（他人のwarp） | 所有者本人はいつでも削除可。他人のwarpを削除するには追加権限が必要 |
| `/warps` | `stellaria.warp` | サーバー全体のwarp一覧をチャットに表示（所有者名も表示） |

いずれもタブ補完で候補名を出す（`TabCompleteUtil`を利用、既存コマンドと同様）。
エラー・成功メッセージはすべて`messages.yml`の`home.*`・`warp.*`キーを`ConfigManager.getMessage()`
経由で参照し、ハードコードしない（`TpaCore`と同じ方針）。

## 安全テレポート判定（TeleportSafetyUtil）

### 判定基準

destination の位置が「安全」であるとは、以下をすべて満たすこと:

1. 足元ブロック（destination位置）が`Block#isPassable()` — 実体を持たず、フェンス・塀・階段・
   ハーフブロックなどの当たり判定がある特殊形状も含めて正しく「通行可能」と判定される
2. 頭上ブロック（足元の1つ上）も同様に`isPassable()`
3. 床ブロック（足元の1つ下）が`isPassable() == false`（立てる実体がある）
4. 足元・床のいずれもハザード素材（`LAVA`, `FIRE`, `SOUL_FIRE`, `MAGMA_BLOCK`, `CACTUS`,
   `SWEET_BERRY_BUSH`, `WITHER_ROSE`, `POWDER_SNOW`, `CAMPFIRE`, `SOUL_CAMPFIRE`）に含まれない

`Material.isSolid()`ではなく`Block#isPassable()`を使うことで、フェンス・塀・ハーフブロック・階段と
いった「見た目は空気ではないが実際に通れる/通れない」の判定をBukkit標準APIの当たり判定に委ねる。

### 不安全時のフロー

1. `/home`・`/warp`実行時に`isSafe(destination) == false`と判定
2. その時点で保留中の確認（同一プレイヤー・同一コマンド種別）がなければ、`messages.yml`の
   `*.unsafe_warning`を送信し、`Map<UUID, PendingConfirm>`に`(destination, 発行時刻)`を記録
3. 10秒以内に同じプレイヤーが同じコマンドをもう一度実行し、かつ指定した名前（destination）が
   保留中の確認と一致する場合、保存座標へそのままテレポート（座標調整なし・自己責任）し、保留状態を
   クリアする
4. 10秒経過後に再実行した場合、または保留中とは異なる名前を指定した場合は、その新しい行き先に対して
   手順2からやり直す（改めて警告を出し、その行き先で保留状態を上書き）

`PendingConfirm`は`HomeCommand`・`WarpCommand`それぞれが自分専用の静的Mapを持つ（`TpaCore`の
`pendingTeleport`と同様の設計）。`TeleportSafetyUtil`はこのMapを引数で受け取り判定・更新する共通処理
のみを提供し、状態そのものは持たない。

## config.yml / messages.yml 追加

**config.yml:**
```yaml
home:
  max-per-player: 5
  cost: 0
warp:
  max-per-player: 5
  cost: 0
```

**messages.yml** に追加するキー（`home.*`・`warp.*`、warpのみ追加で`delete_no_permission_others`）:
`no_permission`, `limit_reached`, `name_taken`, `not_found`, `created`, `deleted`,
`unsafe_warning`, `unsafe_confirm_teleported`, `list_header`, `list_entry`, `list_empty`,
`insufficient_funds`

## テスト方針

リポジトリに自動テストの仕組みがないため（`src/test`なし）、`./gradlew build`でコンパイル確認した
うえで`./gradlew runServer`による実機確認を行う:

- sethome/home/delhome/homesの一連の操作、上限5件超過、名前重複エラー
- setwarp/warp/delwarp/warpsの一連の操作、上限5件超過、名前重複エラー（他プレイヤーとの重複含む）
- 経済コスト不足時のエラー、成功時の残高減少
- 不安全な地点（ブロックに埋まる・床が空洞・溶岩上など）での警告表示 → 10秒以内の再実行で強制テレポート
  → 10秒経過後は警告からやり直しになること
- 安全な地点への即時テレポート（警告が出ないこと）
- `stellaria.warp.delete.others`権限の有無による他人warp削除の可否
- `/stellariareload`後も設定値（上限・コスト）が反映されること
