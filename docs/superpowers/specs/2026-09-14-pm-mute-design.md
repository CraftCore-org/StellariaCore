# PM機能 + レベル別ミュート機能 設計仕様

## 背景・ロードマップ上の位置づけ

`docs/superpowers/specs/2026-09-14-afk-heal-broadcast-design.md` の冒頭で決めた5分割ロードマップの
サブプロジェクト②にあたる。UtilsPluginの `PrivateMessageCommand` / `MessageManager` をベースにした
プレイヤー間メッセージ機能（PM）と、StellariaCore独自の新機能であるレベル別ミュート機能をあわせて実装する。

UtilsPluginの `MuteChatCommand` は「サーバー全体のチャットを一時停止するトグル」であり、今回作る
「ユーザーごと・期限つき・3段階」のミュートとは性質が異なるため、これは移植ではなく新規設計とする。
`ReplyCommand` / `SocialSpyCommand` のうち、SocialSpyは対象外のまま。Replyは当初の除外方針から一転し、
今回のスコープに含める（後述）。

## スコープ

**含む:**
- `/msg`（alias: `tell`, `w`, `message`) — プレイヤー間PM
- `/reply`（alias: `r`) — 直前にやり取りした相手への返信
- `/mute` / `/unmute` — レベル別・期限付きミュート（DB永続化）
- チャット送信者名・メンション・PM本文のクリックでメッセージ送信欄オートフィル

**含まない:**
- `/socialspy`（当初の除外方針を維持）
- ミュート理由の編集・履歴閲覧コマンド（`/mutelist` 等） — 将来必要になったら別途検討
- オフラインプレイヤーへのPM送信（送信先はオンライン必須。ミュート自体はオフライン対象に実行可能）

## アーキテクチャ概要

新規クラス:
- `managers/MuteManager` — ミュート状態の管理。DB永続化＋メモリキャッシュ。
- `managers/PrivateMessageManager` — PM送受信ロジック（フォーマット・音・`/reply`用の直前相手記録）。
- `commands/MuteCommand` — `/mute` と `/unmute` を1つの `CommandExecutor` で捌く（`TpaCore` と同じパターン）。
- `commands/MessageCommand` — `/msg` と `/reply` を1つの `CommandExecutor` で捌く（同上）。
- `listeners/MuteCommandBlockListener` — `PlayerCommandPreprocessEvent` でLv3のコマンドブラックリストを弾く。
- `utils/DurationParser` — `10m`/`1h`/`3d`/`perm` のような相対時間文字列のパース・残り時間の表示整形。

既存クラスの改修:
- `listeners/ChatListener` — 送信前に `MuteManager.isRestricted(sender, MuteScope.CHAT)` をチェックしてブロック。
  送信者名にクリックイベント（`/msg <name> ` オートフィル）を追加。
- `managers/MentionService` — 個別メンション（`@name`）にクリックイベントを追加（`@all` は対象外）。
- `StellariaCore` — 新規マネージャー・コマンド・リスナーの登録配線を追加。

## DBスキーマ（新テーブル `mutes`）

```sql
CREATE TABLE IF NOT EXISTS mutes (
    uuid TEXT PRIMARY KEY,
    level INTEGER,
    expires_at INTEGER,  -- epoch millis。永久ミュートは -1
    reason TEXT,
    muted_by TEXT,        -- 実行者の名前（表示用、その時点のスナップショット）
    muted_at INTEGER      -- epoch millis
)
```

`StellariaCore#onEnable` で `DatabaseManager.createTableIfNotExists("mutes", ...)` を呼ぶ（`players` テーブルと同じ並び）。

`MuteManager` は `onEnable` 時に `SELECT * FROM mutes` で全件ロードし `Map<UUID, MuteRecord>` にキャッシュする。
`isRestricted()` 呼び出し時に期限切れを検知したら、その場でキャッシュから除去しDBからも非同期削除する
（自動解除。通知はしないサイレント解除）。`mute()`/`unmute()` はキャッシュを即時更新しつつDB書き込みは
`DatabaseManager.*Async` 系で非同期に行う（既存 `EconomyManager` と同じ方針）。

`MuteRecord`（レコード型）: `level`, `expiresAt`, `reason`, `mutedBy`, `mutedAt`。

## レベル定義とスコープ

| Lv | 制限内容 |
|---|---|
| 1（デフォルト） | 公開チャット送信禁止 |
| 2 | Lv1 + PM送信禁止（**受信は常に可能**、`/msg`/`/reply`とも対象） |
| 3 | Lv2 + `mute.level3-command-blacklist`（config.yml）記載のコマンド実行禁止 |

```java
public enum MuteScope {
    CHAT(1), PM(2), COMMAND(3);
    private final int requiredLevel;
    // ...
}
```

`MuteManager.isRestricted(UUID uuid, MuteScope scope)`:
```java
MuteRecord record = cache.get(uuid);
if (record == null) return false;
if (isExpired(record)) { unmuteInternal(uuid); return false; }
return record.level() >= scope.requiredLevel();
```

## コマンド仕様

### `/mute <player> <level:1-3> <duration:10m|1h|3d|perm> <reason...>`

- 権限: `stellaria.mute`（mute/unmute共通、unmute専用の権限は用意しない）
- 引数不足・形式不正はそれぞれ専用メッセージ（`mute.usage`/`mute.invalid_level`/`mute.invalid_duration`）
- 対象は `Bukkit.getOfflinePlayer(args[0])` で解決（一度もサーバーに来たことのない名前は誤爆しうるが、
  既存コードの `EconomyManager`/`AFK` 等でも同様の前提のため踏襲する）
- 既にミュート中の相手に実行した場合は上書き（`INSERT OR REPLACE` 相当、`MuteManager.mute()` 内で吸収）
- `/mute help`（第1引数が `help`）で `messages.yml` の `mute.help`（3段階の説明リスト）を表示して終了
- 成功時の通知:
  - 対象者本人（オンラインなら即時、`mute.muted_target`。理由・レベル・期限を含む）
  - 実行者（`mute.muted_sender`）
  - `stellaria.mute.notify` 権限保持者全員（`mute.muted_staff`、実行者・対象者との重複送信は除く）

### `/unmute <player>`

- 権限: `stellaria.mute`
- 対象がミュートされていなければ `mute.not_muted`
- 成功時、対象本人（オンラインなら）・実行者・`stellaria.mute.notify` 保持者に通知
  （`mute.unmuted_target`/`mute.unmuted_sender`/`mute.unmuted_staff`）

### `/msg <player> <message...>`（alias: `tell`, `w`, `message`）

- 権限: `stellaria.msg`（**デフォルト全員許可** — `plugin.yml` に `default: true` で明示登録する。
  他の権限ノードは既存方針どおり未登録＝OPのみデフォルト許可のまま）
- must-be-player / usage / 自分宛て禁止 / 相手オフライン は通常のバリデーション
- 送信前に `MuteManager.isRestricted(sender, MuteScope.PM)` をチェック。ミュート中なら `mute.blocked_pm`
  を送って中断（相手には何も送らない）
- カラーコードは既存 `message.color-codes.permission`（デフォルト `stellaria.chat.color` を流用）で
  送信者のみ許可判定
- 送信成功時、`PrivateMessageManager` が両者の `lastMessaged` を更新する（`/reply` 用）

### `/reply <message...>`（alias: `r`）

- 権限: `/msg` と共通の `stellaria.msg`（専用権限は用意しない）
- `PrivateMessageManager.getLastMessaged(sender)` が `null` なら `msg.reply_no_target`
- 直前の相手が現在オフラインなら `msg.player_not_found`
- それ以外のバリデーション・ミュートチェック・送信ロジックは `/msg` と共通（`PrivateMessageManager.send()` を再利用）

## PMメッセージのフォーマットとクリックオートフィル

`messages.yml` の `msg.format_sender` / `msg.format_receiver` は `%target%`/`%sender%`/`%message%` の
3スロットを持つテンプレート文字列。`BroadcastCommand.splice()` と同じ方式で、テンプレート文字列を検索して
各スロットの位置に対応するComponentを差し込む（`ConfigManager.getRawMessage()` を使用、生文字列のまま
プレースホルダー位置を自前で解決する）。

`chat.click-to-message`（config.yml、デフォルト `true`）が有効なら、完成したメッセージComponent全体に
以下を付与する:
- `ClickEvent.suggestCommand("/msg <相手名> ")`（送信側は相手名、受信側は送信者名でチャット欄をオートフィル。
  実際に送信はされない）
- `HoverEvent.showText(...)`（`msg.click_hint`: 「クリックで返信」）

## チャット送信者名・メンションのクリックオートフィル

`chat.click-to-message` が有効な場合のみ:

- **`ChatListener`**: `sourceDisplayName` に `ClickEvent.suggestCommand("/msg <発言者名> ")` を追加。
  既存の `chat.tooltip.lines`（ping/所持金など）によるホバーは維持しつつ、末尾に
  `chat.click_hint`（「クリックでメッセージを送信」）を1行追加する。
  `chat.tooltip.enabled` が `false` でも、`chat.click-to-message` が `true` なら
  クリックヒント1行だけのホバーを表示する（クリック可能なのに手がかりが無い状態を避ける）。
- **`MentionService`**: `@name` 形式の個別メンション（`@all` は対象外）に
  `ClickEvent.suggestCommand("/msg <メンション相手名> ")` と
  `HoverEvent.showText(mention.click_hint の %player% を相手名に置換したもの)` を追加。

いずれも自分自身の発言・メンションに対してクリックしても実害はない（`/msg <自分の名前>` は
`msg.self` エラーになるだけ）ため、送信者本人を特別扱いする分岐は設けない。

## コマンドブラックリスト（Lv3）

`listeners/MuteCommandBlockListener`:
```java
@EventHandler
public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
    Player player = event.getPlayer();
    if (!muteManager.isRestricted(player.getUniqueId(), MuteScope.COMMAND)) return;

    String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
    List<String> blacklist = configManager.getStringList("mute.level3-command-blacklist");
    if (blacklist.stream().anyMatch(label::equalsIgnoreCase)) {
        event.setCancelled(true);
        player.sendMessage(configManager.getMessage("mute.blocked_command", player)
            .replace("%remaining%", ...).replace("%reason%", ...));
    }
}
```

マッチングはプレイヤーが実際に入力したコマンドラベル（`/tell ...` なら `tell`）で行うため、
エイリアスも含めてブラックリストに列挙する必要がある。デフォルト値は以下（config.yml参照）。

## config.yml 追加キー

```yaml
mute:
  level3-command-blacklist:
    - "msg"
    - "tell"
    - "w"
    - "message"
    - "reply"
    - "r"
    - "mute"
    - "unmute"

message:
  color-codes:
    enabled: true
    permission: "stellaria.chat.color"
  sound:
    enabled: true
    name: "ENTITY_EXPERIENCE_ORB_PICKUP"
    volume: 1.0
    pitch: 1.0
```

既存 `chat:` セクションに1キー追記:
```yaml
chat:
  click-to-message: true # 追加
```

## messages.yml 追加キー

```yaml
mute:
  usage: "&%c使用方法: /mute <プレイヤー> <レベル1-3> <期間 10m|1h|3d|perm> <理由>"
  help:
    - "&%e&l| &%7ミュートのレベル一覧:"
    - "&%7Lv1: &%f公開チャット送信禁止"
    - "&%7Lv2: &%fLv1 + PM送信禁止"
    - "&%7Lv3: &%fLv2 + 一部コマンド実行禁止"
  invalid_level: "&%cレベルは1〜3で指定してください。"
  invalid_duration: "&%c期間の指定が正しくありません。例: 10m, 1h, 3d, perm"
  player_not_found: "&%c%player% &%7はオフラインまたは存在しません。"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  muted_target: "&%cあなたはミュートされました。（レベル%level%、理由: %reason%、期限: %expires%）"
  muted_sender: "&%a%player% &%7をミュートしました。（レベル%level%、期限: %expires%）"
  muted_staff: "&%7[ミュート] &%f%moderator% &%7が &%f%player% &%7をミュートしました。（レベル%level%、理由: %reason%）"
  not_muted: "&%c%player% &%7はミュートされていません。"
  unmuted_target: "&%aミュートが解除されました。"
  unmuted_sender: "&%a%player% &%7のミュートを解除しました。"
  unmuted_staff: "&%7[ミュート解除] &%f%moderator% &%7が &%f%player% &%7のミュートを解除しました。"
  blocked_chat: "&%cミュート中のため発言できません。（残り: %remaining%、理由: %reason%）"
  blocked_pm: "&%cミュート中のためメッセージを送信できません。（残り: %remaining%、理由: %reason%）"
  blocked_command: "&%cミュート中のためこのコマンドは使用できません。（残り: %remaining%、理由: %reason%）"

msg:
  usage: "&%c使用方法: /msg <プレイヤー> <メッセージ>"
  no_permission: "&%cこのコマンドを実行する権限がありません。"
  must_be_player: "&%cこのコマンドはプレイヤーのみ実行できます。"
  self: "&%c自分自身にメッセージを送ることはできません。"
  player_not_found: "&%c%player% &%7はオンラインではありません。"
  reply_no_target: "&%c返信できる相手がいません。"
  format_sender: "&%d[あなた -> %target%] &f%message%"
  format_receiver: "&%d[%sender% -> あなた] &f%message%"
  click_hint: "&%7クリックで返信"

chat:
  click_hint: "&%7クリックでメッセージを送信"

mention:
  click_hint: "&%7クリックで%player%にメッセージを送信"
```

`%level%`/`%reason%`/`%expires%`/`%remaining%`/`%moderator%`/`%player%` は各コマンド・リスナーが
手動で `.replace()` するプレースホルダー（`PlaceholderManager` の組み込みトークンとは別系統。
`tpa.tpa_warmup` の `%seconds%` と同じ扱い）。

## 期間パース・残り時間表示（`utils/DurationParser`）

```java
public final class DurationParser {
    private static final Pattern PATTERN = Pattern.compile("^(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);

    // "10m" -> 600, "1h" -> 3600, "perm"/"permanent" -> -1
    // 形式不正なら IllegalArgumentException
    public static long parseSeconds(String input);

    // millis指定の残り時間を "3日" "5時間" "12分" "45秒" のように最大単位1つで整形
    // expiresAtMillis が -1（永久）なら "永久"
    public static String formatRemaining(long expiresAtMillis);
}
```

`formatRemaining` は最大の単位1つだけを表示する簡易フォーマット（例: 25時間30分でも「1日1時間」ではなく
「25時間」）。複数単位の合成表示はYAGNIとして見送る。

## エラーハンドリング一覧

| 状況 | メッセージキー |
|---|---|
| `/mute` `/unmute` 権限なし | `mute.no_permission` |
| `/mute` `/unmute` コンソール実行 | `mute.must_be_player` |
| `/mute` 引数不足 | `mute.usage` |
| レベル指定が1-3以外 | `mute.invalid_level` |
| 期間指定の形式不正 | `mute.invalid_duration` |
| 対象プレイヤーが見つからない | `mute.player_not_found` |
| `/unmute` 対象が未ミュート | `mute.not_muted` |
| ミュート中に公開チャット送信 | `mute.blocked_chat`（イベントキャンセル） |
| ミュート中にPM送信（`/msg`/`/reply`共通） | `mute.blocked_pm` |
| ミュート中にLv3対象コマンド実行 | `mute.blocked_command`（イベントキャンセル） |
| `/msg` `/reply` 権限なし | `msg.no_permission` |
| `/msg` `/reply` コンソール実行 | `msg.must_be_player` |
| `/msg` 引数不足 | `msg.usage` |
| `/msg` 自分宛て | `msg.self` |
| `/msg` 相手がオフライン | `msg.player_not_found` |
| `/reply` 直前の相手なし | `msg.reply_no_target` |
| `/reply` 直前の相手がオフライン | `msg.player_not_found` |

## 権限一覧

| 権限ノード | 用途 | デフォルト |
|---|---|---|
| `stellaria.mute` | `/mute` `/unmute` | 未登録（OPのみ、既存方針踏襲） |
| `stellaria.mute.notify` | ミュート実行/解除のスタッフ通知を受け取る | 未登録（OPのみ） |
| `stellaria.msg` | `/msg` `/reply` | **`plugin.yml`に`default: true`で登録**（全員許可） |
| `stellaria.chat.color`（既存） | PM本文での`&`カラーコード使用 | 既存のまま |

`plugin.yml` に `permissions:` ブロックを新設し `stellaria.msg` のみ明示登録する
（他のノードは既存コマンドと同様に未登録のままでOP限定がデフォルトになる）。

## テスト方針

このリポジトリに自動テストは存在しない（CLAUDE.md記載どおり）ため、`./gradlew build` の成功に加えて
`./gradlew runServer` での手動確認を行う。確認項目:

1. `/mute <player> 1 10m テスト` → 対象がPvP以外で発言できなくなる、10分後に自動解除される
2. `/mute <player> 2 1h テスト` → 対象が`/msg`送信不可（受信は可能）、公開チャットも不可
3. `/mute <player> 3 perm テスト` → 対象が`mute`/`msg`/`tell`等のコマンド実行不可（例外は許可コマンド）
4. `/unmute <player>` → 即座に解除、以後発言・PM・コマンドが可能に戻る
5. `/mute help` → レベル一覧が表示される
6. `/msg`, `/tell`, `/w`, `/message` の4エイリアスすべてが動作する
7. `/reply`（`/r`）が直前の送信相手・受信相手どちらに対しても正しく機能する
8. PM本文・チャット送信者名・メンションをクリックしてチャット欄に`/msg <name> `が自動入力される
   （送信はされないことを確認）
9. サーバー再起動後もミュートが有効期限まで保持されている（DB永続化の確認）
