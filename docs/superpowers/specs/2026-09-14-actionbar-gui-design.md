# 基盤マネージャー（アクションバー同時表示 + GUIフレームワーク）設計仕様

## 背景・ロードマップ上の位置づけ

`docs/superpowers/specs/2026-09-14-afk-heal-broadcast-design.md` の冒頭で決めた5分割ロードマップの
サブプロジェクト③にあたる。UtilsPluginからの移植ではなく、StellariaCore独自の新規基盤機能として
「アクションバー同時表示マネージャー」と「GUI用の汎用フレームワーク」を実装する。

UtilsPluginの `MenuCommand` は実質GUIではなく、ナビゲーション用コンパスアイテムを配布するだけの
コマンドだった（`InventoryHolder`もクリックハンドリングも無い）。よって今回のGUIフレームワークは
移植ではなく新規設計とする。

## スコープ

**含む:**
- `ActionBarManager` — 複数の機能が同時にアクションバーへ表示要求を出しても、1行に連結して共存表示する
  基盤マネージャー
- 常設ステータス表示（config駆動テンプレート）
- `TpaCore` のテレポート待機（warmup）中に残り秒数をアクションバーへ表示する連携
- `AfkManager` がAFK/復帰した本人にアクションバーで一瞬通知する連携
- `Gui` 抽象クラス + `GuiListener` — インベントリベースのGUIを作るための汎用フレームワーク（具体的な
  メニュー画面・コマンドは含まない）

**含まない:**
- 具体的なGUIメニュー画面（`/menu`コマンド等）の実装 — フレームワークのみ
- `packetevents` を使った高度な演出（HUD風オーバーレイ、視聴者ごとの偽装アイテム表示など） —
  標準Bukkit Inventory APIで足りるため今回は使わない。将来、標準APIで実現できない具体的な要件が
  出てきた時に個別検討する
- アクションバー表示のON/OFFをプレイヤーごとに切り替える設定コマンド（将来必要になれば別途検討）

## アーキテクチャ概要

新規クラス:
- `managers/ActionBarManager` — チャンネル方式でプレイヤーごとのアクションバー表示を管理
- `gui/Gui` — `InventoryHolder`を実装した抽象クラス。具体的なGUI画面はこれを継承して作る
- `gui/GuiListener` — `InventoryClickEvent`/`InventoryCloseEvent`を購読し、`getHolder()`が`Gui`の
  インスタンスであれば対応するコールバックに振り分ける唯一のリスナー

既存クラスの改修:
- `commands/tpa/TpaCore` — `scheduleTeleport()`のテレポート待機中、1秒ごとに残り秒数をアクションバーへ
  反映する
- `managers/AfkManager` — `setAfk()`でAFK/復帰した本人にアクションバーで一瞬通知する
- `listeners/PlayerListener` — `onPlayerLeave`で退出プレイヤーの`ActionBarManager`チャンネル情報を破棄する
  （メモリリーク防止、既存の`AfkManager.removePlayer()`呼び出しと同じ並びに追加）
- `StellariaCore` — `ActionBarManager`のインスタンス化・tick登録、`GuiListener`の登録

## ActionBarManager — チャンネル方式の同時表示

アクションバーは1行しか表示できないため、「同時表示」は複数の発信元（チャンネル）の内容を
区切り文字で連結して1行にまとめることで実現する。

```java
public class ActionBarManager {
    private record ChannelEntry(Component content, long expiresAtMillis) {
        // expiresAtMillis < 0 は無期限（sustained）
    }

    // プレイヤーUUID -> (チャンネルID -> エントリ)。LinkedHashMapなので挿入順=表示順、
    // 既存キーの更新は順序を変えない。
    private final Map<UUID, LinkedHashMap<String, ChannelEntry>> channels;

    /** 無期限で表示し続けるチャンネルを設定/更新する（常設ステータス・TPAカウントダウンなど）。 */
    public void setChannel(Player player, String channelId, Component content);

    /** durationTicks 後に自動的に消えるチャンネルを設定する（AFK通知などの一時フラッシュ）。 */
    public void flash(Player player, String channelId, Component content, long durationTicks);

    /** チャンネルを即座に消す（TPAキャンセル時など）。 */
    public void clearChannel(Player player, String channelId);

    /** プレイヤー退出時に呼ぶ。保持しているチャンネル情報を全て破棄する（メモリリーク防止）。 */
    public void removePlayer(UUID uuid);

    /** action-bar.update-interval-ticks ごとにグローバルリージョンスケジューラから呼ばれる。 */
    public void tick();
}
```

`tick()`の処理:
1. 各オンラインプレイヤーについて、保持しているチャンネルのうち期限切れ（`expiresAtMillis >= 0` かつ
   現在時刻を過ぎている）のものを削除する
2. 残ったチャンネルが空なら何もしない（他プラグインが送っているアクションバーを不要に上書きしない）
3. 空でなければ、チャンネルの値を`action-bar.separator`で連結して`Player#sendActionBar(Component)`する

## 常設ステータス表示

`config.yml`の`action-bar.persistent.enabled`（デフォルト`false`）が`true`の場合、`tick()`の中で
`action-bar.persistent.template`を`PlaceholderManager.resolve()`で解決し、`"persistent"`という固定
チャンネルIDで`setChannel()`を呼ぶ（内容が変わっていなくても毎tick呼んで問題ない — `setChannel`は
同じキーへの上書きなので順序が保たれる）。デフォルトで無効にしておくことで、既存サーバーの見た目を
勝手に変えないようにする。

## TPAカウントダウン連携

`commands/tpa/TpaCore#scheduleTeleport()`の待機処理を、既存の1回きりの`runDelayed`に加えて
1秒間隔の`runAtFixedRate`タスクで残り秒数を計算し、`ActionBarManager.setChannel(mover, "tpa_countdown", ...)`
を呼ぶように拡張する。以下のタイミングで`clearChannel(mover, "tpa_countdown")`を呼ぶ:
- テレポート完了時（`performTeleport`実行後）
- ダメージによるキャンセル時（既存の`onEntityDamage`）
- 対象プレイヤーがオフラインになった時（既存の対象オフラインチェック）

表示テンプレートは`messages.yml`の`tpa.tpa_warmup_actionbar`（`%seconds%`プレースホルダー、
既存の`tpa.tpa_warmup`と同じ手動`.replace()`方式）。既存のチャットメッセージ通知（`tpa.tpa_warmup`）は
そのまま維持し、アクションバーは追加の演出として動く。

## AFK個人フラッシュ連携

`managers/AfkManager#setAfk()`の`broadcastStateChange()`呼び出し（既存の全体ブロードキャストはそのまま）
に加えて、対象プレイヤー本人に対して`ActionBarManager.flash()`を呼ぶ:
- AFKになった時: `messages.yml`の`afk.became_actionbar`を`"afk_flash"`チャンネルで
  `config.yml`の`afk.actionbar-flash-seconds`（デフォルト3秒）分表示
- 復帰した時: `afk.returned_actionbar`を同様に表示

## GUIフレームワーク

```java
public abstract class Gui implements InventoryHolder {
    private final Inventory inventory;

    protected Gui(int size, Component title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    /** クリック時に呼ばれる。デフォルトは何もしない（サブクラスでオーバーライドして使う）。 */
    public void onClick(InventoryClickEvent event) {
    }

    /** 閉じた時に呼ばれる。デフォルトは何もしない。 */
    public void onClose(InventoryCloseEvent event) {
    }
}
```

`GuiListener`はプラグイン全体で1つだけ登録し、`InventoryClickEvent`/`InventoryCloseEvent`の
`event.getInventory().getHolder()`が`Gui`のインスタンスであれば、対応する`onClick`/`onClose`に
委譲する（`Gui`ではない通常のプレイヤーインベントリ操作には一切干渉しない）。

このタスクでは具体的な`Gui`のサブクラス・コマンドは作らない。将来の機能（例: 経済コマンドの
確認画面など）がこのフレームワークを継承して使う想定。

## config.yml 追加キー

```yaml
action-bar:
  enabled: true
  update-interval-ticks: 5
  separator: "&%7 | &r"
  persistent:
    enabled: false
    template: ""

afk:
  actionbar-flash-seconds: 3 # 既存の afk: セクションに追記
```

## messages.yml 追加キー

```yaml
afk:
  became_actionbar: "&e離席中です"
  returned_actionbar: "&aお帰りなさい"

tpa:
  tpa_warmup_actionbar: "&e%seconds%秒後にテレポートします"
```

## エラーハンドリング

- `action-bar.enabled`が`false`の場合、`ActionBarManager.tick()`自体を登録しない（AFK/TPAからの
  `setChannel`/`flash`呼び出しは内部で無視して何もしない、NPEにはしない）
- `Player#sendActionBar`はオフラインプレイヤーに対しては呼ばれない設計（`tick()`は
  `Bukkit.getOnlinePlayers()`のみを対象にする）ため、プレイヤー離脱時の特別なクリーンアップは不要
  （`channels`マップの該当UUIDエントリは`PlayerQuitEvent`で明示的に破棄し、メモリリークを防ぐ）
- `GuiListener`は`getHolder()`が`Gui`でなければ即座に何もしないため、通常のインベントリ操作
  （チェスト・かまど等）には影響しない

## テスト方針

このリポジトリに自動テストは無いため、`./gradlew build`の成功に加えて`./gradlew runServer`での
手動確認を行う:

1. `config.yml`の`action-bar.persistent.enabled`を`true`にして`/stellariareload` →
   アクションバーに常設テンプレートが表示される
2. `/tpa <player>`→承認 → テレポート待機中、アクションバーに残り秒数がカウントダウン表示される
   （常設ステータスが有効なら`|`区切りで両方見える）
3. テレポート待機中にダメージを受ける → アクションバーのカウントダウンも消える（チャットキャンセル
   通知と同時に）
4. 一定時間操作せずAFKになる → 本人のアクションバーに一瞬「離席中です」が表示され、数秒で消える
   （常設ステータスが有効ならその後は常設表示に戻る）
5. 移動してAFKから復帰 → 「お帰りなさい」が一瞬表示される
6. `action-bar.enabled`を`false`にして`/stellariareload` → 上記すべてのアクションバー表示が出なくなる
   （エラーは出ない）
7. `Gui`/`GuiListener`はビルド成功のみ確認（具体的な画面が無いため実機での見た目確認は無し）
