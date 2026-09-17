# ワールド自動リセット機能 設計仕様

- 初稿日: 2026-09-16 / 確定日: 2026-09-17
- ステータス: **設計確定**。次工程は writing-plans スキルによる実装プラン作成。
- 対象: 「ワールド自動リセット（毎週or2週間or1ヶ月に一回、一部のワールドを再生成できる機能。資源ワールド等向け。時間は金曜日12:00、変更できるように）」

## 概要

資源ワールド等、指定したワールドを一定周期で完全に削除・再生成する機能。プレイヤーの安全な退避、事前告知、再入場防止、home/warp の後始末までを一貫して扱う。

## リセット方式

- 対象ワールドの**ワールドフォルダを削除してワールドを再生成**する（バックアップへのリネームではなく完全削除→再生成）。理由: シンプルさ優先。
- 再生成時は**毎回ランダムな新規シード**を使う（固定シードにしない）。資源ワールドとして「毎回新鮮な地形・鉱脈」にする狙い。

## 対象ワールドの指定

- `config.yml` にリセット対象ワールド名のリストを直接書く。ワールドごとの個別スケジュールは持たせず、対象ワールド全体で共通のスケジュール1本を使う。

## スケジュール方式

`schedule.type` で `weekly` と `monthly` を排他的に切り替える。

```yaml
world-reset:
  enabled: true
  worlds:
    - "resource_world"
    - "resource_nether"
  schedule:
    type: "weekly"          # "weekly" または "monthly"
    time: "12:00"            # 24時間表記、両モード共通
    # type: weekly のとき使用
    day-of-week: "FRIDAY"    # java.time.DayOfWeek の名前
    interval-weeks: 1        # 1=毎週、2=隔週
    # type: monthly のとき使用
    day-of-month: 1          # 1〜31
  lockout:
    pre-evacuation-minutes: 30
    reentry-confirm-seconds: 10
  announcements:
    minutes-before: [60, 30, 10, 5]
```

- `weekly`: 「曜日+時刻」+ `interval-weeks`（1=毎週、2=隔週）。
- `monthly`: 「日付+時刻」（例: 毎月1日12:00）。`day-of-week`/`interval-weeks` は無視する。
- `day-of-month` がその月に存在しない日（例: 31日指定で2月）の場合は、**その月の末日に丸める**。
- 次回リセット時刻は状態を永続化せず、現在時刻とスケジュール設定から都度計算する（`HeadshopManager.shopDate()` と同様の「基準時刻を跨ぐ日付」計算パターン）。
- サーバー停止等でスケジュール時刻を過ぎてしまった場合、過去分を追いかけて即時実行はしない。現在時刻から見て次に到来する未来の occurrence を再計算するだけに留める（資源ワールドの非クリティカルな性質上、取りこぼしは許容する）。

## プレイヤー安全対策

1. **事前告知（4段階の固定カウントダウン）**: リセット時刻の **1時間前・30分前・10分前・5分前** に、対象ワールド名と残り時間を含む全体放送を行う。タイミングは固定でconfig化はしない。
2. **30分前の強制退避**: 告知と同時に、対象ワールド内の全プレイヤーを強制的にメインワールド（スポーン等）へテレポートして退避させる。
3. **30分間のロックアウト窓**: 強制退避からリセット実行までの30分間、対象ワールドへの再入場を検知したら警告を出し、**10秒以内に同じ操作を再実行しないと入れない**（`utils/TeleportSafetyUtil` の「不安全な着地点で10秒以内に再実行で強制テレポート」と同じ確認フローパターンを、新規ユーティリティとして踏襲する）。
   - 再入場検知は `PlayerTeleportEvent` を一括フックする。原因（コマンド、ネザーポータル、エンドポータル、他プラグイン経由等）を問わず、テレポート先が対象ワールドかつロックアウト窓内であれば一律チェック対象にする。
4. **通常時（ロックアウト窓の外）の入場時**: 対象ワールドに入場するたびに、次回リセット日時とこのリセットシステムの説明文をプレイヤーへ送信する。

## 管理者用の即時手動リセット

- `/worldreset now <world>` コマンドを追加する。
- 通常スケジュールの「4段階告知 + 30分退避」プロセスは踏まない。**短縮版**として、コマンド実行時に即座に対象ワールドの全プレイヤーをメインワールドへ退避させたうえで、直ちにリセット（フォルダ削除→再生成）を実行する。
- 対象ワールドが `world-reset.worlds` に含まれていない場合はエラーメッセージを返し、実行しない。

## home/warp の扱い

- リセット対象ワールドに存在する home / warp は、**リセット実行時に自動的にDBから削除**する（`homes`/`warps` テーブルから該当 `world` のレコードを一括削除）。定期リセット・即時リセットのどちらでも同様に行う。
- 対象ワールド内で `/sethome` または `/setwarp` を実行した場合、「このワールドは自動リセット対象のため、リセット時にこの home/warp は削除されます」という警告メッセージを表示する。ただし**コマンド自体はブロックしない**（登録は通常通り成功させる）。警告文言は `messages.yml` の新規 `world-reset.*` セクションに追加する。

## クラス構成

- `managers/WorldResetManager` — スケジュール管理とリセット実行本体。`AutoBroadcastManager` と同じ `Bukkit.getGlobalRegionScheduler().runAtFixedRate(...)` パターンでスケジュールチェック・告知・退避・リセット実行を行う。`/stellariareload` 時の `reloadFeatureManagers()` にも登録し、スケジュール設定の再読み込みに対応する。
- `listeners/WorldResetListener` — 新規の独立リスナー（既存の `PlayerListener` には相乗りさせない。`PlayerListener` は join/quit・AFK・elevator が守備範囲であり毛色が異なるため）。`PlayerTeleportEvent` でのロックアウト窓チェックと、`/sethome`・`/setwarp` 実行時の警告表示を担当する。
- `utils/WorldResetSafetyUtil` — ロックアウト窓の「10秒以内に同じ操作を再実行すると強制テレポート」という確認フローの新規実装。`TeleportSafetyUtil` とは別クラスとして切り出す（用途が「不安全な着地点」ではなく「リセット待ちワールドへの再入場」であるため）。
- `commands/WorldResetCommand` — `/worldreset now <world>` の実装。

## 参考: 既存コードの関連パターン（実装時の流用元）

- `utils/TeleportSafetyUtil`: 「不安全な着地点→10秒以内に同コマンド再実行で強制テレポート」の確認フロー。ロックアウト窓の再入場確認 (`WorldResetSafetyUtil`) の実装パターンとして流用する。
- `managers/AutoBroadcastManager`: 定期的な全体放送のパターン（`Bukkit.getGlobalRegionScheduler().runAtFixedRate`）。スケジュールチェック自体もこのパターンで実装する。
- `HeadshopManager` の `shopDate()`（`docs/superpowers/specs/2026-09-16-headshop-design.md` 参照）: 「基準時刻を跨ぐ日付」の考え方。次回リセット時刻の計算で同種のロジックを使う。
