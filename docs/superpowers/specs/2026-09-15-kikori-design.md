# 木こり機能（`/kikori`）設計

- 日付: 2026-09-15
- ステータス: 承認待ち

## 背景・位置づけ

「土地保護」「一括破壊系」「ショップ系」の3案のうち、一括破壊系（木こり／Tree Feller）を最初に実装する。斧を持った状態で丸太を1本壊すと、繋がっている**天然の木だけ**を連鎖的に伐採する機能。プレイヤーが建てた丸太・木材建築を誤って巻き込まないための人工物検知と、序盤で使わせないための経済連携（購入制）が主眼。

土地保護・ショップ系は別サブプロジェクトとして今後個別にbrainstormする。

## スコープ

**対象:**
- `/kikori`（トグル）・`/kikori on`・`/kikori off`・`/kikori buy`（購入）・`/kikori pass`（隠しサブコマンド、人工物検知の一時無視）
- 天然丸太の連鎖伐採（BFS、上限件数あり）と、伐採した丸太周辺の天然葉っぱの巻き込み削除
- 人工物（プレイヤー設置物）検知による中断・自動OFF・警告
- 5分間 木こり関連の操作（伐採実行）が無ければ自動OFF
- お金による機能アンロック（`EconomyManager` 連携）

**対象外:**
- 土地保護機能（別プロジェクト。本機能はむしろ将来の土地保護と自然に連携する設計にする — 後述）
- ショップ機能（別プロジェクト）
- 導入前から存在する丸太建築の完全な保護（`STRIPPED_*_LOG` は自動保護されるが、それ以外の未加工丸太建築は導入後にプレイヤーが触れる/置き直すまでは保護対象外）

## アーキテクチャ

### 新規ファイル

```
commands/
  KikoriCommand.java
managers/
  KikoriManager.java
listeners/
  KikoriListener.java
```

`AfkManager`（トグル＋タイムアウト自動判定）と`ElevatorManager`（アイテム判定）の構造を踏襲する。既存の「join/quit系リスナーを増やさない」方針（CLAUDE.md）は維持しつつ、`BlockPlaceEvent`/`BlockBreakEvent`は既存リスナーのどれにも属さない新規の関心事のため、`KikoriListener`を新規に立てる。

### `StellariaCore#onEnable` への追加

1. `players`テーブルへの`kikori_unlocked`列追加（後述のマイグレーション処理、`DatabaseManager.createTableIfNotExists`より後・`KikoriManager`構築より前）
2. `KikoriManager`を構築し、タイムアウト監視の`runAtFixedRate` tick（`kikori.enabled`がtrueの間のみ、10秒間隔・`AfkManager`と同方式）を開始
3. `KikoriListener`を登録（`BlockPlaceEvent`・`BlockBreakEvent`）
4. `KikoriCommand`を`kikori`コマンドに登録（`CommandExecutor`と`TabCompleter`の両方）
5. 退出時のクリーンアップ: 既存の`PlayerListener`（quitハンドラ）に`KikoriManager.removePlayer(uuid)`呼び出しを追加（進行中の伐採タスクのキャンセルとメモリ解放）

`plugin.yml`の`commands:`に`kikori`を追加。権限は`stellaria.kikori`（デフォルト`true`— 実際のアクセス制御は購入済みフラグで行うため、他コマンドと同様の形だけ揃える）。

## データ永続化

### `players`テーブルへの列追加

SQLiteは`ALTER TABLE players ADD COLUMN ...`に対応しているが、既存の`DatabaseManager.createTableIfNotExists`は新規テーブル作成のみを想定しており列追加の仕組みが無い。`DatabaseManager`に以下を追加する:

```java
public static void addColumnIfNotExists(String table, String columnDef) {
    // PRAGMA table_info(table) で既存カラム名一覧を取得し、
    // columnDef の先頭トークン（カラム名）が無ければ ALTER TABLE ADD COLUMN を実行
}
```

`StellariaCore#onEnable`で:
```java
DatabaseManager.addColumnIfNotExists("players", "kikori_unlocked INTEGER NOT NULL DEFAULT 0");
```

### チャンクPersistentDataContainerによる人工丸太の記録

丸太ブロック自体はタイルエンティティではないため`PersistentDataContainer`を直接持てない。代わりに**チャンクのPDC**（`Chunk#getPersistentDataContainer()`）に、そのチャンク内で人工物扱いすべき丸太のローカル座標を`PersistentDataType.LONG_ARRAY`で保持する。

- キー: `NamespacedKey(plugin, "kikori_artificial_logs")`
- 値: 各丸太を1個の`long`にパック（チャンクX 4bit・チャンクZ 4bit・ワールド高さ(-64〜319の384段) 9bitで計17bit、`long`に余裕で収まる）
- `BlockPlaceEvent`で丸太系ブロック（`Tag`または`Material`名が`_LOG`/`_WOOD`で終わる）が置かれたら、そのブロックのチャンクのPDCから配列を読み、座標を追加して書き戻す
- `BlockBreakEvent`で丸太が壊れたら、原因を問わず（斧の連鎖伐採・素手・爆発等）記録から削除。配列が空になったらキー自体を削除
- これにより記録サイズは「現存する人工丸太の数」に比例し、チャンクセーブと一緒に永続化されるため別テーブル管理は不要

### `STRIPPED_*_LOG` の扱い

皮むき丸太はバニラの自然生成には存在しないため、タグの有無に関わらず常に人工物として扱う。これにより導入前から存在する皮むき丸太建築もタグ無しで保護できる。

### 葉っぱの人工物判定

バニラの`Leaves#isPersistent()`をそのまま利用する。プレイヤーが直接設置した葉は`persistent=true`、木から自然生成/成長した葉は`persistent=false`になる仕様のため、独自タグ管理は不要。

## 伐採ロジック（`KikoriManager`）

### 状態管理

`AfkManager`と同様、インメモリのみ（永続化なし）:
- `Set<UUID> enabledPlayers`
- `Map<UUID, Long> lastFellMillis`（タイムアウト判定用）
- `Set<UUID> pendingPass`（`/kikori pass`で予約、実際に人工物を回避した時だけ消費）
- `Map<UUID, ScheduledTask> activeFellTasks`（進行中の1tickずつの伐採タスク。プレイヤーごとに同時1件まで）

### トリガー条件（`KikoriListener#onBlockBreak`）

以下を**すべて**満たした時のみ発動。1つでも欠ければバニラ通りの単発破壊（イベントに一切介入しない）:
1. `enabledPlayers`にプレイヤーが含まれる
2. メインハンドのアイテムが`Tag.ITEMS_AXES`に含まれる
3. 壊されたブロックが丸太系ブロックである
4. 壊されたブロック自体が人工物（タグ付き or `STRIPPED_*`）**ではない**（人工物なら警告無しでバニラ単発破壊のみ。自分の置いた1本を素直に壊すのは想定内の操作のため）

### 探索〜実行フロー

1. 起点の丸太から26方向隣接（斜めの分岐も拾う）でBFS。**樹種は問わず**丸太系ブロック全部を対象に、`kikori.max-logs`（デフォルト256）件まで収集
2. 探索中に人工物の丸太を検知したら:
   - `pendingPass`が**無ければ**: 直ちに探索・破壊を中止（起点の1本は手順4の通りバニラ破壊済みのまま）。`enabledPlayers`からプレイヤーを外し（＝OFF）、`pendingPass`もクリア。`messages.yml`の`kikori.artificial_detected`をアクションバー＋チャットで送信
   - `pendingPass`が**あれば**: その丸太も収集対象に含めて探索続行。ループ全体を通じて実際に人工物を回避できた場合のみ`pendingPass`を消費（何にも当たらなければ次回に持ち越し）
3. 中断されなかった場合、収集した各天然丸太を中心に半径3ブロック球内の葉っぱブロックを走査し、`isPersistent() == false`のものだけ破壊キューに追加（重複は除去）
4. 破壊キュー（丸太→葉っぱの順）を`Bukkit.getRegionScheduler().runAtFixedRate(plugin, originLocation, task -> {...}, 1L, 1L)`で1tickに1ブロックずつ処理。各ブロックは`player.breakBlock(block)`で破壊する
   - `player.breakBlock()`はバニラの`BlockBreakEvent`を内部で発火するため、耐久値減少・ドロップ・経験値がバニラ通りに動くだけでなく、**将来実装する土地保護機能のイベントキャンセルもここに自然に効く**（キュー処理中のブロックがキャンセルされたら、そのブロックだけスキップして続行）
   - キューが空になったらタスクを`cancel()`
5. `lastFellMillis`を伐採発動のたびに更新（人工物中断で即終了した場合も更新 — 「操作した」こと自体は事実のため）

### タイムアウト・状態リセット

- `kikori.enabled`がtrueの間、10秒間隔でtickし、`enabledPlayers`の各プレイヤーについて`now - lastFellMillis > kikori.timeout-seconds * 1000`ならOFFにする
- OFFになる経路（タイムアウト／`/kikori off`／`/kikori`トグルでOFF側）は**すべて共通の`disable(player)`ヘルパー**を通し、`enabledPlayers`から除去 + `pendingPass`もクリアする
- `removePlayer(uuid)`（退出時）: 上記に加えて`activeFellTasks`のタスクを`cancel()`してMapからも除去

## コマンド設計（`KikoriCommand`）

| サブコマンド | 動作 | 権限 | タブ補完 |
|---|---|---|---|
| `/kikori` | トグル（現在の状態を反転） | `stellaria.kikori` | 候補: `on`, `off`, `buy` |
| `/kikori on` | 強制ON | 同上 | 同上 |
| `/kikori off` | 強制OFF（`disable()`経由） | 同上 | 同上 |
| `/kikori buy` | 未購入なら`kikori.price`を`EconomyManager`から引き落とし`kikori_unlocked`を1に | 同上 | 同上 |
| `/kikori pass` | `pendingPass`予約 + 未ONなら自動でON | 同上 | **候補に出さない**（`TabCompleteUtil.filterStartsWith`に渡す候補リストから`pass`を除外するだけで、コマンド自体は`onCommand`内で通常通り処理する） |

- `kikori_unlocked`が0の状態で`on`/トグルON側を実行しようとしたら`kikori.not_unlocked`メッセージ（購入案内）を出して何もしない
- `buy`実行時、既に購入済みなら`kikori.already_unlocked`
- `buy`実行時、残高不足なら`kikori.buy_insufficient_funds`（`EconomyManager.has()`で事前チェック）

## Config / Messages キー一覧

### `config.yml` 追加分

```yaml
kikori:
  enabled: true
  price: 50000
  timeout-seconds: 300
  max-logs: 256
  leaf-radius: 3
```

### `messages.yml` 追加分

```yaml
kikori:
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  not_unlocked: "&%cこの機能は購入していません。&%e/kikori buy &%cで購入できます。（%price%円）"
  already_unlocked: "&%cこの機能は既に購入済みです。"
  buy_insufficient_funds: "&%c残高が足りません。（必要: %price%円）"
  buy_success: "&%a木こり機能を購入しました！&%7/kikori &%aで切り替えられます。"
  enabled: "&%a木こり機能をONにしました。&%7斧を持って丸太を壊すと発動します。"
  disabled: "&%7木こり機能をOFFにしました。"
  timeout_disabled: "&%7一定時間操作が無かったため、木こり機能を自動でOFFにしました。"
  artificial_detected: "&%c人工物を検知しました。木こり機能を自動でOFFにします。&%7無視する場合は隠しコマンドを使ってください。"
  pass_granted: "&%e次回、人工物を検知しても1回だけ無視して伐採を続行します。"
```

（`%price%`は`kikori.price`の値をそのまま埋め込む。`ConfigManager.getMessage()`は`%player%`しか置換しないため、`%price%`は`KikoriCommand`側で`getMessage()`が返す文字列に対して`.replace("%price%", ...)`する一手間を`getMessage()`呼び出し**前**の生文字列に対して行う必要がある — 具体的には`ConfigManager`に生文字列取得用のメソッドが無い場合、`PayCommand`等の既存の金額差し込み実装を参考に揃える。`artificial_detected`はアクションバーとチャットの両方に同じ内容を送る想定なので1キーで共用する。）

## エラーハンドリング

- プレイヤー以外からの`/kikori`系実行 → `kikori.must_be_player`
- 権限不足 → `kikori.no_permission`
- 未購入で有効化しようとした → `kikori.not_unlocked`
- 購入済みで再度`buy` → `kikori.already_unlocked`
- 残高不足で`buy` → `kikori.buy_insufficient_funds`
- 伐採中にプレイヤーが切断 → 進行中タスクを`cancel()`（エラーではなく正常系のクリーンアップ）
- 伐採中に対象ブロックが既に消えている/一致しない（他プラグインとの競合等）→ そのブロックだけスキップして次のキュー項目へ進む

## テスト方針

本リポジトリに自動テストは無く、`runServer`でのローカル確認もスキップし、実機（本番同等環境）で以下を確認する:

- `./gradlew build` が通ること（`DatabaseManager.addColumnIfNotExists`込みで既存の`players`テーブルにマイグレーションが正常に効くか、既存DBファイルで起動して確認）
- **購入フロー**: 未購入時に`/kikori`→`kikori.not_unlocked`表示 → 残高不足で`/kikori buy`→`kikori.buy_insufficient_funds` → 残高を用意して`/kikori buy`→成功メッセージ + 残高が`kikori.price`分減っていること → 再度`/kikori buy`→`kikori.already_unlocked`
- **トグル**: 購入後`/kikori`でON/OFF切り替わること、`/kikori on`・`/kikori off`個別に効くこと、`/kikori`のタブ補完に`on`/`off`/`buy`は出るが`pass`は出ないこと
- **斧・トグル条件**: ONの状態で斧を持たずに丸太を壊す→単発破壊のみ（連鎖しない）。斧を持って壊す→連鎖発動。OFFの状態では斧を持っていても単発破壊のみ
- **天然木の連鎖伐採**: 自然生成した木（できれば分岐の多いオーク/ジャングルの木）を1本壊し、繋がってる丸太が1tickずつ順番に壊れていく（一瞬で全部消えない）こと。伐採後、周辺の葉っぱ（半径3ブロック程度）も自然に消えるが、木から離れた場所の葉には影響しないこと
- **人工物検知（丸太）**: 導入後に丸太を設置してから天然木に隣接させ、その天然木を伐採→人工丸太の手前で中断し、自動OFF + `kikori.artificial_detected`が出ること。再度`/kikori`しないと有効化できないこと
- **`STRIPPED_*_LOG`の自動保護**: タグを付けていない皮むき丸太（既存建築を模したもの）を天然木に隣接させても、同様に中断されること
- **人工物検知（葉っぱ）**: 天然木の伐採範囲内に、プレイヤーが設置した葉ブロック（`persistent=true`）を置いておく → その葉だけ壊されず残り、他の天然葉は普通に消えること。中断は発生しない（葉は個別スキップのみ）こと
- **pass**: 人工物検知でOFFになった後、`/kikori pass`実行→自動でONに戻る + 次の伐採で人工物を巻き込んで続行できること。人工物に当たらない伐採を挟んだ場合はpassが温存され続けること。passを消費した後は再度人工物に当たると通常通り中断すること
- **タイムアウト**: `kikori.timeout-seconds`を短い値（例: 10秒）に変更して`/stellariareload`→ONのまま何もせず待つ→自動OFF + `kikori.timeout_disabled`表示。この時`pendingPass`があれば消えていること（`/kikori pass`→即放置→タイムアウト→もう一度pass無しで人工物に当てて通常通り中断することを確認）
- **退出時のクリーンアップ**: 連鎖伐採の途中（tick処理中）でプレイヤーがログアウト→サーバーログにエラーが出ない、キューが残り続けない
- **他プラグインとの協調**（もし手元にWorldGuard等の保護プラグインがあれば）: 保護リージョンに丸太の一部がまたがる木を伐採→保護範囲のブロックだけ壊れず残ること
