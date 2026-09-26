# 独自進捗の中身（第 3 段） 設計書

- 作成日: 2026-09-26
- 対象: StellariaCore（Paper 1.21.11）
- 状態: レビュー待ち
- 前提: [独自進捗の基盤](2026-09-26-custom-advancements-design.md)（PR #41）と、累計で稼いだお金のランキング（PR #42、`economy.earned`）の上に作る。
- 進捗の一覧（ユーザー承認済み）: https://claude.ai/artifact/EkSL957FiNokd9UPQ8B9YW

## 1. 目的

承認済みの一覧のうち、T11（他人が作った路線に乗る）を除く 116 個の進捗を `advancements.yml` に定義し、各機能に判定用のカウント処理を追加する。既存のサンプル 5 個（S01, S02, P01, P02, M01）は一覧の ID と内容に合わせて置き換える。

### 範囲外

- T11 と、その前提になる路線作成のユーザー開放（別の設計で扱う）。
- 進捗の達成数ランキング（第 3 段の後に扱う）。

## 2. 進捗 ID とタブ

- 進捗 ID は一覧の ID を小文字にしたもの（`s01`, `e12` など）ではなく、意味の分かる英語名にする（例: `kikori_1000`）。一覧の ID は `advancements.yml` のコメントに残し、ユーザーとのやりとりに使う。
- 隠し進捗（H01〜H08）は `stellaria` タブに入れ、`hidden: true` にする。
- 親子関係（ツリー）は、同じ系列の段階（100 → 1,000 → 10,000 など）を親子にし、それ以外はタブのルートにぶら下げる。
- 報酬は難易度の既定額とし、一覧で上乗せした 4 個（S04: 3,000 円、S06: 10,000 円、S07: 20,000 円、P06: 10,000 円）だけ `reward` を書く。

## 3. カウンターのキーと記録する場所

各機能から `AdvancementManager` の `increment` / `addDistinct` / `addToCounter`（オフラインの相手向け）を呼ぶ。キーの一覧と呼び出し場所は次のとおり。

| キー | 型 | 記録する場所と条件 |
|---|---|---|
| `tour.commands` | distinct | `/home` `/warp` `/tpa` `/shop` `/land` の実行時（値はコマンド名） |
| `economy.zero` | event | 出金・送金の後、残高が 0 になったプレイヤー |
| `pay.sent_count`, `pay.sent_total` | counter | `/pay` 成功時（送った人。total は金額） |
| `pay.big` | event | `/pay` の金額が 100,000 以上 |
| `pay.one_yen` | event | `/pay` の金額が 1 |
| `pay.rich_one_yen` | event | 送金前の所持金が 1,000,000 以上で、金額が 1 |
| `pay.received_count` | counter | `/pay` 成功時（受け取った人。オフラインでも記録） |
| `pay.partners` | distinct | 送った人には相手、受け取った人には送り主の UUID |
| `shop.created` | event | ショップ作成時 |
| `land.shop_in_land` | event | ショップ作成位置が自分の保護した土地の中 |
| `shop.sold_count`, `shop.sold_items` | counter | 販売ショップで売れたとき（オーナー。items は個数） |
| `shop.customers` | distinct | 同上（オーナーに、買った人の UUID） |
| `shop.sales.<日付>` → `shop.daily100k` | counter → event | 同上。日本時間の日付ごとの売上を加算し、100,000 以上になったら event |
| `shop.sold_out` | event | 販売ショップの在庫が購入で 0 になったとき（オーナー） |
| `shop.bought_one` | event | 購入個数が 1 |
| `shop.bought_other` | counter | 他人のショップで購入 |
| `shop.bought_shops` | distinct | 同上（値はショップ ID） |
| `shop.sold_to_shop` | event | 他人の買取ショップに売ったとき（売った人） |
| `economy.earned` | counter | PR #42 で記録済み |
| `chat.messages` | counter | チャット送信時（ミュートなどでキャンセルされたものは除く） |
| `chat.day100` | event | 日本時間の同じ日に 100 回チャットした。日ごとの回数はメモリで数える（再起動でその日の回数はリセット） |
| `mention.targets` | distinct | メンションされた各プレイヤー（`@all` は除く） |
| `msg.targets` | distinct | 個人メッセージの宛先 |
| `msg.reply` | event | `/reply` の成功時 |
| `tpa.sent` | event | `/tpa`・`/tphere` のリクエスト送信時 |
| `tpa.accepted` | event | `/tpaccept`・`/tphaccept` の成功時（承認した人） |
| `land.visited_other` | event | 他人の土地に入ったとき（土地の表示処理と同じ場所） |
| `land.claimed` | event | 土地の保護成功時 |
| `land.area10`, `land.area25` | event | 保護成功後、その土地がつながったエリアのチャンク数が 10・25 以上 |
| `land.trusted_other` | event | メンバー追加の成功時（追加した人） |
| `land.became_member` | event | 同上（追加された人。オフラインでも記録） |
| `land.unclaimed` | event | 保護解除の成功時 |
| `lock.created` | counter | チェスト保護の作成時 |
| `lock.removed` | event | チェスト保護の解除時 |
| `rail.rides` | event | 高速鉄道で駅に到着したとき（乗っていた全プレイヤー） |
| `rail.lines` | distinct | 同上（到着駅の路線名） |
| `rail.stations` | distinct | 同上（到着駅名） |
| `rail.full_line` | event | 出発駅が路線の端の駅で、到着駅が反対側の端の駅 |
| `rail.distance` | counter | 到着時に、その乗車で走ったブロック数を加算 |
| `rail.ride5k`, `rail.ride20k` | event | 1 回の乗車で 5,000・20,000 ブロック以上 |
| `kikori.logs` | counter | 木こりで原木が壊れたとき（1 本ずつ） |
| `kikori.log_types` | distinct | 同上（原木の種類。樹皮付きの `_WOOD` は `_LOG` と同じ種類として数える） |
| `mine.blocks` | counter | 一括採掘で壊した個数（1 回の採掘の合計） |
| `mine.big` | event | 1 回の一括採掘で 32 個以上 |
| `join.count` | event | 基盤で記録済み |
| `join.3am` | event | 日本時間 3:00〜3:59 のログイン |
| `account.age7` / `age30` / `age100` | event | ログイン時、`OfflinePlayer#getFirstPlayed` から 7・30・100 日以上たっている |
| `login.streak7` | event | ログイン日（5.1）から計算した連続日数が 7 以上 |
| `login.active20of30` | event | 直近 30 日のうちログインした日が 20 日以上 |
| `home.set` | event | Home の登録時 |
| `home.count3`, `home.max` | event | 登録後の Home 数が 3 以上・上限（`home.max-per-player`）以上 |
| `home.teleports` | counter | Home へのテレポート成功時 |
| `warp.created` | event | Warp の作成時 |
| `warp.count3` | event | 作成後、自分が持つ Warp が 3 個以上 |
| `land.warp_in_land` | event | Warp の作成位置が自分の保護した土地の中 |
| `warp.visited` | distinct | Warp へのテレポート成功時（値は Warp 名） |
| `warp.visited_other` | distinct | 同上で、他人の Warp（値は Warp 名） |
| `warp.visited_owners` | distinct | 同上（値はオーナーの UUID） |
| `warp.my_used` | counter | 同上（オーナーに加算。オフラインでも記録） |
| `vote.started`, `vote.time_started`, `vote.weather_started` | event / counter | 時間投票・天気投票の開始時 |
| `vote.yes`, `vote.no` | event | 投票で賛成・反対したとき |
| `vote.participations` | counter | 開始・賛成・反対のすべて |
| `vote.passed`, `vote.rejected` | event | 投票終了時、始めた人に |
| `vote.server` | counter | 投票サイトでの投票（Votifier。オフラインでも記録） |
| `headshop.bought` | event | HeadShop での購入成功時 |
| `headshop.heads` | distinct | 同上（値は頭のテクスチャ） |
| `headshop.player_head` | event | プレイヤーの頭の購入 |
| `headshop.rotation_days` | distinct | 日替わりの頭の購入（値はローテーションの日付） |
| `headshop.spent` | counter | 購入額 |

`stat` 型（ジャンプ回数、移動距離、掘ったブロック数、プレイ時間）は基盤の仕組みで判定する。

## 4. 条件の種類の追加は行わない

一覧のすべての条件は、基盤の 6 種類（`counter`, `distinct`, `event`, `stat`, `all_of`, `completed`）で表せる。「エリアが 10 チャンク以上」「Home が 3 か所以上」のように「その時点の数」で決まる条件は、記録する側で判定して `event` にする。

## 5. 新しい仕組み

### 5.1 ログイン日の記録

```
player_login_days
  uuid TEXT NOT NULL, day TEXT NOT NULL,   -- 日本時間の yyyy-MM-dd
  PRIMARY KEY (uuid, day)
```

- ログイン時に `INSERT OR IGNORE` で当日を記録する。
- 連続日数と直近 30 日の日数は、直近 31 日ぶんの行を読んで純粋関数（`LoginDays`）で計算する。日付をまたいでログインし続けている場合に備え、日本時間の 0 時にもオンラインの全員の当日分を記録する。
- 導入前のログイン日は記録がないため、連続日数は導入日から数え始める。

### 5.2 日ごとの売上

- 販売ショップで売れるたびに、オーナーの `shop.sales.<日本時間の日付>` を同期で加算し、加算後の値を読む。100,000 以上になったら `shop.daily100k` を記録する。
- オーナーがオフラインでも DB だけで判定できるようにするため、この値だけは同期で読み書きする（1 回の購入につき 2 クエリ）。

### 5.3 高速鉄道の乗車記録

- `RailSession` に、出発駅名（発車時にトロッコの近くにある駅。無ければ null）と、その乗車で走ったブロック数を持たせる。
- 走ったブロック数は、既存の `tickMovement` でブロック座標が変わるたびに、前の座標との水平距離を加算する。
- 到着（`EndReason.ARRIVED`）時に、乗っていたプレイヤー全員について 3 章の `rail.*` を記録する。到着以外（降車・脱線・切断など）では記録しない。
- 始発から終着の判定は、到着駅の路線で、出発駅と到着駅が路線の両端（1 番目と最後）であること。片方向の路線では 1 番目から最後の向きだけを数える。

### 5.4 日ごとのチャット数

- `AdvancementManager` のメモリに、プレイヤーごとの「日付と回数」を持つ。日付が変わったら 0 から数え直す。ログアウトで破棄する。

## 6. テスト

- 単体テスト
  - `LoginDays`: 連続日数（途中の抜け、今日を含む・含まない）、直近 30 日の日数、月またぎ。
  - `RailRideRecord`（乗車の判定を切り出した純粋関数）: 両端の判定（往復・片方向・途中駅）、距離のしきい値。
  - `advancements.yml` 全体を `AdvancementDefinitions.parse` にかけて、警告が 0 件で 116 個すべてが読み込まれること、ID の重複がないこと、`all_of` と `completed` の参照が正しいこと。
- 既存テストがすべて通ること。
- 手動確認（`./gradlew runServer`）: タブごとに数個ずつ、実際に操作して達成できること。

## 7. 本番反映時の注意

- **DB**: `player_login_days` テーブルを新規作成する（起動時に自動作成、再起動が必要）。
- **advancements.yml**: 既存サーバーには基盤の PR で作られた（サンプル 5 個の）ファイルが残るため、新しい定義は自動では入らない。**既存の `advancements.yml` を削除するか、新しい内容で置き換えてから再起動する必要がある。** サンプルの進捗 ID は新しい定義でも同じ ID を使い、達成記録が引き継がれるようにする（`welcome`, `first_steps`, `playtime_1h`, `playtime_10h`, `kikori_100`）。
- **経済への影響**: 導入後の初回ログインで、既存プレイヤーは `stat` 型（プレイ時間・移動距離・ジャンプ・掘ったブロック数）と初ログインからの日数の進捗をさかのぼって達成し、報酬を受け取る。古参ほど多く受け取り、条件をすべて満たすプレイヤーは 1 人あたり最大 18,050 円になる（プレイ時間 6 個で 13,650 円、移動距離 3 個で 1,850 円、初ログインからの日数 3 個で 1,850 円、ジャンプと掘ったブロック数で 350 円、S01・S02 で 350 円。さかのぼりで達成できるのは 16 個なので、S05 の 20 個には届かない）。
