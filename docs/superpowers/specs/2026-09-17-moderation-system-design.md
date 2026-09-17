# モデレーションシステム（警告・報告・Kick・Ban）設計

## 概要

運営がプレイヤーを警告・Kick・Banでき、プレイヤー側もルール違反者を運営に報告できるモデレーション機能を追加する。理由入力は全コマンド共通で必須とし、処罰履歴はスタッフ専用コマンドで確認できるようにする。Discordとの連携（監査ログ通知・報告のトリアージ通知）を持つ。

対象コマンド: `/warn`、`/kick`、`/ban`、`/report`、`/userhistory`（新規）。

## 全体構成

`managers/ModerationManager`がwarn/kick/banの記録・判定を一括で担当し、`managers/ReportManager`が`/report`を専任で担当する。理由入力は全コマンド共通で**必須**とし、引数なし・理由省略での実行はエラーとして拒否する。

### `/warn <player> <reason>`

`warns`テーブルに1件追加するだけ。自動エスカレーション（一定回数でKick/BANへ自動移行するような仕組み）は持たない。運営は`/userhistory`で蓄積状況を見て手動で次のアクションを判断する。警告は無期限に蓄積され、自動で失効しない。対象がオンラインであれば、警告を受けたことをアクションバー等で本人に通知する。

### `/kick <player> <reason>`

即時切断。`kicks`テーブルに記録として残す（Kick回数のカウントを兼ねる）。切断画面には理由と、「異議がある場合は下記Discordへ」という案内（`discord.invite`のURLを埋め込み）を表示する。

### `/ban <player> <duration|permanent> <reason>`

既存のミュート機能で使っている`utils/DurationParser`をそのまま流用して期間をパースする。`permanent`を指定した場合は無期限BANとする。

- 対象がオンラインであれば即座に切断する。
- `bans`テーブルへ記録する（オフラインのプレイヤーにも実行可能）。
- 以後のログインは`AsyncPlayerPreLoginEvent`でブロックし、拒否画面に理由・期限（`permanent`なら「無期限」）・Discordへの異議申し立て案内を表示する。

`BanManager`（`ModerationManager`のサブコンポーネントとして実装してよい）は、`managers/MuteManager`と同じ「起動時に全件をメモリキャッシュへロードし、参照時に遅延失効＆自己削除（キャッシュ+非同期DB削除）」というパターンに合わせる。`expires_at`が`NULL`なら永久BANとして扱う。

### `/report <player>`

1. コマンドを実行するとGUIが開き、理由カテゴリを選択する（カテゴリは`config.yml`で定義。例: 暴言/チート/荒らし/その他）。
2. カテゴリ選択後、チャットで自由記述の補足説明を入力させる（他機能（ショップの価格入力など）と同じ、プレイヤーUUIDをキーにした保留状態＋`AsyncChatEvent`での捕捉方式）。
3. 確定すると`reports`テーブルに保存し、同時にDiscordへ通知する。

権限は不要（誰でも実行可能）とする。

**ヘルプ・使い方の分かりやすさ**: `/report`を引数なし、または対象プレイヤーを省略して実行した場合は、単なる「使い方: /report <player>」ではなく、「① 対象を指定 → ② 理由カテゴリを選ぶ画面が開く → ③ 詳細をチャットで入力 → ④ 送信」という一連の流れが一目でわかる複数行のヘルプメッセージを`messages.yml`（`report.usage`のような複数行のメッセージリスト）に用意し、`ConfigManager.getMessageList`経由で表示する。存在しないプレイヤー名を指定した場合も、単なるエラーだけでなく「オンライン/直近ログインしたプレイヤー名を指定してください」といった具体的な案内を添える。

### `/userhistory <player>`（新規、スタッフ専用）

対象プレイヤーのwarn/kick/ban/被report件数と直近の履歴（時系列で数件）を一覧表示する。既存の`/seen`コマンドとは役割を分離する。

- `/seen`: 誰でも使える、最終ログイン等の軽い情報
- `/userhistory`: スタッフ向けの処罰・報告履歴。専用の権限を要求する。

## データモデル

既存の`land_*`/`container_lock_*`と同じ作法で、`StellariaCore#onEnable`にて`DatabaseManager.createTableIfNotExists`で作成する。

```
warns    : id INTEGER PRIMARY KEY AUTOINCREMENT, target_uuid TEXT, moderator_uuid TEXT, reason TEXT, created_at INTEGER
kicks    : id INTEGER PRIMARY KEY AUTOINCREMENT, target_uuid TEXT, moderator_uuid TEXT, reason TEXT, created_at INTEGER
bans     : id INTEGER PRIMARY KEY AUTOINCREMENT, target_uuid TEXT, moderator_uuid TEXT, reason TEXT, banned_at INTEGER, expires_at INTEGER NULL  -- NULL = 永久
reports  : id INTEGER PRIMARY KEY AUTOINCREMENT, reporter_uuid TEXT, target_uuid TEXT, category TEXT, reason TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, created_at INTEGER
```

`bans`のみ起動時に全件をメモリキャッシュへロードする（`MuteManager`と同じ理由：ログインという準ホットパスで毎回SQLiteに問い合わせない設計とするため）。`warns`/`kicks`/`reports`はキャッシュ不要とし、`/userhistory`実行時にDBへ直接クエリする（頻繁に叩かれるホットパスではないため）。

## Discord連携

既存の`managers/DiscordBotManager`の`sendEmbedToChatChannels`と同じ実装パターン（`config.yml`のチャンネルIDリストに対して`jda.getTextChannelById(...).sendMessageEmbeds(...)`）で、新しいメソッドを2つ追加する。

- `sendModerationLog(EmbedBuilder)` → 新設定キー`discord.bot.modlog-channel-id`（リスト）へ送信。warn/kick/banの全アクションを「誰が・誰に・どの理由で」の監査ログとして通知する。
- `sendReportLog(EmbedBuilder)` → 新設定キー`discord.bot.report-channel-id`（リスト）へ送信。`/report`の内容をトリアージ用に通知する。

処罰される側に見せるメッセージ（Kick切断画面・BAN拒否画面）では、既存の`discord.invite`（`/discord`コマンドが案内しているのと同じ招待URL）を`%discord_invite%`のようなプレースホルダーとして埋め込み、新たな設定キーを増やさない。

## 権限

`plugin.yml`の既存の親子ツリー方式（`stellaria.admin`配下に各権限を`children`として登録する形）に合わせ、`stellaria.warn`/`stellaria.kick`/`stellaria.ban`/`stellaria.userhistory`を新設する。`/report`は権限チェックを行わない。

## エラーハンドリング

- 理由未入力（`/warn`・`/kick`・`/ban`） → コマンド自体を成立させず、上記「ヘルプの分かりやすさ」と同様の複数行の使い方メッセージを表示する。
- 存在しないプレイヤー名 → 該当なしメッセージ（過去ログイン履歴がある名前かどうかで案内文を出し分けてもよい）。
- `/ban`の期間フォーマット不正 → `DurationParser`が返す既存のエラーメッセージ処理をそのまま流用する。
- Discord送信失敗（JDA未起動・チャンネルID未設定等） → `DiscordBotManager`の既存の`hasConfiguredGuild()`ガードと同じ考え方で、ログにだけ残し、warn/kick/ban/report自体の成否には影響させない。

## テスト方針

本リポジトリには自動テストが存在しない（`src/test`なし）ため、既存方針に従い`./gradlew runServer`での手動確認とする。

- `/warn`の蓄積が`/userhistory`に反映されること
- `/kick`実行時の切断メッセージ（理由・Discord案内）の表示
- `/ban`の期間指定・`permanent`指定それぞれでのログインブロック
- BAN期限切れ後、再ログインできること（遅延失効の確認）
- `/report`のGUI選択→チャット入力→送信の一連の流れと、引数不足時のヘルプ表示
- Discordのmodlogチャンネルへwarn/kick/banの各アクションが届くこと
- Discordのreportチャンネルへ`/report`の内容が届くこと

## 実装者（Codex）への指示

Codex側にもsuperpowersプラグインが入っている前提で、以下のスキルを次の順番で使うこと。

1. **`superpowers:writing-plans`** — 本設計書（このファイル）を入力として、実装計画（plan）をまず作成すること。着手前に必ずこのスキルを通すこと。
2. **`superpowers:test-driven-development`** — 計画に沿って実装する各ステップで、可能な範囲でこの進め方に従うこと（本リポジトリに`src/test`は無いが、小さく実装して都度`./gradlew build`で確認するサイクルという原則自体は守ること）。
3. **`superpowers:systematic-debugging`** — 実装中に既存挙動との食い違いや原因不明の不具合に遭遇したら、憶測で直さずこのスキルの手順で原因を特定してから修正すること。
4. **`superpowers:verification-before-completion`** — 実装完了を報告する前に必ず使うこと。「動くはず」ではなく、`./gradlew build`の成功と、可能であれば`./gradlew runServer`での実際の動作確認結果を根拠にすること。
5. **`superpowers:requesting-code-review`** — 実装が完了しビルド・動作確認が済んだ後、マージ前にコードレビューを依頼する際に使うこと。
6. **`superpowers:finishing-a-development-branch`** — レビュー完了後、ブランチをどう統合するか（マージ/PR等）を決める際に使うこと。

加えて、これらのスキルを使う際も以下を守ること。

- 実装前に本設計書に加えて、参照元として`managers/MuteManager`（起動時フルロード＋遅延失効のパターン）、`managers/DiscordBotManager`（Embed送信・チャンネルID設定のパターン）、`utils/DurationParser`（期間文字列のパース）、`commands/SeenCommand`（プレイヤー情報系コマンドの作法）を実際に読み、既存の書き方・命名・エラーハンドリングの作法に合わせること。独自のスタイルを持ち込まない。
- `CLAUDE.md`に書かれている既存の規約（`ConfigManager`経由でしかメッセージを出さない、`DatabaseManager`の静的メソッド経由でしかDBを触らない、`managers/`と`listeners/`の役割分担、`utils/`は状態を持たない等）を厳守すること。
- 本設計書に無い機能・リファクタ・最適化を勝手に追加しないこと（YAGNI）。設計と矛盾する挙動や未決事項を見つけた場合は、独断で仕様を決めずに一旦立ち止まって報告すること。
