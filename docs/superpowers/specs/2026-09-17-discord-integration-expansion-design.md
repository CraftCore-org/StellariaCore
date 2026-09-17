# Discord連携拡張（プレイヤー身元表示・管理者スラッシュコマンド・付随機能） 設計

- 日付: 2026-09-17
- ステータス: 承認待ち

## 背景・位置づけ

現状の`managers/DiscordBotManager`（JDA、`net.dv8tion:JDA:6.6.0`）は「MC↔Discordのチャット中継」と「起動/停止・参加/退出のログ埋め込み送信」だけを行っている。中継はBotが単一アカウントとして`%player%: %message%`形式のプレーンテキストで送るだけで、プレイヤーごとの見た目の区別が無い。本設計では、

1. **チャット中継をプレイヤーごとにDiscordの「別ユーザー」のように見せる**（Webhookでの名前・アイコンなりすまし、ランク表示込み）
2. **Discord側のスラッシュコマンドで、Discord連携関連の設定を変更できるようにする**（`config.yml`への書き戻し込み）
3. サーバー状態のBotステータス表示
4. Discordスラッシュコマンドでのオンライン一覧・プレイヤー情報照会
5. 参加/退出ログの一般チャンネル配信の状態確認

を扱う。**スコープ外**: Discordのスラッシュコマンドから変更できる設定は`discord.*`配下のみ（他機能の設定は対象外）。sudo実行時のDiscord通知は別途（今回のスコープ外、ユーザーの意向により保留中）。

## 前提として直しておくバグ

`DiscordBotManager#startBot()`/`#stop()`/各ログ送信メソッドが`discord.bot.admin-guild.id`（ネスト形式）を読んでいるが、`config.yml`に実際にあるキーは`discord.bot.admin-guild-id`（フラット形式）。パスが一致しておらず、`admin-guild-id`を設定しても常に空文字列として扱われるバグ。今回admin-guildを実際に使う機能（管理者スラッシュコマンド）を追加するため、このタイミングでコード側のパスを`"discord.bot.admin-guild-id"`に修正する。

## ① プレイヤー身元表示（Webhook化）

### 方式

Discord APIでは、Botアカウントが他人の名前・アイコンを名乗ってメッセージを送ることはできない。これができるのは**Webhook**経由の送信のみ。したがって`mcChatToDiscord`をWebhook送信に置き換える。

### Webhookの自動管理（新規設定キー不要）

運営がWebhook URLを手動発行して`config.yml`に貼る運用は避ける。`startBot()`のJDA準備完了後、`discord.bot.serverchat-channel-id`に列挙された各チャンネルに対して：

1. `channel.retrieveWebhooks().complete()`で既存Webhookを取得し、名前が固定値`"StellariaCore"`のものが無いか探す
2. 無ければ`channel.createWebhook("StellariaCore").complete()`で作成する（Botに`MANAGE_WEBHOOKS`権限が必要——`plugin.yml`の要求権限ではなくDiscord側のBot招待権限の話なので、READMEか起動時ログで案内する）
3. 見つかった/作成した`Webhook`の`getUrl()`を`Map<String channelId, String webhookUrl>`にキャッシュする（`DiscordBotManager`のインスタンスフィールド、`stop()`でクリア）

これにより、**受信（Discord→MC）は従来通りBotが同じチャンネルをlistenし続け**、**送信（MC→Discord）だけWebhook経由**という形で、同じチャンネルに両方が共存する。`serverchat-channel-id`の意味は変わらない。

### 送信方法（新規ライブラリ追加なし）

JDAは組み込みのWebhookクライアントを持たないため、追加の依存を増やさずJava標準の`java.net.http.HttpClient`でWebhook URLに直接JSON POSTする（`{"username": ..., "avatar_url": ..., "content": ...}`）。同期的な`complete()`呼び出しと同様、チャットイベント自体は非同期でよいので`HttpClient.newHttpClient().sendAsync(...)`で投げっぱなしにする（失敗時はログにwarning、メッセージ自体はMC側には影響させない）。

### 表示名フォーマット

新規設定キー`discord.bot.player-name-format`（デフォルト`"[%rank_tag%] %player%"`）。送信直前に

- `%player%` → プレイヤー名
- `%rank_tag%` → `RankManager`から取得した`RankInfo.tablistTag()`（色コードは含まれない前提のタグ文字列。念のため`PlainTextComponentSerializer`等で色成分が万一混入していても素通しできるよう、`ColorUtil`で一旦Componentにしてから`PlainTextComponentSerializer.plainText().serialize(...)`でプレーン化してから埋め込む）。ランク無し（LuckPerms未導入/未設定）の場合は`%rank_tag%`を空文字に置換し、結果に生じる余分な空白・角括弧は素直に残す（例: `"[] Player"`になる点は許容——`rank.groups`が空のサーバーではそもそも`%rank_tag%`を使わないフォーマットに運営側で変更すればよい）

### アバター

新規ライブラリ追加なしで`https://crafatar.com/avatars/<uuid>?overlay`を`avatar_url`にそのまま渡す（Discord側がURLを取得・キャッシュしてくれるため、サーバー側でのダウンロードは不要）。

### `DiscordListener`側の対応漏れ防止

Webhookで送ったメッセージがBotの`onMessageReceived`で再び拾われ、MCチャットに折り返されてしまう無限ループを防ぐガードが必要。現状の`if (event.getAuthor().isBot()) return;`だけでは**Webhookメッセージを確実に弾けるとは限らない**（Discord APIの仕様上、Webhook経由のメッセージは`author`が通常のBotとは違う扱いになるケースがある）ため、`event.getMessage().isWebhookMessage()`のチェックを明示的に追加する:

```java
if (event.getAuthor().isBot() || event.getMessage().isWebhookMessage()) { return; }
```

## ② Discord管理者スラッシュコマンド（`discord.*`設定の閲覧・変更）

### コマンド

- `/discordconfig get <key>` — 現在値を返す（ephemeral）
- `/discordconfig set <key> <value>` — 値を変更し、即時反映＋`config.yml`へ永続化

`<key>`は`discord.`で始まる`config.yml`上のドット区切りパス（例: `discord.bot.player-name-format`、`discord.invite`）。`discord.`で始まらないキー、または`config.yml`に現状存在しないキーは拒否する（新規キーの誤作成を防ぐ——既存キーの値変更のみ許可）。

### 権限・登録範囲

- コマンドは**`discord.bot.admin-guild-id`のギルドにのみ**ギルドスコープで登録する（`guild.upsertCommand(...)`、グローバルコマンドにはしない）。`admin-guild-id`が未設定なら登録自体をスキップし、起動ログに警告を出す
- 実行時、追加で`discord.bot.admin-roles`（新規設定キー、Discordロール ID の配列、複数指定可）のいずれかのロールを実行者が持っているか確認する。持っていなければ`権限がありません`のephemeral応答を返し、それ以上何もしない
- 上記2重チェック（ギルド限定登録＋ロールチェック）により、一般サーバーの利用者は存在にすら気付かない

### 値の型変換

`YamlConfiguration#get(path)`で取得した現在値の実行時型（`Boolean`/`Integer`/`Double`/`List<?>`/`String`）を見て、Discordから渡された文字列をその型に変換してから書き込む。

- `Boolean` → `"on"/"true"/"1"`→true、`"off"/"false"/"0"`→false、それ以外は不正値としてエラー応答
- `Integer`/`Double` → `Integer.parseInt`/`Double.parseDouble`、失敗したらエラー応答
- `List<?>` → カンマ区切りで分割（例: `set discord.bot.serverchat-channel-id 111,222`）
- `String` → そのまま

### 永続化（`config.yml`を直接書き換える。コメントを壊さない）

`ConfigFile#save()`（`YamlConfiguration#save(file)`）はファイル全体をBukkitのYAMLダンパーで再シリアライズするため、**既存の全コメントが消える**。`config.yml`には多数の日本語コメント（`!NOTE: 未実装`等）があるため、これを使うと確実に事故る。

代わりに、新規の静的ユーティリティ`utils/YamlScalarPatcher`を作り、**該当キーの行だけ**を書き換える。

- 対象は`discord:`ルート配下（1〜2階層のネスト、例: `discord.invite`、`discord.bot.player-name-format`）というスコープの狭さを前提にした、シンプルな行ベースの実装で十分（汎用YAML編集ライブラリは不要）
- アルゴリズム概要: `config.yml`を行配列として読み込み → パスを`.`で分解 → 各セグメントについて、現在のインデント幅を基準に「`<セグメント名>:`で始まる行」を、直前に確定したセグメントのインデント範囲内（そのセグメントのブロックが終わる＝インデントが戻るまで）で探しながら1段ずつ深く辿る → 最終セグメントの行が見つかったら、その行の`key:`以降の値部分だけを新しい値に置き換える（行末に`# コメント`が付いている場合はそのコメントを保持する）。他の行は一切変更しない
- 値のYAML表現への変換: `String`はダブルクォートで囲む（既存の`config.yml`の書式に合わせる）、`Boolean`/`Integer`/`Double`はそのまま、`List<String>`は`["a", "b"]`のフロー形式（`serverchat-channel-id: [""]`のような既存の書き方と揃える）
- 該当キーが見つからない場合（本来ここには来ないはずだが、事前の`contains`チェックをすり抜けた場合の保険）は何もせず`false`を返す。呼び出し側はこれをログに警告として残す
- 書き換え後、`configuration.set(path, coercedValue)`でメモリ上の`YamlConfiguration`にも同じ値を反映してから`YamlScalarPatcher`でファイルへ書く（メモリと ファイルの二重更新。メモリ側の更新だけなら`ConfigManager`の全getterが即座に新しい値を返すようになる——コンストラクタでconfig値をキャッシュしているマネージャーは無いため、`/stellariareload`のような再読込トリガーは不要）

### エラーハンドリング

- `admin-guild-id`以外のギルドからのコマンド実行 → Discord側がそもそもコマンド一覧に出さないので通常発生しない。念のためハンドラ内でも`event.getGuild().getId().equals(adminGuildId)`を確認する
- ロール不足 → ephemeralで`discord.bot.admin.no_permission`的な文言（Discord埋め込みメッセージなので`messages.yml`ではなく`config.yml`の`discord.bot.admin.*`配下に文言を持たせる。Discord向けメッセージは`messages.yml`のMC向けフォーマットパイプラインを通さないため）
- 存在しないキー・prefix不一致 → ephemeralでエラー内容を返す
- 型変換失敗 → ephemeralでエラー内容（期待する型）を返す

## ③ サーバー状態表示

`jda.getPresence().setActivity(Activity.playing(text))`で、Botのステータス文言をオンライン人数で更新する。新規設定キー`discord.bot.status-format`（デフォルト`"%online%/%max_online% online"`）。既存の`PlaceholderManager`はプレイヤー文脈が前提の実装なので流用せず、`DiscordBotManager`内で`%online%`→`Bukkit.getOnlinePlayers().size()`、`%max_online%`→`Bukkit.getServer().getMaxPlayers()`の単純置換のみ行う。`Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)`で1分毎に更新（他のHUDマネージャーと同じパターン）。JDAが未起動（`discord.bot.enabled: false`やtoken未設定）の場合はタスク自体を開始しない。

## ④ Discordスラッシュコマンドでのプレイヤー情報照会

- `/players` — `Bukkit.getOnlinePlayers()`の名前一覧を埋め込みで返す（人数0なら「誰もいません」）
- `/whois <player>` — 対象プレイヤーの表示にあたり、既存の`PlaytimeManager`・`RankManager`・`players`/`player_stats`テーブル（`DatabaseManager`経由、`SeenCommand`/`ProfileCommand`/`PlaytimeCommand`が既に同じデータを読んでいるのでそれらのマネージャーメソッドを再利用し、DBを直接クエリし直さない）から、ランク・最終ログイン・プレイ時間・所持金を埋め込みで返す。存在しないプレイヤー名なら「見つかりません」

これらは`admin-guild-id`ではなく`discord.bot.server-guild-id`（一般利用者がいる方のギルド）にコマンド登録する（管理者専用の`/discordconfig`とは登録先を分ける）。権限チェックは無し（誰でも実行可能な情報照会コマンドのため）。

## ⑤ 参加/退出ログの一般チャンネル配信について（確認事項・実装不要の可能性）

現状のコード（`DiscordBotManager#sendPlayerJoinLog`/`#sendPlayerQuitLog`、`PlayerJoinListener`/`PlayerQuitListener`から呼び出し済み）は、**既に**`discord.bot.serverchat-channel-id`（チャット中継と同じ、一般利用者が見るチャンネル）へ参加/退出の埋め込みを送信している。要望と現状実装が一致しているように見えるため、**新規のコード変更は不要**と判断する。

実機で届いていない場合は、以下のいずれかの運用上の問題が疑われる。コード修正ではなく確認から始めること:

- `discord.bot.token`が空、またはBotの起動自体に失敗している（`startBot()`のcatchで握りつぶされ、コンソールに「DiscordBotの起動に失敗しました。」とだけ出て詳細が見えない——ここも合わせて`e.getMessage()`をログに含めるよう直しておくと調査しやすい）
- `discord.bot.server-guild-id`と`discord.bot.admin-guild-id`の両方が空（`sendPlayerJoinLog`等は両方空なら即returnする実装のため）
- `discord.bot.serverchat-channel-id`が空リストのまま

## `config.yml` 追加分

```yaml
discord:
  bot:
    admin-roles: [""]              # /discordconfig を実行できるDiscordロールID（複数可）
    player-name-format: "[%rank_tag%] %player%"
    status-format: "%online%/%max_online% online"
    admin:
      no-permission: "権限がありません。"
```

## テスト方針

本リポジトリに自動テストは無く、`runServer`＋実際のDiscordサーバーでの実機確認を行う:

- `./gradlew build`が通ること
- **Webhook中継**: MCでチャット→Discordにプレイヤー名・アイコン付きで（Botとは別の「ユーザー」のように）投稿されること。ランク付き/無しの両方で表示を確認
- **ループ防止**: Webhook経由の投稿がMCチャットへ折り返されないこと(`isWebhookMessage()`ガードの確認)
- **`/discordconfig`**: admin-guild以外・admin-roles無しのユーザーからは実行できない（またはコマンド自体が見えない）こと。admin-roles持ちのユーザーが`set discord.bot.player-name-format`等を変更→即座に反映されること→`config.yml`を開いて該当行以外のコメント・他設定が壊れていないこと
- **サーバー状態表示**: Botのステータス（プレイ中表示）がオンライン人数に応じて更新されること
- **`/players`・`/whois`**: server-guildから実行できること、admin-guildからは見えない(または見えてもよいかは要検討)こと
- **参加/退出ログ**: 上記確認事項リストに沿って、実際に一般チャンネルへ届くかを確認する
