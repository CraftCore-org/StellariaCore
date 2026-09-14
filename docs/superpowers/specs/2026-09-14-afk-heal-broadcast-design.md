# AFK / Heal / Broadcast 機能追加 設計

- 日付: 2026-09-14
- ステータス: 承認待ち（サブプロジェクト①）

## 背景・位置づけ

StellariaCore に UtilsPlugin から機能移植・新規機能を追加する大きめの変更計画のうち、最初のサブプロジェクト。
全体は以下のサブプロジェクトに分解されており、本スペックはこのうち①のみを対象とする。他は別途スペックを作成する。

1. **基本ユーティリティコマンド（本スペック）** — AFK / Heal / Broadcast（手動＋定期）
2. プレイヤー間メッセージ機能 — PM（tell/msg）＋ レベル別ミュート機能（3段階、レベル指定なしなら公開チャットのみ対象）
3. 基盤マネージャー — アクションバー同時表示マネージャー（AFK/警告系の一時メッセージ・常設ステータス表示・TPAカウントダウン等の機能連動を想定）／GUI用の汎用フレームワーク
4. 経済コマンド — pay・admin系（give/set/take）
5. 雑タスク — メンションフォーマットのconfigデフォルト値変更（`mention.format` を `[@Username]` → `@Username` 相当に変更するだけ。コード変更なし）

追加候補（本計画の対象外・将来検討）: home/warp、kit配布、`/back`、vanish、`/seen` 等の監査コマンド。

## スコープ

**対象:**
- `/afk` — AFKトグル、タイムアウト自動判定、タブリスト/スコアボード/ビロウネームへの視覚的表示
- `/heal [player]` — 自分または他人（別権限）のHP・満腹度回復、炎消火
- `/broadcast <message>`（alias `bc`） — 手動の全体アナウンス
- 定期自動放送（config駆動、複数メッセージを順送り）
- `/stellariareload` 実行時の定期放送リスタート対応

**対象外:**
- ミュート・PM・アクションバー管理・GUI基盤・経済コマンドは別サブプロジェクト
- AFKプレイヤーのPvP保護やダメージ無効化などのゲームプレイ的な副作用（今回は表示と通知のみ）

## アーキテクチャ

### 新規ファイル

```
commands/
  AfkCommand.java
  HealCommand.java
  BroadcastCommand.java
managers/
  AfkManager.java
  AutoBroadcastManager.java
```

Heal は状態を持たないため専用 Manager は作らず、`HealCommand` 内に処理を書く。

### `StellariaCore#onEnable` への追加

既存の「1〜7の手順」の後（TpaCore登録の前後どちらでもよいが、`PlaceholderManager` 構築より前に `AfkManager` を構築する必要がある — `%afk%` トークン解決が `AfkManager` を参照するため）:

1. `AfkManager` を構築し、AFKタイムアウト監視の `runAtFixedRate` tick（10秒間隔）を開始
2. `PlayerListener` に `PlayerMoveEvent`（位置変化時のみ）・`PlayerInteractEvent` を追加し `AfkManager.updateActivity()` を呼ぶ。退出時は既存の quit ハンドラ内で `AfkManager.removePlayer()` を呼ぶ
3. `AutoBroadcastManager` を構築し、`broadcast.auto.enabled` が true なら定期放送 tick を開始
4. `AfkCommand` / `HealCommand` / `BroadcastCommand` を対応コマンドに登録
5. `ReloadCommand`（`/stellariareload`）経由で呼ばれる `StellariaCore#reloadFeatureManagers()` に `AutoBroadcastManager.restart()` を追加

`plugin.yml` の `commands:` に `afk` / `heal` / `broadcast`（`aliases: [bc]`）を追加。

## AFK設計

### 状態管理（`AfkManager`）

- `Set<UUID> afkPlayers` と `Map<UUID, Long> lastActivityMillis`（インメモリのみ、TPAリクエストと同様に永続化なし）
- `isAfk(UUID)` / `updateActivity(Player)` / `setAfk(Player, boolean)`（手動トグル・タイムアウト両方から呼ばれる） / `removePlayer(UUID)`
- タイムアウト監視は `Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> ..., 200L, 200L)`（10秒間隔、既存のScoreboard等のtickerと同方式）。`config.yml` の `afk.enabled` が false ならタイマー自体を止める
- `updateActivity()` 呼び出し時、既にAFK中なら自動的に `setAfk(player, false)` して復帰通知を出す

### 有効化トリガー

`PlayerListener`（既存、tablist更新・tpaクリーンアップ担当）に追加:
- `PlayerMoveEvent`: `event.hasChangedPosition()` が true の場合のみ `updateActivity`
- `PlayerInteractEvent`: 常に `updateActivity`
- Quit時: `AfkManager.removePlayer(uuid)`

新規リスナークラスは作らない（CLAUDE.md記載の既存3リスナー分割パターンにこれ以上増やさない）。

### 視覚表示

`PlaceholderManager` に `%afk%` トークンを追加。AFK中は `config.yml` の `afk.tag`（例: `"&%7[AFK] &r"`）、非AFK中は空文字に解決する。`resolveBuiltIn()` は `plugin.getAfkManager().isAfk(player.getUniqueId())` を参照する形で実装するため、`AfkManager` は `PlaceholderManager` の tick 開始より前に構築されている必要がある（前述の onEnable 順序を参照）。

これにより `tablist.value` / `scoreboard.lines` / `belowname.title` の各テンプレートに `%afk%` を書き込めば、既存の `PlaceholderManager.resolve()` 経由で自動的に反映される。デフォルト設定でのテンプレートへの組み込み例（`config.yml` のデフォルト値変更として実施）:
- `tablist.value`: `"%afk%&%7%ping%ms"`
- `belowname.title`: `"%afk%&%c❤"`

### 通知

AFKになった/復帰した際、`messages.yml` の `afk.became` / `afk.returned`（`%player%` 差し込み対応）を `Bukkit.broadcast()` 相当で全体送信。

## Heal設計

- `/heal`: 引数なし → 自分自身、`args[0]` あり → 対象プレイヤー（要 `stellaria.heal.others`）
- 処理: `MAX_HEALTH` 属性値までHP回復、満腹度・隠し満腹度を20に、`fireTicks` を0に
- 権限: `stellaria.heal`（基本）、`stellaria.heal.others`（他人指定時に追加で必要）
- メッセージ: `messages.yml` の `heal.self` / `heal.other_sender` / `heal.other_receiver` / `heal.player_not_found`（`%player%` 差し込み）

## Broadcast設計

### 手動 (`/broadcast`, alias `bc`)

- 権限: `stellaria.broadcast`（送信権限）、`stellaria.broadcast.color`（メッセージ内で `&` カラーコードを使う権限。`chat.color-codes.permission` とは別ノードとして切る — broadcast権限があっても色を使えるとは限らないため）
- 引数を結合し、`messages.yml` の `broadcast.format`（`%message%` プレースホルダ）でラップして `Bukkit.broadcast()` 相当で全体送信
- 引数なし → 使用方法メッセージ（`messages.yml` の `broadcast.usage`）

### 定期自動放送 (`AutoBroadcastManager`)

- 設定は `config.yml` の `broadcast.auto.*`（`enabled: false`（デフォルトOFF） / `interval-minutes: 5` / `messages: []`）— スコアボード等の既存configテンプレートと同じ扱いで、messages.ymlではなくconfig.yml側に置く
- `Bukkit.getGlobalRegionScheduler().runAtFixedRate` で `interval-minutes` 分ごとに `messages` リストを順送り再生。各メッセージは `PlaceholderManager.resolve()` を通してから送信（`%online%` 等のトークンが使える）
- `restart()` メソッドで `/stellariareload` 時にインデックスをリセットして再起動できるようにする（UtilsPlugin版の `restart()` を踏襲）
- メッセージリストが空の場合は警告ログを出してタイマーを起動しない

## Config / Messages キー一覧

### `config.yml` 追加分

```yaml
afk:
  enabled: true
  timeout-seconds: 300
  tag: "&%7[AFK] &r"

broadcast:
  auto:
    enabled: false
    interval-minutes: 5
    messages: []
```

### `messages.yml` 追加分

```yaml
afk:
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  became: "&%e%player% &%7がAFK（離席中）になりました。"
  returned: "&%e%player% &%7がAFKから復帰しました。"

heal:
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  self: "&%a体力と満腹度を回復しました。"
  other_sender: "&%a%player% &%7の体力と満腹度を回復しました。"
  other_receiver: "&%a体力と満腹度が回復されました。"
  player_not_found: "&%c%player% &%7はオンラインではありません。"

broadcast:
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  format: "&%6&l[お知らせ] &r%message%"
  usage: "&%c使用方法: /broadcast <メッセージ>"
```

（`heal.player_not_found` は `%player%` 置換前提。`broadcast.format` は既存の `%player%` 方式に合わせて `%message%` プレースホルダに統一する。`must_be_player`/`no_permission` は featureごとに個別キーを持つ方針— 現状 `messages.yml` に共通キーが無いため、既存の `tpa.tpa_err_*` のようなfeature名前空間パターンを踏襲する。）

## エラーハンドリング

- プレイヤー以外からの `/afk` 実行 → `afk.must_be_player`
- 権限不足 → 各コマンドで `hasPermission` チェック後、`afk.no_permission` / `heal.no_permission` / `broadcast.no_permission`
- `/heal <存在しないプレイヤー>` → `heal.player_not_found`
- `/broadcast`（引数なし） → `broadcast.usage`

## テスト方針

本リポジトリに自動テストは無いため（CLAUDE.md記載の通り）、以下を手動確認する:

- `./gradlew build` が通ること
- `./gradlew runServer` 上で:
  - `afk.timeout-seconds` を短く設定し、放置でAFK自動遷移・タブリスト/ビロウネームに `%afk%` タグが出ることを確認
  - 移動・インタラクトでAFK解除されることを確認
  - `/heal`・`/heal <他プレイヤー>`（権限あり/なし）の動作確認
  - `/broadcast` 手動実行、および `broadcast.auto.enabled: true` での定期放送・`/stellariareload` 後の再起動を確認
