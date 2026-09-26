# 独自進捗の基盤 設計書

- 作成日: 2026-09-26
- 対象: StellariaCore（Paper 1.21.11）
- 状態: レビュー待ち
- 位置づけ: 独自進捗プロジェクトの第 2 段（第 1 段は [統計ランキング拡張](2026-09-26-ranking-stats-design.md)）。本書は基盤だけを扱い、100 個近い進捗の中身と各機能へのカウント処理の追加は第 3 段で扱う。

## 1. 目的

サーバー独自の進捗を、バニラの進捗画面（L キー）と専用 GUI の両方で見られるようにする。達成時には難易度に応じた報酬を支払い、サーバーの機能を一通り遊ぶ動機を作る。

### 成功条件

- `advancements.yml` に定義を書くだけで、進捗がバニラの進捗画面に表示される。
- 各機能のコードは、カウンターを増やす 1 行を呼ぶだけで進捗の判定に参加できる。
- 達成するとトーストが出て、難易度に応じた報酬が 1 回だけ支払われる。
- むずかしい・やりこみの進捗は、全員のチャットに達成が通知される。
- `/advancements` で、カテゴリごとの達成状況と未達成の進捗の進み具合（例: 340 / 1,000）を確認できる。
- `advancements.yml` の一部が間違っていても、その進捗だけが無効になり、プラグインは起動する。

### 範囲外

- 第 3 段で追加する進捗の中身（経済・交流・土地・移動・採掘・投票・HeadShop など）と、各機能へのカウント処理の追加。
- 路線作成の一般開放。

## 2. 全体の構成

| 要素 | 役割 |
|---|---|
| `advancements.yml` | タブと進捗の定義（表示名・説明・アイコン・難易度・条件） |
| `AdvancementDefinitions`（`utils/`） | `advancements.yml` の読み込みと検証。Bukkit に依存しない |
| `AdvancementRules`（`utils/`） | 条件の判定（達成したか、進み具合はいくつか）。Bukkit に依存しない |
| `AdvancementJson`（`utils/`） | 定義からバニラ進捗の JSON を作る。Bukkit に依存しない |
| `AdvancementRegistrar`（`managers/`） | バニラへの登録・更新・削除（`Bukkit.getUnsafe()`） |
| `AdvancementManager`（`managers/`） | カウンターの受け口、プレイヤーごとのキャッシュ、DB 保存、達成処理、報酬、バニラとの同期 |
| `AdvancementGui` / `AdvancementCategoryGui`（`gui/`） | 専用 GUI |
| `AdvancementCommand`（`commands/`） | `/advancements` と管理者用サブコマンド |

DB を正とし、バニラの進捗は表示係として扱う。報酬の支払い、`completed` の数え方、GUI の表示はすべて DB の状態から決める。

## 3. 定義ファイル `advancements.yml`

### 3.1 位置づけ

`customhead.yml` と同じく、`ConfigManager` に登録する独立した設定ファイルとする。進捗の表示名と説明はプレイヤー向けの文言だが、1 つの進捗の定義が 2 ファイルに分かれると管理しにくいため、`messages.yml` には置かない（CLAUDE.md の「文言は messages.yml」の例外として CLAUDE.md に明記する）。報酬や通知などの挙動の設定は `config.yml` に置く。

### 3.2 書式

```yaml
tabs:
  stellaria:
    title: "&%dすてらりあ"
    description: "サーバーを遊び尽くそう"
    icon: NETHER_STAR
    background: "minecraft:block/amethyst_block"
  mining:
    title: "&%a木こり・採掘"
    description: "木を切り、岩を掘る"
    icon: DIAMOND_PICKAXE
    background: "minecraft:block/stone"

advancements:
  kikori_100:
    tab: mining
    icon: WOODEN_AXE
    title: "&%a見習い木こり"
    description: "木こりで100本伐採する"
    difficulty: normal
    trigger:
      type: counter
      key: kikori.logs
      goal: 100
  kikori_1000:
    tab: mining
    parent: kikori_100
    icon: IRON_AXE
    title: "&%a森の敵"
    description: "木こりで1000本伐採する"
    difficulty: hard
    trigger:
      type: counter
      key: kikori.logs
      goal: 1000
```

| 項目 | 必須 | 内容 |
|---|---|---|
| `tab` | 必須 | `tabs` のキー |
| `parent` | 任意 | 同じタブ内の進捗 ID。省略時はタブのルートにぶら下がる |
| `icon` | 必須 | `Material` 名（アイテムとして存在するもの） |
| `title`, `description` | 必須 | `&%` パレットの色コードを使える |
| `difficulty` | 必須 | `easy` / `normal` / `hard` / `challenge` |
| `hidden` | 任意 | 既定は `false`。`true` なら達成するまでバニラ画面にも GUI にも中身を出さない |
| `reward` | 任意 | 報酬額の個別指定。省略時は難易度の既定額 |
| `trigger` | 必須 | 4 章を参照 |

進捗 ID は `[a-z0-9_]+` とし、バニラには `stellaria:<タブ>/<ID>` として登録する。タブのルートは `stellaria:<タブ>/root` とする。

### 3.3 タブのルート

バニラの進捗画面はルートごとにタブが分かれるため、`tabs` の各タブにルートの進捗を自動で作る。ルートは報酬なし・通知なしで、プレイヤーがログインしたときに自動で達成させる（ルートが未達成だとタブ自体が表示されないため）。

初期のタブは、第 3 段で使う次の 10 個を定義しておく: `stellaria`（総合・隠し）, `economy`, `social`, `land`, `transport`, `mining`, `playtime`, `warp_home`, `vote`, `headshop`。

## 4. 条件（trigger）

| `type` | 追加の項目 | 達成条件 | 進み具合 |
|---|---|---|---|
| `counter` | `key`, `goal` | カウンター `key` の値が `goal` 以上 | 値 / `goal` |
| `distinct` | `key`, `goal` | `key` に記録された異なる値の数が `goal` 以上 | 数 / `goal` |
| `event` | `key` | カウンター `key` が 1 以上（1 回起きた） | なし |
| `stat` | `key`, `goal` | 統計 `key` の値が `goal` 以上。`key` は統計ランキングのキー（`mined` など）か `playtime`（秒） | 値 / `goal` |
| `all_of` | `ids` | `ids` の進捗をすべて達成 | 達成数 / 件数 |
| `completed` | `goal` | ルートを除く達成済みの進捗が `goal` 個以上 | 数 / `goal` |

- `completed` は、自分自身と他の `completed` 型の進捗を数に含めない。
- `all_of` が循環している定義（A が B を含み、B が A を含む）は、読み込み時に両方を無効にする。
- `event` は `counter` の特殊形であり、同じカウンターを使える（例: `pay.sent_count` の 1 以上で「初めての送金」）。

### 4.1 カウンターの受け口

各機能のコードは、`AdvancementManager` の次のメソッドだけを呼ぶ。

```java
void increment(Player player, String key, long amount);   // counter / event
void addDistinct(Player player, String key, String member); // distinct
void checkStats(Player player);                            // stat（統計・プレイ時間の再判定）
```

- 呼び出しはメインスレッド（またはそのプレイヤーの region スレッド）から行う。
- キーは `<機能>.<内容>` の形にする（例: `kikori.logs`, `pay.recipients`）。定義に使われていないキーを渡しても何も起きない。
- `stat` 型は、ログイン時と統計スナップショットの定期書き出し（`ranking.snapshot-interval-minutes`）のタイミングで判定する。値はその場の統計（`StatSnapshotManager#readLive`）とプレイ時間（`PlaytimeManager#getPlaytimeSeconds`）から取る。

## 5. データ

### 5.1 テーブル

```
player_counters
  uuid TEXT NOT NULL, counter_key TEXT NOT NULL, value INTEGER NOT NULL,
  PRIMARY KEY (uuid, counter_key)

player_counter_members
  uuid TEXT NOT NULL, counter_key TEXT NOT NULL, member TEXT NOT NULL,
  PRIMARY KEY (uuid, counter_key, member)

player_advancements
  uuid TEXT NOT NULL, advancement_id TEXT NOT NULL,
  completed_at INTEGER NOT NULL, reward_paid INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (uuid, advancement_id)
```

統計ランキングの `player_stat_snapshots` はバニラ統計で毎回上書きされるテーブルのため、加算で貯めるカウンターとは分ける。

### 5.2 キャッシュ

- ログイン時に、そのプレイヤーのカウンター・distinct の件数・達成済み一覧を非同期で読み込み、メモリに保持する。読み込みが終わるまでに来た `increment` は、キャッシュに加算して読み込み結果とマージする。
- `distinct` はメンバーそのものではなく件数だけをキャッシュする。新しいメンバーかどうかは `INSERT OR IGNORE` の結果（追加された行数）で判定する。
- カウンターの書き込みは非同期で、`UPDATE ... SET value = value + ?` の加算として行う（キャッシュの値で上書きしない）。
- ログアウト時にキャッシュを破棄する。

## 6. 達成処理

`increment` などでキャッシュが変わったら、そのキーを使う進捗だけを判定する（キーから進捗への索引を読み込み時に作る）。達成したら次の順で処理する。

1. `player_advancements` に `INSERT ... ON CONFLICT (uuid, advancement_id) DO UPDATE SET completed_at = excluded.completed_at WHERE completed_at = 0` で達成を記録する。変更された行数が 0 なら、すでに達成済みなので何もしない（二重処理の防止）。`completed_at = 0` は `revoke` で未達成に戻された行を表す（8.2）。
2. バニラの進捗の条件 `done` を `AdvancementProgress#awardCriteria` で達成させる。これによりトーストと（設定されていれば）チャット通知が出る。
3. `UPDATE player_advancements SET reward_paid = 1 WHERE ... AND reward_paid = 0` の更新行数が 1 のときだけ、`EconomyManager` で報酬を入金し、本人に報酬のメッセージを出す。
4. この進捗に依存する `all_of` と `completed` の進捗を判定する。

### 6.1 報酬

| 難易度 | バニラの枠 | 既定の報酬 | チャット通知 |
|---|---|---|---|
| `easy` | task | 50 円 | なし |
| `normal` | task | 300 円 | なし |
| `hard` | goal | 1,500 円 | あり |
| `challenge` | challenge | 5,000 円 | あり |

既定額と通知の有無は `config.yml` の `advancements.difficulties.<難易度>.reward` と `.announce` で変えられる。通知の有無はバニラ進捗の JSON（`announce_to_chat`）に含まれるため、変更は再起動で反映される。サーバーの gamerule `announceAdvancements` が `false` の場合はチャット通知が出ない。

### 6.2 バニラとの同期

ログイン時に、DB で達成済みなのにバニラで未達成の進捗を見つけたら、バニラ側だけ達成させる（報酬は支払わない）。逆に、バニラで達成済みなのに DB に記録がない進捗は、バニラ側を未達成に戻す（`/advancement grant` などで DB を経由せずに付けられた場合）。

## 7. バニラへの登録

`AdvancementRegistrar` が起動時に次を行う。

1. 各定義から JSON を作る（`AdvancementJson`）。条件は `minecraft:impossible` の `done` を 1 つだけ持たせ、コードからのみ達成させる。
2. 全定義の JSON をまとめたハッシュを、前回起動時の値（プラグインのデータフォルダに保存）と比べる。同じなら何もしない。
3. 違う場合は、`stellaria` 名前空間の登録済み進捗のうち、定義にないもの・内容が変わったものを `removeAdvancement` で消し、新しいものを `loadAdvancement` で登録する。登録順は、親が子より先になるようにする。
4. 登録に失敗した進捗は警告を出して飛ばす。その進捗は GUI と DB 側の判定・報酬だけで動く。

`loadAdvancement` は非推奨扱いの `UnsafeValues` の API であり、登録内容はメインワールドのデータパック（`bukkit`）に保存されて再起動後も残る。`/stellariareload` では再登録しない。

## 8. 専用 GUI とコマンド

### 8.1 `/advancements`（別名 `/adv`, `/進捗`）

`/進捗` のように ASCII 以外の別名がクライアントのコマンド補完で正しく動くかは実機で確認し、動かない場合は別名から外す。

- **トップ画面**: タブごとにアイコンを並べ、「12 / 18 達成」を表示する。下段に全体の達成数と、受け取った報酬の合計を表示する。
- **カテゴリ画面**: そのタブの進捗を定義順に並べる。1 ページに収まらない場合はページ送りを付ける。
  - 達成済み: 進捗のアイコンにエンチャントの輝きを付け、説明に達成日時と報酬額を出す。
  - 未達成: 灰色の染料で、説明に進み具合（`340 / 1,000`）を出す。
  - 隠し進捗で未達成: 「？？？」とだけ表示する。
- `/menu` の画面に入り口のボタンを追加する（`config.yml` の `menu.items` の既定値に追加）。

### 8.2 管理者用

`/advancements admin <プレイヤー> grant|revoke <進捗ID>`（権限 `stellaria.advancements.admin`）

- `grant`: 通常の達成処理（報酬を含む）を行う。
- `revoke`: `player_advancements` の行は消さずに `completed_at` を 0 にして未達成扱いにし、バニラ側も未達成に戻す。支払済みの報酬は回収しない。`reward_paid` は残るため、再び達成しても報酬は二重に支払われない。

## 9. 設定とメッセージ

### 9.1 config.yml

```yaml
advancements:
  enabled: true
  difficulties:
    easy:      { reward: 50,   announce: false }
    normal:    { reward: 300,  announce: false }
    hard:      { reward: 1500, announce: true }
    challenge: { reward: 5000, announce: true }
```

### 9.2 messages.yml

`advancements.*` に次を追加する: 報酬受け取り、GUI のタイトル・ボタン・説明行（達成日時、進み具合、報酬額、隠し進捗の表示）、コマンドの使い方とエラー、管理者用コマンドの結果。

### 9.3 advancements.yml

3.2 の書式で、10 個のタブの定義と、動作確認用のサンプル進捗を数個入れる（`stellaria` タブの「初ログイン」`event`、`playtime` タブの「1 時間プレイ」`stat`、`mining` タブの「見習い木こり」`counter` など）。

## 10. テスト

- 単体テスト（Bukkit に依存しないクラス）
  - `AdvancementDefinitions`: 必須項目の欠け、未知の `type`・`difficulty`・`tab`、存在しない `parent`、`all_of` の循環、不正な ID を、それぞれその進捗だけ無効にして警告すること。
  - `AdvancementRules`: 6 種類の条件の達成判定と進み具合、`completed` が自分と他の `completed` を数えないこと、隠し進捗の扱い。
  - `AdvancementJson`: 難易度から枠と `announce_to_chat` が決まること、親の ID、ルートの背景。
- DB を使うテスト（一時ディレクトリの SQLite）: 達成の二重処理防止、報酬の二重支払い防止、`revoke` 後に再達成しても報酬を払わないこと、カウンターの加算。
- 手動確認（`./gradlew runServer`）: L キーで 10 個のタブが出ること、サンプル進捗のトーストと報酬、ログイン時の同期、`/advancements` の表示。

## 11. 本番反映時の注意

- **DB**: `player_counters`, `player_counter_members`, `player_advancements` の 3 テーブルを新規作成する。起動時に自動で作成されるが、反映には再起動が必要である。
- **新しい設定ファイル**: `advancements.yml` は初回起動時にデータフォルダへコピーされる。既存サーバーにも自動で作られる。
- **config.yml**: `advancements.*` と、`menu.items` への入り口ボタンの追加がある。既存の `config.yml` には手動で追記が必要である（`advancements` が無い場合は既定値で動く）。
- **messages.yml**: `advancements.*` が増える。既存の `messages.yml` には手動で追記が必要である。
- **ワールドのデータパック**: 初回起動時に `stellaria` 名前空間の進捗がメインワールドの `datapacks/bukkit` に書き込まれる。
