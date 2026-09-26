# 統計ランキング拡張 設計書

- 作成日: 2026-09-26
- 対象: StellariaCore（Paper 1.21.11）
- 状態: レビュー待ち
- 位置づけ: 独自進捗プロジェクトの第 1 段。本書の統計スナップショット基盤を、後続の進捗機能がカウンターの保存先として再利用する（「8. 後続プロジェクト」を参照）。

## 1. 目的

`/ranking` に、モンスター討伐数や釣り回数など、眺めて楽しい統計のランキングを追加する。あわせて、自分の順位を表示して軽い競争要素を持たせる。

### 成功条件

- `/ranking <種類> [ページ]` で、11 種類の統計ランキングを表示できる。
- 公開初日から、導入前にプレイした分の記録もランキングに反映されている。
- オフラインのプレイヤーもランキングに並ぶ。
- 一覧の末尾に、実行したプレイヤー自身の順位が表示される。
- `/settings` で「統計ランキングに載せない」を切り替えられる。所持金の公開設定とは独立している。
- 表示する種類・並び順・表示名を `config.yml` で変更できる。

## 2. 追加するランキング

値はすべてバニラの統計（`org.bukkit.Statistic`）から取得する。

| キー | 表示名 | 取得元 |
|---|---|---|
| `mobkills` | モンスター討伐数 | `MOB_KILLS` |
| `pvpkills` | プレイヤーキル数 | `PLAYER_KILLS` |
| `deaths` | 死亡回数 | `DEATHS` |
| `fishing` | 釣り回数 | `FISH_CAUGHT` |
| `mined` | 掘ったブロック数 | `MINE_BLOCK` を全ブロック種類で合計 |
| `placed` | 置いたブロック数 | `USE_ITEM` のうち `Material#isBlock()` が真の種類を合計 |
| `distance` | 移動距離 | 移動系統計（`WALK_ONE_CM`, `SPRINT_ONE_CM`, `CROUCH_ONE_CM`, `SWIM_ONE_CM`, `WALK_ON_WATER_ONE_CM`, `WALK_UNDER_WATER_ONE_CM`, `CLIMB_ONE_CM`, `FALL_ONE_CM`, `FLY_ONE_CM`, `AVIATE_ONE_CM`, `BOAT_ONE_CM`, `MINECART_ONE_CM`, `HORSE_ONE_CM`, `PIG_ONE_CM`, `STRIDER_ONE_CM`, `HAPPY_GHAST_ONE_CM` など）の合計。保存は cm、表示は m または km |
| `jumps` | ジャンプ回数 | `JUMP` |
| `trades` | 村人との取引数 | `TRADED_WITH_VILLAGER` |
| `bred` | 動物の繁殖数 | `ANIMALS_BRED` |
| `cake` | ケーキを食べた数 | `EAT_CAKE_SLICE` |

### 補足

- バニラの統計はプレイヤーごとに 1 つで、メインワールドの `stats/` に保存される。Multiverse で追加したワールドの分も合算される。リセット対象の `resource` 系ワールドには保存されないため、ワールドリセットで統計が消えることはない。
- ロビーやクリエイティブでの行動も集計に含める（本番サーバーと開発サーバーは分かれているため問題ない）。
- 木こり（`KikoriManager`）、一括採掘（`MineManager`）、範囲破壊（`ExcavationListener`）は、いずれも `player.breakBlock()` でブロックを壊している。これは手で掘った場合と同じ扱いになり `MINE_BLOCK` に加算されるため、追加の対応は不要である。
- 統計の取得元に使う `Statistic` がサーバーのバージョンに存在しない場合、その項目だけ加算対象から外し、起動時に警告を 1 回出す。

## 3. データの持ち方

### 3.1 テーブル

```
player_stat_snapshots
  uuid       TEXT NOT NULL
  stat_key   TEXT NOT NULL   -- 2 章のキー（mobkills など）
  value      INTEGER NOT NULL
  updated_at INTEGER NOT NULL
  PRIMARY KEY (uuid, stat_key)
```

`(stat_key, value)` にインデックスを張り、ランキングの並べ替えを速くする。

このテーブルはキーと値の組を保存する汎用の形にしておく。後続の進捗機能では、木こり本数などのカウンターを別のキーで同じテーブルに保存する想定である。ただし、ランキングに表示するのは `config.yml` の `ranking.stats` に列挙したキーだけとする。

### 3.2 取り込みのタイミング

新設する `StatSnapshotManager`（`managers/`）が、バニラの統計をテーブルに書き出す。

1. **ログアウト時**: `PlayerListener` の退出処理から、そのプレイヤーの全項目を書き出す。
2. **オンライン中の定期更新**: `ranking.snapshot-interval-minutes`（既定 5 分）ごとに、オンラインの全プレイヤー分を書き出す。
3. **起動時の取り込み**: スナップショットが 1 件もないプレイヤーだけを対象に、`OfflinePlayer#getStatistic` で統計ファイルから読み込む。サーバーが止まらないよう、1 tick あたり `ranking.backfill-per-tick`（既定 5 人）ずつに分けて処理する。これにより、導入前の記録も初日から反映される。

統計の読み取りはメインスレッドで行い、DB への書き込みだけを `DatabaseManager` の非同期メソッドで行う。1 人分の書き込みは `transaction()` でまとめる。

`mined` と `placed` は全ブロック種類（1,000 種類以上）を走査するため、読み取りの重さを計測する。問題があれば、全 `Material` のうち対象となる種類の配列を起動時に 1 回だけ作ってキャッシュする。

### 3.3 名前の解決

表示名は既存の `players.name` から取得する（`money` ランキングと同じ）。

## 4. 表示

### 4.1 コマンド

- 書式は `/ranking <種類> [ページ]` のまま変えない。`money` と `playtime` はそのまま残す。
- タブ補完の候補に、`ranking.stats` に列挙した種類を追加する。
- 不明な種類を指定したときのエラーメッセージに、使える種類の一覧を出す。

### 4.2 自分の順位

一覧とページャーの下に、次の 1 行を表示する。

```
あなたの順位: 12位 / 48人中（1,234）
```

- 順位は「自分より値が大きい公開プレイヤーの数 + 1」で求める。同じ値のプレイヤーは同じ順位になる。
- 自分が非公開にしている場合でも、本人には「非公開中のため参考順位」として表示する。
- コンソールから実行した場合は表示しない。
- `money` と `playtime` にも同じ行を追加する。

### 4.3 値の表示形式

| 種類 | 形式 |
|---|---|
| 回数・個数 | 3 桁区切り（例: `12,345`） |
| `distance` | 1 km 未満は `850 m`、それ以上は `12.3 km` |

## 5. 公開設定

- `players` に `hide_stats_ranking INTEGER NOT NULL DEFAULT 0` を追加する。
- `SettingsGui` に、所持金の公開設定と並べて「統計ランキングへの掲載」のトグルを追加する。見た目と文言は所持金の公開設定に合わせる。
- 非公開のプレイヤーは、2 章の統計ランキングと自分以外から見た順位計算から除外する。`playtime` ランキングにも同じフラグを適用する。
- `money` ランキングは、従来どおり `hide_balance` だけで判定する。

## 6. 設定とメッセージ

### 6.1 config.yml

```yaml
ranking:
  page-size: 10
  snapshot-interval-minutes: 5
  backfill-per-tick: 5
  # 表示する統計ランキング。並び順がタブ補完の順になる。
  stats:
    - mobkills
    - pvpkills
    - deaths
    - fishing
    - mined
    - placed
    - distance
    - jumps
    - trades
    - bred
    - cake
```

`stats` に書かれていないキーは、`/ranking` で指定できない。未知のキーが書かれていた場合は起動時に警告を出して無視する。

### 6.2 messages.yml

- `ranking.<キー>_header` と `ranking.<キー>_entry`（11 種類分）
- `ranking.self_rank`, `ranking.self_rank_hidden`
- `ranking.usage`, `ranking.invalid_type` の文言を、種類が増えたことに合わせて更新する
- `settings.stats_ranking_visibility`, `settings.stats_ranking_visible`, `settings.stats_ranking_hidden`, `settings.stats_ranking_visible_enabled`, `settings.stats_ranking_hidden_enabled`

## 7. テスト

- 単体テスト（`src/test`）: 距離の表示形式、順位の計算（同値の扱い）、`ranking.stats` の読み込みと未知キーの除外を、Bukkit に依存しない純粋なロジックに切り出してテストする。
- 手動確認（`./gradlew runServer`）:
  - ログアウト後にランキングへ値が反映される。
  - 導入前にプレイしたプレイヤーが、起動後の取り込みでランキングに出る。
  - 非公開に切り替えると他人の一覧から消え、本人には参考順位が出る。
  - `/mine` や木こりで壊した分が `mined` に加算される。

## 8. 後続プロジェクト

本書の範囲外とし、それぞれ別の設計書で扱う。

1. **独自進捗の基盤**: 進捗の定義、判定、バニラ進捗画面への登録、専用 GUI、難易度別の報酬（かんたん 50 円〜やりこみ 5,000 円程度、金額は `config.yml` で変更可能）。
2. **独自進捗の中身**: 経済・交流・土地・移動・採掘・プレイ時間・Warp/Home・投票・HeadShop・総合・隠し進捗を段階的に追加する。
3. **路線作成の一般開放**: 「他人が作った路線を使う」進捗の前提。鉄道側の権限と荒らし対策の変更を伴うため、進捗とは切り離す。

## 9. 本番反映時の注意

- **DB**: `player_stat_snapshots` テーブルの新規作成と、`players.hide_stats_ranking` 列の追加がある。どちらも起動時に自動で作成されるが、反映には再起動が必要である。初回起動時は全プレイヤー分の取り込みが走る。
- **config.yml**: `ranking.snapshot-interval-minutes`, `ranking.backfill-per-tick`, `ranking.stats` が増える。既存の `config.yml` には手動で追記が必要である（`stats` が無い場合は統計ランキングが 1 つも表示されない）。
- **messages.yml**: 6.2 のキーが増え、`ranking.usage` と `ranking.invalid_type` の既定文言が変わる。既存の `messages.yml` には手動で追記と更新が必要である。
