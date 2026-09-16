# ヘッドショップ機能（`/headshop`）設計

- 日付: 2026-09-16
- ステータス: 承認待ち

## 背景・位置づけ

装飾用の頭（スカル）を購入できるショップ機能。運営が事前にminecraft-heads.com等から集めた頭のプールから、毎日5つをランダムに入れ替えて販売する。あわせて、サーバーに実在するプレイヤーの頭も（プールより少し高い価格で）購入できるようにする。`/menu`からも導線を作る。

## スコープ

**対象:**
- 運営が管理GUIでヘッドをプールに登録する仕組み
- 毎日決まった時刻に、プールから5つをランダム抽選して入れ替える日替わりロジック（前回の5つとは重複しない）
- 3行構成のメインGUI（中央5マスに本日のヘッド）
- プレイヤーヘッド一覧GUI（オンライン中・直近ログインのプレイヤーの頭を購入可能）
- `/menu`への導線追加

**対象外:**
- ヘッドごとの個別価格設定（価格はプール共通の固定価格＋プレイヤーヘッドのみ上乗せ額、の2種類のみ）
- HeadDatabase等の外部プラグイン連携（プールは運営が手動登録したものだけ）
- ヘッドの検索・お気に入り等の付加機能

## アーキテクチャ

### 新規ファイル

```
commands/
  HeadshopCommand.java
managers/
  HeadshopManager.java
gui/
  HeadshopGui.java
  HeadshopPlayerHeadsGui.java
  HeadshopAdminGui.java
```

`HomeManager`/`WarpManager`（`plugin`を保持し`DatabaseManager`を直接叩くManager、キャッシュなしでクエリ都度実行）の構造を踏襲する。日替わり抽選のみ`AutoBroadcastManager`と同じ`Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)`パターンで定期チェックする。GUI群は`gui/Gui`を継承し（`AdminShopGui`の購入フロー、`WarpSelectGui`のページングパターンを流用）、`GuiListener`は変更不要。

### `StellariaCore#onEnable`への追加

1. `headshop_pool` / `headshop_rotation` テーブル作成
2. `HeadshopManager`を構築（`plugin`保持、キャッシュは「本日のローテーション5件」のみ・都度DBから読む）し、`runAtFixedRate`で1分毎に日替わりチェックを開始
3. `HeadshopCommand`を`headshop`コマンドに登録（`CommandExecutor`のみ、GUI操作がメインなのでtab補完はサブコマンド`admin`のみ対応）
4. `config.yml`の`menu.items`に1エントリ追加、`MenuGui.onClick()`に`case "headshop"`を追加

退出時のクリーンアップは不要（GUIは`InventoryCloseEvent`で自然に閉じる、プレイヤーごとの一時状態を持たない）。

`plugin.yml`の`commands:`に`headshop`を追加。権限:
- `stellaria.headshop`（デフォルト`true`）— `/headshop`本体・購入
- `stellaria.headshop.admin`（デフォルト`op`）— `/headshop admin`。`stellaria.land.admin`と同様、独立した権限として定義する（`stellaria.admin`の子には入れない。子リストは`reload`/`broadcast`/`mute`/`eco`/`chat.color`/`heal`の初期からの少数のみで、`land.admin`等の新しい管理系権限は単独の`default: op`として追加されている現行の慣習に合わせる）

## データ永続化

### テーブル定義

```sql
CREATE TABLE IF NOT EXISTS headshop_pool (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  display_name TEXT NOT NULL,
  texture TEXT NOT NULL,
  added_by TEXT NOT NULL,
  added_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS headshop_rotation (
  date TEXT NOT NULL,
  pool_id INTEGER NOT NULL,
  PRIMARY KEY (date, pool_id)
);
```

`texture`はプレイヤーヘッドのテクスチャ用base64文字列（`PlayerProfile`の`textures`プロパティにそのまま渡せる形式）。

### 日替わりロジック（`HeadshopManager`）

1. 1分毎のティックで、サーバーのローカル時刻が`headshop.reset-time`（デフォルト`"12:00"`、`HH:mm`形式）以降か判定
2. 以降かつ、`headshop_rotation`に本日の日付（`LocalDate.now().toString()`）の行がまだ無ければ抽選を実行
3. `headshop_rotation`から直近の日付（本日より前で最大のもの）の`pool_id`一覧を取得し、これを除外候補とする
4. `headshop_pool`全件から除外候補を除いた集合から5件をランダム抽選。**除外後の候補が5件未満ならフォールバックとして除外を無視し**、プール全体から抽選する（コンソールに警告ログを出す。プールが5件未満の場合はある分だけ抽選する）
5. 抽選結果を`headshop_rotation`にINSERT
6. `getTodayHeads()`は毎回このテーブルをJOINして`headshop_pool`から表示用データを取得する（再起動しても同じ5件が維持される。インメモリキャッシュは持たない）

## メインGUI（`HeadshopGui`、3行=27スロット）

- 中央の行（スロット11・12・13・14・15）に本日の5ヘッド。ロアに価格を表示
- クリック購入: `EconomyManager.has()`で事前チェック→`withdrawPlayer()`→`transactionSuccess()`が真ならプレイヤーへ頭のアイテムを付与し購入成功メッセージ、偽なら残高不足メッセージ（`AdminShopGui`の既存フローを踏襲）
- スロット22（最下段中央）に「プレイヤーヘッド一覧」ボタン → クリックで`HeadshopPlayerHeadsGui`を開く
- それ以外のスロットは空のまま（`MenuGui`/`WarpSelectGui`等の既存GUIと同様、枠を装飾パネルで埋める慣習は無いため踏襲しない）

## プレイヤーヘッドGUI（`HeadshopPlayerHeadsGui`）

- 対象: 現在オンラインのプレイヤー、または`player_stats.last_logout`が`headshop.player-head-recent-days`（デフォルト30日）以内のプレイヤー
- `WarpSelectGui`と同じページングパターンでページ送り一覧表示。1件＝そのプレイヤーの頭アイテム（`SkullMeta#setOwningPlayer`で本物のスキンを表示）
- 価格 = `headshop.normal-price` + `headshop.player-head-markup`（固定額上乗せ）
- 購入フローはメインGUIと同じ（`EconomyManager`経由）
- **ヒント表示**: GUI内の空きスロット（例: 最上段の隅）に案内アイテム（本や看板アイコン等）を1つ配置し、ロアに「本日のおすすめヘッド（メインメニュー側）は毎日12:00に入れ替わります」といった趣旨の文言を表示する。`headshop.player-heads-hint`として`messages.yml`にロア行リストを定義し、`headshop.reset-time`の値を`%reset_time%`としてロアに埋め込む

## 管理者GUI（`HeadshopAdminGui`、`/headshop admin`）

- ページ送りの管理用インベントリ（1ページ45枠+ナビゲーション、`headshop_pool`を全件ページング表示）
- **登録**: 既存GUI（`AdminShopGui`等）と同様、このGUIも`onClick`で全クリックを`setCancelled(true)`する（実物のドラッグ&ドロップは起きない）。運営はヘッドアイテムをカーソルに乗せた状態で空きスロットをクリックする。そのクリック時の`event.getCursor()`が`PLAYER_HEAD`なら`SkullMeta#getPlayerProfile()`からtextureプロパティ（base64）を読み取り、アイテムのカスタム表示名（`displayName`が設定されていればそれ、無ければ`headshop.admin.unnamed-head`のデフォルト文言）とあわせて`headshop_pool`にINSERTする。イベント自体はキャンセルされるのでカーソルのアイテムは常にそのまま運営の手元に残る。同一texture文字列が既に登録済みの場合は登録せず`headshop.admin.duplicate`メッセージのみ表示。カーソルが`PLAYER_HEAD`ですらない場合は`headshop.admin.invalid_head`を表示
- **削除**: 既存登録済みのヘッドをシフトクリックすると、確認なしで`headshop_pool`から削除する（`land.unclaim`同様、即時実行系の操作として扱う）
- ナビゲーション枠（`WarpSelectGui`と同じ位置関係）に、登録操作のやり方を説明する案内アイテムを1つ常時表示する（`headshop.admin.hint`のロア）
- 権限: `stellaria.headshop.admin`

## コマンド設計（`HeadshopCommand`）

| コマンド | 動作 | 権限 |
|---|---|---|
| `/headshop` | メインGUIを開く | `stellaria.headshop` |
| `/headshop admin` | 管理者GUIを開く | `stellaria.headshop.admin` |

## Config / Messages キー一覧

### `config.yml` 追加分

```yaml
headshop:
  reset-time: "12:00"
  normal-price: 500
  player-head-markup: 300
  player-head-recent-days: 30
```

### `messages.yml` 追加分

```yaml
headshop:
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  title: "&%9&lヘッドショップ"
  player-heads-title: "&%9&lプレイヤーヘッド一覧"
  admin-title: "&%9&lヘッドショップ管理"
  purchased: "&%a%item% &%aを購入しました！（&%e%price% &%aコイン）"
  insufficient-funds: "&%c所持金が足りません（必要: &%e%price%&%c）"
  player-heads-hint:
    - "&%9&l| &%bヒント"
    - "&%7本日のおすすめヘッド（メイン画面）は"
    - "&%7毎日 &%e%reset_time% &%7に入れ替わります"
  player-heads-empty: "&%7対象のプレイヤーがいません"
  gui-previous-page: "&%e前のページ"
  gui-next-page: "&%e次のページ"
  gui-page: "&%7ページ %page%/%max_page%"
  admin:
    added: "&%aヘッドをプールに登録しました：&%e%item%"
    duplicate: "&%cこのヘッドは既に登録されています"
    invalid_head: "&%c頭アイテムをカーソルに乗せた状態でクリックしてください"
    unnamed-head: "名称未設定の頭"
    removed: "&%aプールからヘッドを削除しました：&%e%item%"
    hint:
      - "&%9&l| &%bヘッド登録のヒント"
      - "&%7頭アイテムをカーソルに乗せた状態で"
      - "&%7空きスロットをクリックすると登録されます"
      - "&%7既存の頭は &%eShiftクリック &%7で削除できます"
```

`gui-previous-page`/`gui-next-page`/`gui-page`/`player-heads-empty`は`HeadshopPlayerHeadsGui`と`HeadshopAdminGui`の両方のページングUIで共通利用する（`warp.gui_*`等、他機能のメッセージキーは流用しない）。`admin.hint`は管理者GUIの空きスロット付近に常時表示する案内アイテムのロア。

## エラーハンドリング

- `/headshop`系コマンドをプレイヤー以外が実行 → `headshop.must_be_player`
- `/headshop admin`を`stellaria.headshop.admin`無しで実行 → `headshop.no_permission`（`/headshop`本体は`plugin.yml`の`permission: stellaria.headshop`でBukkit標準の権限拒否に委ねる。`admin`サブコマンドだけ別権限を要求するため、これは`HeadshopCommand`内で手動チェックする——`WarpCommand`が`stellaria.warp`を手動チェックするのと同じ理由）
- プールが空の状態で日替わり抽選のタイミングが来た → 抽選をスキップし、メインGUIの該当スロットは空欄＋案内ロア表示（購入不可）
- 購入時の残高不足 → コインは引き落とさず`insufficient-funds`のみ表示
- 管理者GUIでの重複登録 → 登録せず`duplicate`メッセージ、カーソルのアイテムは常にそのまま（イベントを常にキャンセルするため元々インベントリからは動かない）
- カーソルが`PLAYER_HEAD`以外、またはtextureプロパティを持たない頭 → `invalid_head`

## テスト方針

本リポジトリに自動テストは無く、`runServer`での実機確認を行う:

- `./gradlew build`が通ること
- **プール登録**: `/headshop admin`を開き、頭アイテムをカーソルに乗せた状態で空きスロットをクリック→`headshop_pool`に登録されること。同じアイテムをもう一度登録しようとすると`duplicate`表示になること。シフトクリックで削除できること
- **日替わり抽選**: `headshop.reset-time`を数分後の時刻に設定して再起動→時刻到達後にメインGUIの5枠が埋まること。前日の`headshop_rotation`と重複しないこと（プールを10件程度用意してテスト）
- **メインGUI購入**: 所持金を用意して本日のヘッドを購入→残高が減り、頭アイテムが付与されること。残高不足で`insufficient-funds`表示になること
- **プレイヤーヘッドGUI**: オンライン中のプレイヤーの頭が一覧に出ること、購入時に価格が`normal-price + player-head-markup`になっていること、ヒントアイテムに`reset-time`の値が表示されていること
- **`/menu`連携**: `/menu`からヘッドショップのボタンでメインGUIが開けること
- **再起動後の一貫性**: 日替わり抽選後にサーバーを再起動し、同じ5件が維持されていること（`headshop_rotation`テーブルからの再構築を確認）
