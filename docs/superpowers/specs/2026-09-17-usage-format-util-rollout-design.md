# UsageFormatUtilの全コマンドへの展開 設計

- 日付: 2026-09-17
- ステータス: 承認待ち

## 背景・位置づけ

`utils/UsageFormatUtil`は`<>`・`|`だけをグレーに塗り分け、`|`の前後にスペースを入れて使い方メッセージを見やすくするユーティリティだが、現状`LandCommand`でしか使われていない。他18ファイル・21箇所のusageメッセージ送信は`ConfigManager#getMessage`を直接呼んでおり、`<プレイヤー>`や`a|b`のような記号がそのまま本文色で詰まって表示される。

## スコープ

**対象（`getMessage(".*usage.*", ...)`呼び出し、21箇所・18ファイル）:**

- `BroadcastCommand`（`broadcast.usage`）
- `ChunkBorderCommand`（`chunkborder.usage` ×2）
- `EcoCommand`（`eco.usage`）
- `FeaturesCommand`（`features.usage`）
- `HomeCommand`（`home.usage_sethome`/`usage_home`/`usage_delhome`）
- `KikoriCommand`（`kikori.usage`）
- `MessageCommand`（`msg.usage` ×2）
- `MineCommand`（`mine.usage`）
- `MuteCommand`（`mute.usage` ×2）
- `PayCommand`（`pay.usage`）
- `ProfileCommand`（`profile.usage`）
- `RankingCommand`（`ranking.usage`）
- `ScoreboardCommand`（`scoreboard.usage`）
- `SettingsCommand`（`settings.usage`）
- `SudoCommand`（`sudo.usage`）
- `WarpCommand`（`warp.usage_setwarp`/`usage_warp`/`usage_delwarp`）

**含める（既存実装の統一）:** `LandCommand`も下記の共通ヘルパーに寄せる（現状は自前の`private sendUsage`ヘルパーを持っている）。

**対象外:** `<>`や`|`を含まない単純な一言usage（例: 引数無しの案内文）も同じ経路に統一する。見た目上の変化は無いが、呼び出し側を機械的に1パターンへ揃える方が一貫性が高く、今後`<>`付きのusageに変わっても取りこぼさない。

## 実装方針

`LandCommand`の現行パターン（`getRawMessage` → `UsageFormatUtil.format` → `FormatUtil.text`の3手順）を、呼び出し側の重複を減らすため`ConfigManager`に集約する。

### `ConfigManager`への追加

```java
/** usageメッセージ専用の取得ヘルパー。getRawMessageで生文字列を取り、UsageFormatUtilで<>/|を色分けしてからFormatUtil.textで仕上げる。 */
public String getUsageMessage(String path, @Nullable OfflinePlayer player) {
    String raw = getRawMessage(path);
    return FormatUtil.text(player, UsageFormatUtil.format(raw));
}
```

`getMessage`の隣に置く（`messages.yml`を読む系の他のgetterと同じ並び）。`ignoreWarn`版は不要（usageメッセージは常に存在する前提のキーのため、既存の`getMessage`同様に警告ログの対象でよい）。

### 呼び出し側の変更

各コマンドで
```java
sender.sendMessage(plugin.getConfigManager().getMessage("xxx.usage", player));
```
を
```java
sender.sendMessage(plugin.getConfigManager().getUsageMessage("xxx.usage", player));
```
に機械的に置換する。`sender`が`Player`でなく`CommandSender`のみの箇所（`BroadcastCommand`/`EcoCommand`/`MuteCommand`/`RankingCommand`等、`player`引数に`null`を渡している箇所）はそのまま`null`を渡せばよい（`FormatUtil.text`は`null`許容、`LandCommand`の既存呼び出しも同様）。

### `LandCommand`側の変更

既存の`private sendUsage(Player player, String messageKey)`ヘルパー（537〜543行付近）を削除し、呼び出し箇所を`plugin.getConfigManager().getUsageMessage(messageKey, player)`に置き換える。

## エラーハンドリング

既存の`getMessage`と同じ扱い（キーが無ければ`ConfigManager`の一回限り警告ログ、メッセージ自体は`messages.yml`のプレースホルダ文言）。`getUsageMessage`独自のエラー処理は追加しない。

## テスト方針

本リポジトリに自動テストは無く、`runServer`での実機確認を行う:

- `./gradlew build`が通ること
- 変更した21箇所のうち、`<>`や`|`を含むusage（例: `/land rule <pvp|explosions|...> <on|off|default>`、`/sudo <player> <command>`、`/mute <player> <duration> [reason]`等）を実際にコマンド不足引数で叩き、記号がグレーで区切られスペースが入っていること
- `<>`/`|`を含まない単純なusage（例: `/features`）も従来通り表示されること（色崩れが無いこと）
- `/land`のusageも従来と同じ見た目のまま出ること（ヘルパー統合による差分が無いことの確認）
