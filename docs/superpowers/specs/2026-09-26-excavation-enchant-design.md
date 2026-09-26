# 範囲破壊エンチャント 設計書

- 作成日: 2026-09-26
- 対象: StellariaCore / StellariaEnchants（Paper 1.21.11）
- 状態: レビュー待ち
- 前提: [カスタムエンチャント 設計書](2026-09-25-custom-enchants-design.md) の構成（登録は StellariaEnchants、効果は StellariaCore の `enchants/`）に従う。

## 1. 目的

整地やトンネル掘りを楽にする、3x3 の範囲破壊エンチャントを追加する。強力なエンチャントなので、入手手段は古代都市のチェストに限定し、レア度で釣り合いを取る。

### 成功条件

- ツルハシかシャベルで掘ると、殴った面に垂直な 3x3 の範囲がまとめて壊れる。
- エンチャントテーブルと村人の取引には出現せず、古代都市のチェストからだけ入手できる。
- しゃがんでいる間は 1 ブロックだけ掘れる。
- 道具の残り耐久値が少なくなると範囲破壊が止まり、道具が壊れる前に警告が出る。
- 土地保護・`/mine` の鉱石報酬・自動精錬・耐久消費が、通常の破壊と同じように適用される。

## 2. エンチャント仕様

| 項目 | 値 |
|---|---|
| ID | `excavation`（`stellaria:excavation`） |
| 表示名 | 範囲破壊 |
| 最大レベル | 1（範囲は 3x3 固定） |
| 対象 | ツルハシ・シャベル |
| 排他 | なし（シルクタッチ・幸運・効率強化・自動精錬と併用可） |
| 出現 | 古代都市のチェストのみ（エンチャントテーブル・取引には出ない） |
| デメリット | なし。周囲のブロックも通常の破壊として扱うため、壊した数だけ耐久値が減る（耐久力は有効） |

レベルを設けない理由は 2 つある。1 つは、本同士の合成で上位レベルを作れるとレア度が薄れること。もう 1 つは、5x5 以上だと資源ワールドの荒れ方と 1 回の処理量が大きくなりすぎることである。

## 3. 登録（stellaria-enchants）

### 3.1 treasure フラグ

- `EnchantDefinition` に `boolean treasure` を追加する。既存の 10 種類はすべて `false` とする。
- `StellariaEnchantsBootstrap` のタグ登録で、`treasure == true` の定義を `#minecraft:in_enchanting_table` と `#minecraft:tradeable` に追加しない。
- `#minecraft:on_random_loot` など、その他のランダム入手用タグには既存の定義と同じく追加しない。これにより、バニラのルートテーブルや釣り、モブの装備からは出現しない。

### 3.2 対象アイテム

- `#minecraft:pickaxes` と `#minecraft:shovels` を含むアイテムタグ `stellaria:enchantable/excavation` を、`LifecycleEvents.TAGS.preFlatten(RegistryKey.ITEM)` で登録する。
- `EnchantDefinition` の `itemTag` にこのタグを指定する。`supportedItems` は既存どおり `event.getOrCreateTag(...)` で解決する。
- 新しい素材のツール（銅など）が追加されても、バニラのタグに従って自動で対象になる。

### 3.3 登録値

`new EnchantDefinition(EnchantKeys.EXCAVATION, "範囲破壊", <3.2 のタグ>, null, 1, 1, 25, 0, 8, EquipmentSlotGroup.MAINHAND, false, true)`

- 出現重み 1、最小コスト 25、金床コスト 8。テーブルに出ないため重みとコストは実質的に使われないが、金床での付与コストは 8 になる。

## 4. 効果（StellariaCore `enchants/`）

### 4.1 構成

| クラス | 役割 |
|---|---|
| `CustomEnchant.EXCAVATION` | enum に追加。`EnchantKeys.EXCAVATION` を共有する |
| `ExcavationRules` | 純粋なロジック。面から 3x3 の座標を求める計算と、周囲のブロックを壊すかどうかの判定 |
| `ExcavationListener` | `BlockBreakEvent` を受けて範囲破壊を実行する |
| `AncientCityLootListener` | `LootGenerateEvent` で古代都市のチェストに本を追加する |

どちらのリスナーも `CustomEnchantModule#enable()` で登録する。StellariaEnchants が無い場合は `CustomEnchantRegistry` が `EXCAVATION` を無効として扱い、両リスナーとも何もしない。

### 4.2 範囲破壊の流れ

`ExcavationListener#onBlockBreak`（`ignoreCancelled = true`、`priority = HIGH`）。土地保護（`LOW`）やロビー保護（`NORMAL`）がキャンセルした破壊では動かないように、それらより後の優先度にする。イベントを変更しない処理ではないため `MONITOR` は使わない。

1. 以下のいずれかに当てはまれば何もしない。
   - このプレイヤーが範囲破壊の処理中（再入防止。4.4 参照）
   - メインハンドの道具に `EXCAVATION` が付いていない
   - しゃがんでいる
   - クリエイティブモードかスペクテイターモード
2. 中心のブロックが道具の mineable タグ（ツルハシなら `#mineable/pickaxe`、シャベルなら `#mineable/shovel`）に入っていなければ何もしない。
3. 殴った面を求める。掘り始めたときにクライアントが送ってきた面（`BlockDamageEvent#getBlockFace`、プレイヤーごとに直近 1 件を `LastHitFaces` に記録）が同じブロックのものならそれを使う。無ければ `player.rayTraceBlocks(<ブロック到達距離>)` で視線の先を判定し、ヒットしたブロックが壊したブロックと一致すればその面を使う。どちらでも決まらない場合は範囲破壊をせず中心だけにする。
4. `ExcavationRules.offsets(face)` で、面に垂直な平面上の周囲 8 マスを求める。
   - `UP`/`DOWN` を殴った場合は X-Z 平面
   - `NORTH`/`SOUTH` を殴った場合は X-Y 平面
   - `EAST`/`WEST` を殴った場合は Z-Y 平面
5. 周囲の各ブロックについて、4.3 の条件を満たすものだけを `player.breakBlock(block)` で壊す。
6. 1 ブロック壊すごとに道具の残り耐久値を確認し、`min-durability`（既定 10）を下回ったらそこで打ち切る。打ち切ったとき、または開始時点ですでに下回っていたときは、アクションバーに警告を出す（4.5）。

`player.breakBlock()` は通常の破壊と同じく `BlockBreakEvent` と `BlockDropItemEvent` を発火させる。そのため、周囲のブロックにも土地保護・`/mine` の鉱石報酬・自動精錬・幸運・シルクタッチ・耐久消費がそのまま適用される。

### 4.3 周囲のブロックを壊す条件

以下をすべて満たすブロックだけを壊す（`ExcavationRules` で判定する）。

- 空気・液体ではない
- 道具の mineable タグに入っている（ツルハシなら `#mineable/pickaxe`、シャベルなら `#mineable/shovel`）
- 道具のランクが足りている（`block.isPreferredTool(tool)`）。`isPreferredTool` は道具を選ばないブロック（ガラス・松明・作物・木材など）にも true を返すため、mineable タグと組み合わせてランクの確認にだけ使う
- 硬さ（`getType().getHardness()`）が 0 以上で、「中心のブロックの硬さ」と 4.5 の大きい方以下である。石を掘り進めたときに鉱石・丸石・深層岩・深層岩の鉱石（最大 4.5）を残さず、黒曜石（50）や金属ブロック（5.0）は巻き込まない。岩盤などの壊せないブロック（硬さが負）は対象外になる
- ブロックエンティティ（`BlockState` が `TileState`）ではない。チェスト・かまどの中身、スポナー、怪しげな砂・砂利、看板などを巻き込まないようにする
- 壊すと二度と手に入らないブロック（芽生えたアメジスト、強化深層岩）ではない

### 4.4 再入の防止

`player.breakBlock()` はもう一度 `BlockBreakEvent` を発火させる。範囲破壊が連鎖しないように、処理中のプレイヤーの UUID を `Set<UUID>` で持ち、処理は `try`/`finally` で必ず解除する。

`/mine` の一括採掘とは次のように共存する。

- 範囲破壊で壊した周囲の鉱石にも `/mine` が反応し、つながった鉱石を掘ることがある。これは両方の機能を持つプレイヤーへの自然な相乗効果として許容する。
- `/mine` 側は自身の `claimedBlocks` で二重処理を防いでいる。範囲破壊側は処理中フラグで自身の連鎖を防ぐ。

### 4.5 耐久値の警告

- `Damageable` な道具について、`最大耐久値 - 現在のダメージ` を残り耐久値とする。
- 残り耐久値が `min-durability` を下回ったら、以降の範囲破壊は行わない（中心の 1 ブロックだけ通常どおり壊れる）。
- 警告は `ActionBarManager#flash(player, "excavation", ..., 60L)` で表示する。文言は messages.yml の `custom-enchants.excavation_low_durability`。
- 耐久値が無い道具（`Unbreakable` 付きなど）は常に範囲破壊を行う。

## 5. 古代都市での入手

`AncientCityLootListener#onLootGenerate`（`LootGenerateEvent`）:

1. `event.getLootTable().getKey()` が `minecraft:chests/ancient_city` 以外なら何もしない。`minecraft:chests/ancient_city_ice_box` は対象外とする。
2. `ancient-city-chance`（既定 0.10）の確率で、`EXCAVATION` Lv.1 を格納したエンチャント本（`EnchantmentStorageMeta#addStoredEnchant`）を `event.getLoot()` に 1 冊追加する。
3. `EXCAVATION` が無効（StellariaEnchants 未導入）なら何もしない。

資源ワールドはワールドリセットで作り直されるため、古代都市が掘り尽くされて入手できなくなることはない。

## 6. 設定ファイル

### config.yml（`custom-enchants.excavation`、`/stellariareload` で反映）

```yaml
  excavation:
    # 古代都市のチェスト1個ごとに、範囲破壊の本が入る確率（0.0〜1.0）
    ancient-city-chance: 0.10
    # 道具の残り耐久値がこの値を下回ると範囲破壊を止め、中心の1ブロックだけ掘る
    min-durability: 10
```

`CustomEnchantConfig` にこの 2 つの値を追加し、既存の値と同じくキャッシュする。

### messages.yml（`custom-enchants.*`）

```yaml
  excavation_low_durability: "&%c道具の耐久値が残りわずかのため、範囲破壊を止めました。"
```

## 7. エラー処理

- StellariaEnchants が無い、または `excavation` が未登録の場合は、既存のエンチャントと同じく `CustomEnchantRegistry` が無効として扱い、StellariaCore は通常どおり起動する。
- 視線判定で面を特定できない場合は、範囲破壊をせず中心だけを壊す（誤った方向に掘らないことを優先する）。

## 8. テスト

- `ExcavationRules`
  - 6 方向それぞれの面について、周囲 8 マスのオフセットが正しい平面上にあり、中心を含まないこと
  - 硬さ・適正道具・容器・壊せないブロックの判定
- `EnchantDefinitionsTest`
  - `treasure` の定義が `excavation` だけであること
  - タグ登録の対象リストに `treasure` の定義が含まれないこと（タグに入れる定義を選ぶ処理を静的メソッドに切り出してテストする）
- 手動確認（`./gradlew runServer`）
  - 床・天井・東西南北の壁を掘ったときの範囲
  - しゃがみ中は 1 ブロックだけ
  - 耐久値 10 未満での停止と警告
  - 他人の土地の境界で、保護された側のブロックが壊れないこと
  - `/locate structure ancient_city` で古代都市を探し、チェストから本が出ること

## 9. ビルドとデプロイ

- StellariaEnchants の更新を含むため、反映にはサーバーの完全再起動が必要。
- config.yml と messages.yml に新しいキーが増えるため、既存のサーバーでは手動で追記する必要がある。
- DB の変更はない。

## 10. 対象外

- レベルによる範囲の拡大（5x5 など）
- 範囲破壊の ON/OFF を切り替えるコマンド（しゃがみで代用する）
- 古代都市以外のルートテーブルへの追加
