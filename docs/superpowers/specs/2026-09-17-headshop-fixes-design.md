# ヘッドショップ 不具合修正・改善 設計

- 日付: 2026-09-17
- ステータス: 承認待ち

`docs/superpowers/specs/2026-09-16-headshop-design.md`で実装された`/headshop`機能に対する不具合修正・改善のまとめ。対象ファイルは`gui/HeadshopAdminGui.java`・`gui/HeadshopPlayerHeadsGui.java`・`gui/HeadshopGui.java`・`messages.yml`。

## ① 管理者GUIでヘッドを登録できない（根本原因特定済み）

### 症状

`/headshop admin`を開き、頭アイテムをカーソルに乗せて空きスロットをクリックしても登録されない。カーソルへアイテムを乗せる操作（自分の持ち物内でのクリック）自体ができない。

### 原因

`HeadshopAdminGui#onClick`（111〜114行）が、クリックされたのがGUI本体（top）か自分の持ち物（bottom）かを区別せず、メソッド冒頭で無条件に`event.setCancelled(true)`を呼んでいる。

```java
public void onClick(InventoryClickEvent event) {
    event.setCancelled(true);  // ← ここが常に効いてしまう
    if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= getInventory().getSize()) {
        return; // 自分の持ち物内クリックはここで弾かれるが、キャンセルは既に確定済み
    }
    ...
```

`GuiListener`は`InventoryClickEvent`の`getInventory()`（＝常にトップインベントリ）で`Gui`かどうかを判定して`onClick`を呼ぶため、プレイヤーが**自分の持ち物内**をクリックした場合もこの`onClick`が呼ばれる。その際`event.getRawSlot()`はトップインベントリのサイズ（54）以上になるため後続の早期returnで処理自体はスキップされるが、そのときには既に`setCancelled(true)`が実行済みで、「頭アイテムを持ち物内でクリックしてカーソルに乗せる」操作そのものがキャンセルされてしまう。結果、運営はGUIを開いた**後**にカーソルへアイテムを乗せる手段が無くなる（GUIを開く前から既にカーソルに乗せていた場合のみ動作する状態）。

### 修正方針

`setCancelled(true)`を、既存の「自分の持ち物内クリックかどうか」の判定より**後**に移動する。

```java
@Override
public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= getInventory().getSize()) {
        return; // 自分の持ち物内のクリックは素通しする（頭アイテムをカーソルに乗せる操作を許可するため）
    }
    event.setCancelled(true);
    ...
```

条件式・以降のロジック（`handleBackButton`、ページ送り、`registerFromCursor`、シフトクリック削除）は変更不要。トップインベントリ内のクリックは従来通り全てキャンセルされる（登録・削除操作以外でアイテムが実際に動くことはない）。

### 影響範囲の確認

同じ「常にキャンセル」パターンは`HeadshopGui`・`HeadshopPlayerHeadsGui`・`AdminShopGui`・`WarpSelectGui`等、購入専用/選択専用のGUIにもあるが、これらは自分の持ち物からアイテムを取り出す操作を必要としないため変更しない。**カーソルにアイテムを乗せて登録する操作が要る`HeadshopAdminGui`だけがこの修正の対象**（同種の「運営がアイテムを登録する」GUIが将来増えた場合も同じ注意が要る）。

## ② ヒント本に「Original」等の本のツールチップが出る（根本原因特定済み）

### 症状

`HeadshopPlayerHeadsGui`・`HeadshopAdminGui`のヒント案内アイテムに「Original」という余計な文字列が表示される。

### 原因

両GUIの`hintItem()`が`Material.WRITTEN_BOOK`（署名された本）を使っているが、`author`/`title`/`generation`等の`BookMeta`必須プロパティを一切設定していない。`WRITTEN_BOOK`はこれらのNBT/コンポーネントを前提にしたアイテムのため、未設定のままだとクライアント側のツールチップに世代（generation）や著者に関するデフォルト表示が漏れ出る。

### 修正方針

2箇所とも`Material.WRITTEN_BOOK`→`Material.BOOK`（署名なしの普通の本）に変更する。

- `HeadshopPlayerHeadsGui#hintItem()`（113行）
- `HeadshopAdminGui#hintItem()`（98行）

`BOOK`は`BookMeta`を要求しない汎用`ItemMeta`のみのアイテムなので、`displayName()`/`lore()`の設定コードはそのまま変更不要。他に手を加える必要はない。

## ③ メインGUI（`/headshop`）にもヒント本を設置

### 背景

現状、案内アイテムは`HeadshopPlayerHeadsGui`（プレイヤーヘッド一覧、「本日のおすすめは reset-time に入れ替わる」）と`HeadshopAdminGui`（登録操作の説明）にしかなく、`HeadshopGui`（メインの3行GUI）自体には案内が無い。

### 実装方針

`HeadshopGui`の空きスロットに`Material.BOOK`の案内アイテムを1つ追加する。

- 配置スロット: `4`（上段中央、既存の`HEAD_SLOTS`=11,12,13,14,15、`PLAYER_HEADS_BUTTON_SLOT`=22とは重ならない）
- 新規定数 `HINT_SLOT = 4` を`HeadshopGui`に追加
- `populate()`内で他アイテムと同様に`getInventory().setItem(HINT_SLOT, hintItem(viewer))`を呼ぶ
- クリックしても何も起きない（`onClick`の`headsBySlot`にもプレイヤーヘッドボタンにも該当しないスロットなので、既存の分岐に何も追加しなくても自然に無視される）

### `messages.yml`追加分

```yaml
headshop:
  main-hint:
    - "&%9&l| &%bヒント"
    - "&%7プレイヤーヘッド一覧は"
    - "&%7下のボタンから購入できます"
    - "&%7本日のおすすめヘッドは"
    - "&%7毎日 &%e%reset_time% &%7に入れ替わります"
```

`player-heads-hint`と同じ構成（`%reset_time%`を`headshop.reset-time`から埋め込む）を踏襲する。`hintItem()`の実装も`HeadshopPlayerHeadsGui#hintItem()`とほぼ同じ形になる（`meta.displayName`に`headshop.hint-title`、`meta.lore`に`main-hint`の各行を`FormatUtil.replace(line, "%reset_time%", resetTime)`して詰める）。

## ④ プレイヤーヘッドのloreに出る「dynamic」の正体（要実機検証・未確定）

### 現状の調査結果

ソースコード全体を検索したが、`"dynamic"`という文字列はどこにも存在しない（`grep -rniI dynamic src/`で0件）。`HeadshopPlayerHeadsGui#createDisplayItem`は`SkullMeta#setOwningPlayer(OfflinePlayer)`で本物のプレイヤーの頭を作り、`displayName`と価格ロアだけを設定しており、「dynamic」を出す実装は無い。

したがって、これは**このプラグインが書いた文言ではなく、Minecraftクライアント側が表示しているツールチップの可能性が高い**が、確証は無い。有力な仮説（未検証）:

- `setOwningPlayer(OfflinePlayer)`で作られる`PlayerProfile`は、テクスチャ（スキン）がまだ解決されていない「不完全なプロフィール」から始まり、サーバーが非同期でMojang側から解決する。1.20.5以降のアイテムコンポーネント刷新後のクライアントが、この未解決/解決中のプロフィールに対して何らかの注記を表示している可能性がある。

### 対応方針

推測のまま設計に組み込むと的外れになるリスクがあるため、**実装前に実機で以下を確認**することを推奨する:

1. F3+H（詳細ツールチップ表示）のON/OFFで「dynamic」の表示・非表示が変わるか
2. `headshop_pool`由来の頭（`HeadshopManager#createHeadItem`。テクスチャを直接`ProfileProperty`にセットし、プロフィールUUIDはテクスチャ文字列から決定的に生成——`setOwningPlayer`は使っていない）には同じ表示が出るか。出なければ`setOwningPlayer`固有の現象と切り分けられる
3. オンライン中のプレイヤーの頭と、直近ログアウトしたオフラインプレイヤーの頭とで表示差があるか（プロフィール解決状況による差の可能性を確認）

この結果を踏まえて、実害（誤解を招く／見た目が悪い等）があれば追加の設計（例: プロフィール解決を待ってからアイテムを生成する、別のAPIでSkullMetaを組み立てる等）を別途起こす。実害が無ければ対応不要と判断してよい。

## テスト方針

本リポジトリに自動テストは無く、`runServer`での実機確認を行う:

- `./gradlew build`が通ること
- **①の確認**: `/headshop admin`を開き、自分の持ち物内の頭アイテムをクリックしてカーソルに乗せられること→空きスロットをクリックして登録されること→重複登録・シフトクリック削除も従来通り動くこと
- **②の確認**: プレイヤーヘッドGUI・管理者GUI双方のヒント本をホバーして「Original」等の余計な表示が消えていること
- **③の確認**: `/headshop`のメインGUI上部にヒント本が表示され、ロアに`reset-time`の値が正しく埋め込まれていること。クリックしても何も起きないこと
- **④の確認**: 上記の実機検証手順を実施し、結果を記録する
