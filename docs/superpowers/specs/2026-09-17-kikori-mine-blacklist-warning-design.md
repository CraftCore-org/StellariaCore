# Kikori/Mine：無効化ワールドでの採掘試行にアクションバー警告 設計

- 日付: 2026-09-17
- ステータス: 承認待ち

## 背景・位置づけ

`docs/superpowers/specs/2026-09-16-world-blacklists-design.md`で導入した機能別ワールドブラックリストにより、`/kikori`・`/mine`コマンド自体はブラックリスト世界では実行できない（`kikori.world_disabled`/`mine.world_disabled`を返す）。

ただし、**別のワールドで機能をONにしてから、そのままブラックリスト世界へ移動して丸太・鉱石を壊すケース**は現状カバーしきれていない。`KikoriListener`/`MineListener`の`onBlockBreak`は、ブラックリスト判定に該当すると連鎖採伐・一括採掘の開始（`tryStartFelling`/`tryStartMining`）だけを抑制して黙って`return`する。単発の破壊自体は`BlockBreakEvent`をキャンセルしていないため通常通り成功し、プレイヤーには何の通知も出ない。「なぜ連鎖が起きないのか」が伝わらない状態になっている。

## スコープ

**対象:** `KikoriListener#onBlockBreak`・`MineListener#onBlockBreak`のブラックリスト判定部分に、既存の`kikori_warning`/`mine_warning`アクションバーチャンネルを使った警告を追加する。

**対象外:**
- 単発の手掘り（vanillaの通常のブロック破壊）自体を妨げることはしない。ブラックリスト世界でも丸太・鉱石を1個ずつ普通に壊すことは常に許可する（メモの「掘れないように」は連鎖だけを指す、とユーザーに確認済み）。
- `/kikori`・`/mine`コマンド自体の`world_disabled`メッセージは変更しない。
- クールダウン制御の追加は行わない（`flash()`は上書き式で連投してもチャットスパムにならないため）。

## 実装方針

`KikoriManager#tryStartFelling`・`MineManager#tryStartMining`内で「人工物を検知して中断した」場合に既に使われているパターン（チャットメッセージ送信＋同名チャンネルへの`ActionBarManager#flash`）を、ブラックリスト判定にもそのまま踏襲する。

### `KikoriListener#onBlockBreak`

```java
if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("kikori.disabled-worlds", true), block.getWorld().getName())) {
    if (plugin.getKikoriManager().isEnabled(player.getUniqueId()) && isHoldingAxe(player)) {
        String warning = plugin.getConfigManager().getMessage("kikori.world_disabled", player);
        plugin.getActionBarManager().flash(player, "kikori_warning", ColorUtil.component(warning), 60L);
    }
    return;
}
```

- 警告を出すのは「機能がONで、かつ斧を持って丸太を壊そうとした」場合のみに絞る（機能をOFFにしている、あるいは斧を持たずただ観光で丸太を壊しているだけのプレイヤーにまで警告を出すと煩わしいため）。この条件は既存の`isEnabled`/`isHoldingAxe`チェックを、ブラックリスト判定ブロックの中で先取りする形になる。
- メッセージは新規キーを追加せず、コマンド側と同じ`kikori.world_disabled`（既存: `"&%cこのワールドでは木こり機能を利用できません。"`）を流用する。チャット欄への平文送信は行わず、アクションバーのみ（ブロック破壊のたびに毎回チャットへ流れるとログが埋まるため、既存の`artificial_detected`のような一時的な情報はアクションバーで十分という判断）。
- `ColorUtil`のimportが必要（`KikoriListener`にはまだ無い）。

### `MineListener#onBlockBreak`

同じ形で、`mine.world_disabled`・`"mine_warning"`チャンネルを使う。

```java
if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("mine.disabled-worlds", true), block.getWorld().getName())) {
    if (plugin.getMineManager().isEnabled(player.getUniqueId()) && isHoldingPickaxe(player)) {
        String warning = plugin.getConfigManager().getMessage("mine.world_disabled", player);
        plugin.getActionBarManager().flash(player, "mine_warning", ColorUtil.component(warning), 60L);
    }
    return;
}
```

## エラーハンドリング

追加のエラーケースは無い。`ActionBarManager#flash`は既存の`artificial_detected`ケースと同じ呼び出し形なので、失敗モードも同じ（存在しないチャンネルという概念は無く、単に上書き表示されるだけ）。

## テスト方針

本リポジトリに自動テストは無く、`runServer`での実機確認を行う:

- `./gradlew build`が通ること
- `kikori.disabled-worlds`/`mine.disabled-worlds`に検証用ワールドを追加
- 通常ワールドで`/kikori on`・`/mine on`→対象ワールドへ移動→斧/ツルハシで丸太・鉱石を1個壊す→**単発の破壊自体は成功し**、アクションバーに警告が出て連鎖は発動しないこと
- 機能をOFFのまま、または素手で同じ操作をした場合は警告が出ないこと（狙った条件のみ発火することの確認）
- 対象ワールド内で`/kikori`・`/mine`を直接実行すると従来通り`world_disabled`でコマンド自体が弾かれること（既存挙動に影響が無いことの確認）
