# 経済コマンド設計仕様

## 背景・ロードマップ上の位置づけ

`docs/superpowers/specs/2026-09-14-afk-heal-broadcast-design.md` の冒頭で決めた5分割ロードマップの
サブプロジェクト④にあたる。UtilsPluginからの移植ではなく、既存の`managers/EconomyManager`
（Vault連携済み・SQLite永続化）を土台にした新規コマンド群を実装する。

`config.yml`の`economy.default-balance`は現状 `!NOTE: 未実装` とコメントされ、どこからも読まれていない
（`PlayerJoinListener`が新規プレイヤー作成時に`coins`を`0`固定で挿入している）。このサブプロジェクトで
あわせて実装する。

## スコープ

**含む:**
- `/pay <player> <amount>` — プレイヤー間送金
- `/eco <give|set|take> <player> <amount>` — 管理者用の残高操作
- `/balance [player|top [page]]`（alias: `money`, `bal`） — 残高確認・ランキング表示
- 初期所持金（`economy.default-balance`）の実装

**含まない:**
- 銀行機能（`EconomyManager`は`hasBankSupport() -> false`のまま）
- 送金額の上限設定・確認ダイアログ（将来必要になれば別途検討）
- トランザクション履歴の閲覧コマンド

## アーキテクチャ概要

新規クラス:
- `commands/PayCommand` — `/pay`
- `commands/EcoCommand` — `/eco`（give/set/takeを1つの`CommandExecutor`でdispatch、`TpaCore`/`MuteCommand`と同じ方針）
- `commands/BalanceCommand` — `/balance`（引数なし/プレイヤー指定/`top`を1つの`CommandExecutor`でdispatch）

既存クラスの改修:
- `managers/EconomyManager` — `setBalance`/`transfer`/`getTopBalances`/`getPlayerCount`を追加
- `listeners/PlayerJoinListener` — 新規プレイヤー作成時の初期`coins`を`economy.default-balance`から読むように変更
- `StellariaCore` — 3コマンドの登録配線

## EconomyManager拡張

```java
/** Vaultの標準APIに無い「残高を指定額に設定する」操作。マイナス指定は禁止。 */
public boolean setBalance(OfflinePlayer player, double amount);

/**
 * from -> to へ amount を送金する。DatabaseManager.transaction() で2件のUPDATEを
 * 1トランザクションにまとめ、from の残高が不足していれば何もせず false を返す。
 */
public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount);

public record BalanceEntry(String name, int coins) {}

/** 残高降順で limit 件、offset 件スキップして取得する（/balance top のページング用）。 */
public List<BalanceEntry> getTopBalances(int limit, int offset);

/** players テーブルの総レコード数（ページ数計算用）。 */
public int getPlayerCount();
```

`transfer()`は`withdrawPlayer`/`depositPlayer`のように2回に分けて呼ぶのではなく、
`DatabaseManager.transaction()`（既存、`managers/DatabaseManager.java`）で1つのトランザクションに
まとめる。残高チェックは`getBalance(from)`をトランザクション開始前に読み、不足していれば
トランザクションを開始せずに`false`を返す（既存の`withdrawPlayer`と同じ「読んでから判定」方式 —
このリポジトリの`DatabaseManager`には行ロック機構が無いため、既存コードと同水準の一貫性
保証にとどめる）。

金額の表示は全コマンド共通で既存の`EconomyManager.format(double)`（万/億/兆表記＋「円」接尾辞）を使う。
スコアボード等の`%money%`プレースホルダーと表示形式が揃う。

## コマンド仕様

### `/pay <player> <amount>`

- 権限: `stellaria.pay`（`plugin.yml`に`default: true`で登録、全員許可）
- `amount`は1以上の整数（`Integer.parseInt`失敗または0以下なら`pay.invalid_amount`）
- 自分宛て禁止・相手オフライン禁止
- `EconomyManager.transfer()`が`false`を返したら`pay.insufficient_balance`
- 成功時、送信者・受信者双方に通知（`pay.sender`/`pay.receiver`、`%amount%`は`format()`済み文字列）

### `/eco <give|set|take> <player> <amount>`

- 権限: `stellaria.eco`（未登録、OP限定がデフォルト — give/set/take共通で1権限、`/mute`/`/unmute`と同じ方針）
- サブコマンドが`give`/`set`/`take`以外なら`eco.invalid_subcommand`
- `amount`は0以上の整数（`set`は0を許容、`give`/`take`は1以上必須）
- `give`: `depositPlayer` / `set`: `setBalance` / `take`: `withdrawPlayer`（残高不足なら`eco.insufficient_balance`、
  マイナス不可 — 設計質問で確認済みの方針どおり管理者操作でもマイナスは許可しない）
- 対象はオフラインでも実行可（`Bukkit.getOfflinePlayer`で解決、既存の`/mute`と同じ前提を踏襲）
- 成功時、実行者・対象双方に通知（対象がオンラインの場合のみ本人に通知。`eco.give_sender`/`eco.give_receiver`等）

### `/balance [player|top [page]]`（alias: `money`, `bal`）

- 権限: `stellaria.balance`（`plugin.yml`に`default: true`で登録）
- 引数なし: 自分の残高を`balance.self`で表示
- `top`以外の1引数: 対象プレイヤーの残高を表示。ただし別途`stellaria.balance.others`
  （未登録、OP限定がデフォルト）が必要 — 無ければ`balance.no_permission_others`
- `top [page]`: `economy.balance-top-page-size`（デフォルト10）件ごとに`EconomyManager.getTopBalances()`で
  取得し、`balance.top_header`（現在ページ/最大ページ）+ `balance.top_entry`（順位・名前・残高）のリストを表示。
  `page`省略時は1ページ目。範囲外のページ番号を指定したら空リストとして`balance.top_empty`を表示
  （エラーにはしない）

## 初期所持金の実装

`listeners/PlayerJoinListener#onJoin`の新規プレイヤー作成部分:

```java
if (!exists) {
    DatabaseManager.insertAsync("players", Map.of(
        "uuid", uuid,
        "name", event.getPlayer().getName(),
        "coins", 0
    ));
}
```

これを`config.yml`の`economy.default-balance`（デフォルト1000）から読むように変更する。
`config.yml`の該当コメント（`!NOTE: 未実装`）も削除する。

## config.yml 追加/変更キー

```yaml
economy:
  default-balance: 1000 # 新規プレイヤーの初期所持金
  balance-top-page-size: 10
```

## messages.yml 追加キー

```yaml
pay:
  usage: "&%c使用方法: /pay <プレイヤー> <金額>"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  self: "&%c自分自身に送金することはできません。"
  invalid_amount: "&%c金額は1以上の整数で指定してください。"
  player_not_found: "&%c%player% &%7はオンラインではありません。"
  insufficient_balance: "&%c所持金が不足しています。"
  sender: "&%a%player% &%7に%amount%を送金しました。"
  receiver: "&%a%player% &%7から%amount%を受け取りました。"

eco:
  usage: "&%c使用方法: /eco <give|set|take> <プレイヤー> <金額>"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  invalid_subcommand: "&%c不明なサブコマンドです。give/set/take のいずれかを指定してください。"
  invalid_amount: "&%c金額は0以上の整数で指定してください。"
  player_not_found: "&%c%player% &%7はオフラインまたは存在しません。"
  insufficient_balance: "&%c%player% &%7の所持金が不足しています。"
  give_sender: "&%a%player% &%7に%amount%を付与しました。"
  give_receiver: "&%a%amount% &%7を受け取りました。"
  set_sender: "&%a%player% &%7の所持金を%amount%に設定しました。"
  set_receiver: "&%aあなたの所持金が%amount%に設定されました。"
  take_sender: "&%a%player% &%7から%amount%を回収しました。"
  take_receiver: "&%c%amount% &%7が回収されました。"

balance:
  usage: "&%c使用方法: /balance [プレイヤー|top [ページ]]"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  no_permission_others: "&%c他人の残高を確認する権限がありません。"
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  player_not_found: "&%c%player% &%7はオフラインまたは存在しません。"
  self: "&%7所持金: %amount%"
  other: "&%7%player% &%7の所持金: %amount%"
  top_header: "&%6&l--- 所持金ランキング（%page%/%max_page%） ---"
  top_entry: "&%7#%rank% &%f%player% &%7- %amount%"
  top_empty: "&%7該当するプレイヤーがいません。"
```

`%amount%`/`%player%`/`%rank%`/`%page%`/`%max_page%`は各コマンドが手動で`.replace()`する
プレースホルダー（`tpa.tpa_warmup`の`%seconds%`と同じ扱い）。`%amount%`は常に
`EconomyManager.format()`済み（「円」接尾辞込み）の文字列を差し込む。

## 権限一覧

| 権限ノード | 用途 | デフォルト |
|---|---|---|
| `stellaria.pay` | `/pay` | **`plugin.yml`に`default: true`で登録**（全員許可） |
| `stellaria.balance` | `/balance`（自分の残高・`top`） | **`plugin.yml`に`default: true`で登録** |
| `stellaria.balance.others` | `/balance <player>`（他人の残高） | 未登録（OPのみ） |
| `stellaria.eco` | `/eco give\|set\|take` | 未登録（OPのみ） |

## エラーハンドリング一覧

| 状況 | メッセージキー |
|---|---|
| `/pay` 権限なし | `pay.no_permission` |
| `/pay` コンソール実行 | `pay.must_be_player` |
| `/pay` 引数不足 | `pay.usage` |
| `/pay` 金額が不正（0以下・数値でない） | `pay.invalid_amount` |
| `/pay` 自分宛て | `pay.self` |
| `/pay` 相手がオフライン | `pay.player_not_found` |
| `/pay` 残高不足 | `pay.insufficient_balance` |
| `/eco` 権限なし | `eco.no_permission` |
| `/eco` サブコマンド不正 | `eco.invalid_subcommand` |
| `/eco` 金額が不正 | `eco.invalid_amount` |
| `/eco` 対象が見つからない | `eco.player_not_found` |
| `/eco take` 残高不足 | `eco.insufficient_balance` |
| `/balance` 権限なし | `balance.no_permission` |
| `/balance <player>` 権限なし（他人分） | `balance.no_permission_others` |
| `/balance <player>` 対象が見つからない | `balance.player_not_found` |
| `/balance` コンソールで自分の残高（対象未指定） | `balance.must_be_player` |

## テスト方針

このリポジトリに自動テストは無いため、`./gradlew build`の成功に加えて`./gradlew runServer`での
手動確認を行う:

1. `/pay <player> 500` → 送受信双方に通知、双方の`%money%`表示（スコアボード等）が即座に反映される
2. 残高を超える金額で`/pay` → `pay.insufficient_balance`、残高は変化しない
3. `/eco give <player> 1000` / `/eco set <player> 0` / `/eco take <player> 100`（残高不足込み）が
   それぞれ正しく動作する
4. `/balance` → 自分の残高が表示される
5. `/balance <player>`（`stellaria.balance.others`なし） → `balance.no_permission_others`
6. `/balance top` → ランキングが降順で表示される、`/balance top 2`で2ページ目に切り替わる
7. 新規プレイヤーが初参加 → `economy.default-balance`（デフォルト1000）が初期所持金になっている
   （`/balance`で確認）
