# GUI操作・ヘッドショップ・カスタムヘッド・ロビーコマンド設計

- 日付: 2026-09-17
- ステータス: レビュー待ち

## 目的と対象範囲

以下を一つの変更として実装する。

1. 全ての `Gui` 派生画面で、画面上部のGUIアイテムは動かせないまま、下部のプレイヤーインベントリは通常どおり操作できるようにする。
2. `/headshop admin` の登録・削除をDBの実データと一致させ、閉じて開き直しても一覧が正しく残るようにする。
3. `/headshop reset` で、運営が当日のおすすめヘッドを即時に再抽選できるようにする。
4. `customhead.yml` でカスタムヘッドを直接管理し、`/menu` と `/world` のGUIアイコンから参照できる共通Utilを追加する。
5. `/lobby` で設定したロビーワールドのスポーン位置へ移動できるようにする。

## GUI入力の共通ルール

`GuiListener` がトップインベントリのクリックだけを各GUIの `onClick` へ渡す。従って、既存GUIがトップ側で行っている `event.setCancelled(true)` はGUIアイテムだけを保護する。

- プレイヤーインベントリ（下部）の通常クリック、カーソルへの取得、スロット間の移動は許可する。
- 下部からのShiftクリックとダブルクリックは、アイテムをGUI内へ移動・回収してしまう可能性があるためキャンセルする。
- `InventoryDragEvent` は、ドラッグ先にトップインベントリのスロットが一つでも含まれる場合だけキャンセルする。
- これにより、`/headshop admin` はGUIを開いた後に自分の持ち物からヘッドをカーソルへ載せられる一方、他の全GUIで画面内アイテムを持ち出したり入れ替えたりできない。

## ヘッドショップ

### 管理GUIの永続化と削除

`HeadshopManager#addToPool` はDBへのINSERT結果を確認し、成功時に実際の `PoolHead`（生成済みのDB IDを含む）を返す。INSERTに失敗した場合は成功表示・GUI表示を行わない。

`HeadshopAdminGui` は追加直後に仮ID `0` のアイテムを表示しない。返された実IDを一覧へ保持するため、追加した同じヘッドをShiftクリックすると正しい行を削除できる。削除は従来どおり `headshop_rotation` の該当参照も先に消す。

### 即時ローテーション更新

`/headshop reset` を追加する。実行者はプレイヤーに限らず、`stellaria.headshop.admin` を持つ運営・コンソールが利用できる。

- 現在のショップ日（`shopDate()`）のローテーションを削除して即時に再生成する。
- 現在表示中だったヘッドを候補から除外するので、候補数が十分なら見た目が必ず変わる。
- プールが少なく候補不足の場合は、現在のヘッドとの重複を許容して可能な件数を抽選する。
- 権限不足・空のプール・成功を `messages.yml` の `headshop.*` メッセージで通知する。
- タブ補完は `admin` と `reset` を出す。

## カスタムヘッド

外部プラグインやHTTP APIには依存しない。Paperの `PlayerProfile` と `textures` プロパティを使用する。ヘッド配布サイト等から得たbase64のテクスチャ値をそのまま設定する。

### `customhead.yml`

プラグイン初回起動時に同梱テンプレートをデータフォルダへコピーし、`ConfigManager`へ登録する。追加・削除はこのファイルを直接編集し、`/stellariareload` で反映する。

```yaml
heads:
  lobby-compass:
    texture: "<base64-texture-value>"
```

### 共通UtilとGUIでの指定方法

`CustomHeadUtil#create(plugin, id)` は設定からヘッドを作成する。存在しないIDまたは空テクスチャでは `null` を返し、呼び出し元は通常の `Material` アイコンへフォールバックする。プロフィールUUIDはIDとテクスチャから決定的に作り、同じカスタムヘッドは正しくスタック可能にする。

`/menu` の各 `menu.items` には、既存の `material` をフォールバックとして残し、任意で `custom-head` を書ける。

```yaml
- slot: 23
  material: COMPASS
  custom-head: lobby-compass
  name: "&%eワールド移動"
  action: world
```

`/world` GUI用にはワールド名ごとの任意マッピングを追加する。未指定時は従来の環境別マテリアルを使う。

```yaml
world:
  gui-custom-heads:
    lobby: lobby-compass
```

このUtilは今後のGUIでも同じID参照で使える。

## `/lobby`

`lobby.world`（既定値 `lobby`）で指定された読み込み済みワールドのスポーン位置へ、`player.teleportAsync` で移動する。

- プレイヤー以外は専用メッセージで拒否する。
- ワールド名が無効または未ロードなら、設定内容を示す専用エラーメッセージを返す。
- `plugin.yml` に `lobby` コマンドと `stellaria.lobby` 権限（既定true）を登録する。
- `/menu` へのボタン追加はこの変更に含めない。必要なら既存の `world` アクションまたは将来の `lobby` アクションとして別途追加できる。

## テスト方針

実装前に、GUIイベントの判定（トップクリック・下部通常クリック・Shiftクリック・GUIへまたがるドラッグ）と、カスタムヘッドIDの設定解決を対象にJUnitテストを追加する。各テストは変更前に失敗することを確認する。

最終的に `./gradlew test` と `./gradlew build` を実行する。実機では次を確認する。

1. 任意のGUIを開き、下部インベントリだけを通常操作でき、GUI内のアイテムを動かせない。
2. `/headshop admin` で登録、閉じて再度開く、Shiftクリック削除を順に実行し、DBと表示が一致する。
3. `/headshop reset` 実行後、十分なプールがあれば当日の表示が更新される。
4. `customhead.yml` のIDを `/menu` と `/world` に設定し、`/stellariareload` 後に正しい見た目になる。存在しないIDではフォールバックアイコンになる。
5. `/lobby` で設定ワールドのスポーン位置へ移動し、無効なワールド名ではエラーになる。
