# 範囲破壊エンチャント 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ツルハシ・シャベル用の 3x3 範囲破壊エンチャント「範囲破壊」（`stellaria:excavation`）を追加し、古代都市のチェストからだけ入手できるようにする。

**Architecture:** 登録は StellariaEnchants（bootstrapper）、効果は StellariaCore の `enchants/` という既存の分担に従う。`EnchantDefinition` に `treasure` フラグを足してエンチャントテーブル・取引のタグから外し、入手は `LootGenerateEvent` で古代都市のチェストに本を差し込む。範囲破壊は `BlockBreakEvent` から周囲 8 マスを `player.breakBlock()` で壊すため、土地保護・`/mine`・自動精錬・耐久消費が通常の破壊と同じく適用される。

**Tech Stack:** Java 21、Paper API 1.21.11（Registry API / Lifecycle API）、Gradle（Kotlin DSL、マルチプロジェクト）、JUnit 5

**Spec:** `docs/superpowers/specs/2026-09-26-excavation-enchant-design.md`

## Global Constraints

- エンチャント ID は `excavation`、表示名は「範囲破壊」、最大レベル 1、範囲は 3x3 固定。
- 対象はツルハシとシャベルのみ（`#minecraft:pickaxes` と `#minecraft:shovels` を含む `stellaria:enchantable/excavation` タグ）。
- ほかのエンチャントとの排他はなし。
- `treasure == true` の定義は `#minecraft:in_enchanting_table` と `#minecraft:tradeable` に入れない。
- 入手は `minecraft:chests/ancient_city` のみ（`ancient_city_ice_box` は対象外）。既定の確率は 0.10。
- 残り耐久値が `min-durability`（既定 10）を下回ったら範囲破壊を止め、アクションバーに警告を出す。
- しゃがみ中、クリエイティブ、スペクテイターでは発動しない。
- config.yml / messages.yml の文言は `&%<char>` のカスタムパレットで書く。ユーザー向けの文字列はすべて messages.yml に置く。
- `./gradlew test --tests X` はサブプロジェクト全部にフィルタがかかり失敗するため、ルートは `./gradlew :test --tests X`、サブプロジェクトは `./gradlew :stellaria-enchants:test` のように指定する。
- コミットメッセージは既存の形式（`feat(enchants): ...` など、日本語の本文）に合わせ、末尾に `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` を付ける。

## Review Focus

- 周囲のブロックが中心より硬い場合（ネザーラックを掘った横に石がある、など）は壊さない。境界は「中心と同じ硬さなら壊す」。→ Task 2 のテストで固定する。
- 残り耐久値がちょうど `min-durability` のときは範囲破壊を続け、1 下回ったら止める。耐久値の無い道具（Unbreakable）は常に続ける。→ Task 2 のテストで固定する。
- 岩盤など硬さが負のブロックと、チェスト・かまどなどの容器は、適正道具で掘っていても壊さない。→ Task 2 のテストで固定する。
- 古代都市の氷室（`chests/ancient_city_ice_box`）やほかの構造物のチェストには本を入れない。確率 0 なら入れず、1 なら必ず入れる。→ Task 4 のテストで固定する。
- `player.breakBlock()` が再び `BlockBreakEvent` を発火しても、範囲破壊は連鎖しない。→ ユニットテストでは扱いにくいため、Task 3 の実装で `try`/`finally` による再入防止を必ず入れ、Task 5 の手動確認で「掘った範囲が 3x3 を超えない」ことを確かめる。

---

## ファイル構成

| ファイル | 種別 | 役割 |
|---|---|---|
| `enchant-keys/src/main/java/org/craftcore/stellaria/enchantkeys/EnchantKeys.java` | 変更 | `EXCAVATION` 定数を追加 |
| `enchant-keys/src/test/java/org/craftcore/stellaria/enchantkeys/EnchantKeysTest.java` | 変更 | 件数を 11 に更新 |
| `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/EnchantDefinition.java` | 変更 | `treasure` フラグを追加 |
| `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/EnchantDefinitions.java` | 変更 | 範囲破壊の定義、対象アイテムタグ、`discoverable()` |
| `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/StellariaEnchantsBootstrap.java` | 変更 | アイテムタグ登録、テーブル・取引タグから treasure を除外 |
| `stellaria-enchants/src/test/java/org/craftcore/stellariaenchants/EnchantDefinitionsTest.java` | 変更 | treasure とタグ対象のテスト |
| `src/main/java/org/craftcore/stellaria/enchants/ExcavationRules.java` | 新規 | 3x3 のオフセット計算、破壊可否、耐久判定（純粋ロジック） |
| `src/test/java/org/craftcore/stellaria/enchants/ExcavationRulesTest.java` | 新規 | 上記のテスト |
| `src/main/java/org/craftcore/stellaria/enchants/CustomEnchant.java` | 変更 | `EXCAVATION` を追加 |
| `src/main/java/org/craftcore/stellaria/enchants/ExcavationListener.java` | 新規 | 範囲破壊の実行 |
| `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantConfig.java` | 変更 | `excavation.*` の 2 値 |
| `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantRegistry.java` | 変更 | `enchantment(CustomEnchant)` を追加 |
| `src/main/java/org/craftcore/stellaria/enchants/AncientCityLootListener.java` | 新規 | 古代都市のチェストに本を追加 |
| `src/test/java/org/craftcore/stellaria/enchants/AncientCityLootListenerTest.java` | 新規 | 追加判定のテスト |
| `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java` | 変更 | 2 つのリスナーを登録 |
| `src/main/resources/config.yml` | 変更 | `custom-enchants.excavation` |
| `src/main/resources/messages.yml` | 変更 | `custom-enchants.excavation_low_durability` |
| `CLAUDE.md` | 変更 | エンチャント数と treasure の説明 |

---

### Task 1: 範囲破壊エンチャントの登録（enchant-keys / stellaria-enchants）

**Files:**
- Modify: `enchant-keys/src/main/java/org/craftcore/stellaria/enchantkeys/EnchantKeys.java`
- Modify: `enchant-keys/src/test/java/org/craftcore/stellaria/enchantkeys/EnchantKeysTest.java`
- Modify: `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/EnchantDefinition.java`
- Modify: `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/EnchantDefinitions.java`
- Modify: `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/StellariaEnchantsBootstrap.java`
- Test: `stellaria-enchants/src/test/java/org/craftcore/stellariaenchants/EnchantDefinitionsTest.java`

**Interfaces:**
- Consumes: なし
- Produces:
  - `EnchantKeys.EXCAVATION = "excavation"`（`EnchantKeys.ALL` の末尾）
  - `EnchantDefinition` の最後のコンポーネント `boolean treasure`
  - `EnchantDefinitions.EXCAVATION_ITEMS : TagKey<ItemType>`（`stellaria:enchantable/excavation`）
  - `EnchantDefinitions.discoverable() : List<EnchantDefinition>`（`treasure == false` のものだけ）

- [ ] **Step 1: 失敗するテストを書く**

`EnchantKeysTest.allContainsTenDistinctIds` を次のように置き換える。

```java
    @Test
    void allContainsElevenDistinctIds() {
        assertEquals(11, EnchantKeys.ALL.size());
        assertEquals(11, new HashSet<>(EnchantKeys.ALL).size());
    }
```

`EnchantDefinitionsTest` の `maxLevelsMatchTheSpec` の期待値に `EnchantKeys.EXCAVATION, 1` を加える。`Map.of` は 10 組までなので `Map.ofEntries` に書き換える。

```java
    @Test
    void maxLevelsMatchTheSpec() {
        Map<String, Integer> levels = EnchantDefinitions.ALL.stream()
                .collect(Collectors.toMap(EnchantDefinition::id, EnchantDefinition::maxLevel));
        assertEquals(Map.ofEntries(
                Map.entry(EnchantKeys.SMELTING, 1), Map.entry(EnchantKeys.PURSUIT, 3),
                Map.entry(EnchantKeys.REPLANT, 1), Map.entry(EnchantKeys.HARVEST, 3),
                Map.entry(EnchantKeys.GLIDE_BOOST, 3), Map.entry(EnchantKeys.LAUNCH, 2),
                Map.entry(EnchantKeys.LIFESTEAL, 3), Map.entry(EnchantKeys.LAST_STAND, 1),
                Map.entry(EnchantKeys.DOUBLE_JUMP, 2), Map.entry(EnchantKeys.ANGLER, 3),
                Map.entry(EnchantKeys.EXCAVATION, 1)), levels);
    }
```

同じクラスに次のテストを追加する（`import java.util.List;` を追加）。

```java
    @Test
    void onlyExcavationIsTreasure() {
        for (EnchantDefinition definition : EnchantDefinitions.ALL) {
            assertEquals(definition.id().equals(EnchantKeys.EXCAVATION), definition.treasure(), definition.id());
        }
    }

    @Test
    void discoverableExcludesTreasureEnchantments() {
        List<String> ids = EnchantDefinitions.discoverable().stream().map(EnchantDefinition::id).toList();
        assertFalse(ids.contains(EnchantKeys.EXCAVATION));
        assertEquals(EnchantDefinitions.ALL.size() - 1, ids.size());
    }

    @Test
    void excavationTargetsTheCustomPickaxeAndShovelTag() {
        EnchantDefinition excavation = EnchantDefinitions.ALL.stream()
                .filter(definition -> definition.id().equals(EnchantKeys.EXCAVATION))
                .findFirst().orElseThrow();
        assertEquals(EnchantDefinitions.EXCAVATION_ITEMS, excavation.itemTag());
        assertEquals("stellaria:enchantable/excavation", EnchantDefinitions.EXCAVATION_ITEMS.key().asString());
    }
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :enchant-keys:test :stellaria-enchants:test`
Expected: コンパイルエラー（`EXCAVATION`、`treasure()`、`discoverable()`、`EXCAVATION_ITEMS` が無い）

- [ ] **Step 3: 実装する**

`EnchantKeys.java`:

```java
    public static final String ANGLER = "angler";
    public static final String EXCAVATION = "excavation";

    public static final List<String> ALL = List.of(
            SMELTING, PURSUIT, REPLANT, HARVEST, GLIDE_BOOST,
            LAUNCH, LIFESTEAL, LAST_STAND, DOUBLE_JUMP, ANGLER, EXCAVATION
    );
```

`EnchantDefinition.java` — クラスコメントに treasure の説明を足し、レコードの最後に `treasure` を追加する。

```java
/**
 * エンチャント 1 種類分の登録内容。対応アイテムはアイテムタグ（itemTag）か単体アイテム（singleItem）のどちらか一方で指定する。
 * 最小コストは「minCostBase + minCostPerLevel × (レベル - 1)」、最大コストは最小コスト + 30。
 * treasure が true のものはエンチャントテーブルと村人の取引に出さず、StellariaCore 側で別の入手手段を用意する。
 */
@SuppressWarnings("UnstableApiUsage")
public record EnchantDefinition(
        String id,
        String displayName,
        @Nullable TagKey<ItemType> itemTag,
        @Nullable TypedKey<ItemType> singleItem,
        int maxLevel,
        int weight,
        int minCostBase,
        int minCostPerLevel,
        int anvilCost,
        EquipmentSlotGroup slot,
        boolean exclusiveWithSilkTouch,
        boolean treasure
) {
```

`EnchantDefinitions.java` — 既存 10 件の末尾に `, false` を追加し、範囲破壊を加える。import に `io.papermc.paper.registry.RegistryKey`、`io.papermc.paper.registry.tag.TagKey`、`net.kyori.adventure.key.Key`、`org.bukkit.inventory.ItemType` を追加する。

```java
/**
 * 11 種類のエンチャントの登録内容。出現重みはバニラの目安（10=よく出る、5=普通、2=珍しい、1=とても珍しい）に合わせる。
 * 剣・斧用は #minecraft:enchantable/sharp_weapon（ダメージ増加と同じ対象）を使う。
 * 範囲破壊は treasure 扱いで、テーブル・取引には出ない（古代都市のチェストからのみ入手）。
 */
@SuppressWarnings("UnstableApiUsage")
public final class EnchantDefinitions {

    /** 範囲破壊の対象（ツルハシ・シャベル）。StellariaEnchantsBootstrap で中身を登録する。 */
    public static final TagKey<ItemType> EXCAVATION_ITEMS =
            TagKey.create(RegistryKey.ITEM, Key.key(EnchantKeys.NAMESPACE, "enchantable/excavation"));

    public static final List<EnchantDefinition> ALL = List.of(
            new EnchantDefinition(EnchantKeys.SMELTING, "自動精錬",
                    ItemTypeTagKeys.PICKAXES, null, 1, 2, 15, 0, 4, EquipmentSlotGroup.MAINHAND, true, false),
            // ……既存の 9 件も同じく末尾に false を追加……
            new EnchantDefinition(EnchantKeys.ANGLER, "釣り人の粘り",
                    ItemTypeTagKeys.ENCHANTABLE_FISHING, null, 3, 5, 10, 10, 2, EquipmentSlotGroup.MAINHAND, false, false),
            new EnchantDefinition(EnchantKeys.EXCAVATION, "範囲破壊",
                    EXCAVATION_ITEMS, null, 1, 1, 25, 0, 8, EquipmentSlotGroup.MAINHAND, false, true)
    );

    private EnchantDefinitions() {
    }

    /** エンチャントテーブルと村人の取引に出すもの（treasure 以外）。 */
    public static List<EnchantDefinition> discoverable() {
        return ALL.stream().filter(definition -> !definition.treasure()).toList();
    }
}
```

注: `EXCAVATION_ITEMS` は `ALL` より前に宣言すること（static 初期化の順序で `null` にならないようにするため）。上の「……」は説明用で、実際には既存 10 件すべてを書き、各行の末尾に `, false` を足す。

`StellariaEnchantsBootstrap.java` — アイテムタグの登録を追加し、エンチャントタグは `discoverable()` だけにする。import に `io.papermc.paper.registry.keys.tags.ItemTypeTagKeys` を追加する。

```java
        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.TAGS.preFlatten(RegistryKey.ITEM).newHandler(event ->
                        event.registrar().setTag(EnchantDefinitions.EXCAVATION_ITEMS, List.of(
                                TagEntry.tagEntry(ItemTypeTagKeys.PICKAXES),
                                TagEntry.tagEntry(ItemTypeTagKeys.SHOVELS)))));

        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.TAGS.preFlatten(RegistryKey.ENCHANTMENT).newHandler(event -> {
                    // treasure のものはテーブル・取引に出さない（入手手段は StellariaCore 側で用意する）
                    List<TagEntry<Enchantment>> entries = EnchantDefinitions.discoverable().stream()
                            .map(definition -> TagEntry.valueEntry(definition.typedKey()))
                            .toList();
                    event.registrar().addToTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE, entries);
                    event.registrar().addToTag(EnchantmentTagKeys.TRADEABLE, entries);
                }));
```

アイテムタグのハンドラは `RegistryEvents.ENCHANTMENT.compose()` のハンドラより前に登録する。`supportedItems` は既存の `event.getOrCreateTag(definition.itemTag())` のままでよい。

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew :enchant-keys:test :stellaria-enchants:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミットする**

```bash
git add enchant-keys stellaria-enchants
git commit -m "feat(enchants): 範囲破壊エンチャントを古代都市限定の treasure として登録する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 範囲破壊の判定ロジック（ExcavationRules）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/ExcavationRules.java`
- Test: `src/test/java/org/craftcore/stellaria/enchants/ExcavationRulesTest.java`

**Interfaces:**
- Consumes: なし
- Produces:
  - `record ExcavationRules.Offset(int dx, int dy, int dz)`
  - `static List<Offset> offsets(BlockFace face)` — 周囲 8 マス。`UP`/`DOWN`/`NORTH`/`SOUTH`/`EAST`/`WEST` 以外は空リスト
  - `static boolean canBreakAround(boolean airOrLiquid, boolean preferredTool, float hardness, float centerHardness, boolean container)`
  - `static boolean hasEnoughDurability(@Nullable Integer remaining, int minDurability)` — `remaining == null`（耐久値なし）なら true

- [ ] **Step 1: 失敗するテストを書く**

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.block.BlockFace;
import org.craftcore.stellaria.enchants.ExcavationRules.Offset;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcavationRulesTest {

    private static void assertPlane(List<Offset> offsets, boolean xFixed, boolean yFixed, boolean zFixed) {
        assertEquals(8, offsets.size());
        assertEquals(8, new HashSet<>(offsets).size());
        assertFalse(offsets.contains(new Offset(0, 0, 0)));
        for (Offset offset : offsets) {
            if (xFixed) assertEquals(0, offset.dx(), offset.toString());
            if (yFixed) assertEquals(0, offset.dy(), offset.toString());
            if (zFixed) assertEquals(0, offset.dz(), offset.toString());
            assertTrue(Math.abs(offset.dx()) <= 1 && Math.abs(offset.dy()) <= 1 && Math.abs(offset.dz()) <= 1);
        }
    }

    @Test
    void floorAndCeilingSpreadHorizontally() {
        assertPlane(ExcavationRules.offsets(BlockFace.UP), false, true, false);
        assertPlane(ExcavationRules.offsets(BlockFace.DOWN), false, true, false);
    }

    @Test
    void northAndSouthWallsSpreadOnTheXyPlane() {
        assertPlane(ExcavationRules.offsets(BlockFace.NORTH), false, false, true);
        assertPlane(ExcavationRules.offsets(BlockFace.SOUTH), false, false, true);
    }

    @Test
    void eastAndWestWallsSpreadOnTheZyPlane() {
        assertPlane(ExcavationRules.offsets(BlockFace.EAST), true, false, false);
        assertPlane(ExcavationRules.offsets(BlockFace.WEST), true, false, false);
    }

    @Test
    void nonAxisFacesProduceNoOffsets() {
        assertTrue(ExcavationRules.offsets(BlockFace.SELF).isEmpty());
        assertTrue(ExcavationRules.offsets(BlockFace.NORTH_EAST).isEmpty());
    }

    @Test
    void breaksSofterOrEquallyHardPreferredBlocks() {
        assertTrue(ExcavationRules.canBreakAround(false, true, 1.5f, 1.5f, false));
        assertTrue(ExcavationRules.canBreakAround(false, true, 0.5f, 1.5f, false));
        assertTrue(ExcavationRules.canBreakAround(false, true, 0.0f, 0.0f, false));
    }

    @Test
    void skipsBlocksHarderThanTheCenter() {
        // ネザーラック（0.4）を掘ったとき、隣の石（1.5）や黒曜石（50）は壊さない
        assertFalse(ExcavationRules.canBreakAround(false, true, 1.5f, 0.4f, false));
        assertFalse(ExcavationRules.canBreakAround(false, true, 50f, 1.5f, false));
    }

    @Test
    void skipsUnbreakableAirLiquidContainersAndWrongTool() {
        assertFalse(ExcavationRules.canBreakAround(false, true, -1f, 1.5f, false)); // 岩盤
        assertFalse(ExcavationRules.canBreakAround(true, true, 0f, 1.5f, false));   // 空気・液体
        assertFalse(ExcavationRules.canBreakAround(false, true, 3.5f, 3.5f, true)); // かまど
        assertFalse(ExcavationRules.canBreakAround(false, false, 0.5f, 1.5f, false)); // ツルハシで土
    }

    @Test
    void durabilityAtTheThresholdStillExcavates() {
        assertTrue(ExcavationRules.hasEnoughDurability(10, 10));
        assertTrue(ExcavationRules.hasEnoughDurability(500, 10));
        assertFalse(ExcavationRules.hasEnoughDurability(9, 10));
        assertFalse(ExcavationRules.hasEnoughDurability(0, 10));
    }

    @Test
    void toolsWithoutDurabilityAlwaysExcavate() {
        assertTrue(ExcavationRules.hasEnoughDurability(null, 10));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :test --tests org.craftcore.stellaria.enchants.ExcavationRulesTest`
Expected: コンパイルエラー（`ExcavationRules` が無い）

- [ ] **Step 3: 実装する**

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 範囲破壊の判定ロジック。殴った面に垂直な 3x3 の平面のうち、中心を除いた 8 マスを求める。
 * 周囲のブロックは「適正道具で掘れて、中心以下の硬さで、容器ではない」ものだけ壊す。
 */
public final class ExcavationRules {

    public record Offset(int dx, int dy, int dz) {
    }

    private ExcavationRules() {
    }

    public static List<Offset> offsets(BlockFace face) {
        List<Offset> result = new ArrayList<>(8);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) {
                    continue;
                }
                switch (face) {
                    case UP, DOWN -> result.add(new Offset(a, 0, b));
                    case NORTH, SOUTH -> result.add(new Offset(a, b, 0));
                    case EAST, WEST -> result.add(new Offset(0, b, a));
                    default -> {
                        return List.of();
                    }
                }
            }
        }
        return result;
    }

    public static boolean canBreakAround(boolean airOrLiquid, boolean preferredTool,
                                         float hardness, float centerHardness, boolean container) {
        return !airOrLiquid
                && preferredTool
                && hardness >= 0
                && hardness <= centerHardness
                && !container;
    }

    /** remaining が null（耐久値の無い道具）なら常に続ける。 */
    public static boolean hasEnoughDurability(@Nullable Integer remaining, int minDurability) {
        return remaining == null || remaining >= minDurability;
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew :test --tests org.craftcore.stellaria.enchants.ExcavationRulesTest`
Expected: PASS（9 件）

- [ ] **Step 5: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants/ExcavationRules.java src/test/java/org/craftcore/stellaria/enchants/ExcavationRulesTest.java
git commit -m "feat(enchants): 範囲破壊の対象範囲と破壊条件の判定を追加する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 範囲破壊の実行（ExcavationListener）

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchant.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantConfig.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/ExcavationListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`
- Modify: `src/main/resources/config.yml`（`custom-enchants:` の末尾）
- Modify: `src/main/resources/messages.yml`（`custom-enchants:` の `angler_daily_cap` の次）

**Interfaces:**
- Consumes: Task 1 の `EnchantKeys.EXCAVATION`、Task 2 の `ExcavationRules.offsets / canBreakAround / hasEnoughDurability / Offset`
- Produces:
  - `CustomEnchant.EXCAVATION`
  - `CustomEnchantConfig#excavationMinDurability() : int`
  - `new ExcavationListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config)`

- [ ] **Step 1: enum・設定・メッセージを追加する**

`CustomEnchant.java` — `ANGLER(EnchantKeys.ANGLER),` の後に追加する。

```java
    ANGLER(EnchantKeys.ANGLER),
    EXCAVATION(EnchantKeys.EXCAVATION);
```

`CustomEnchantConfig.java` — フィールド、`reload()`、ゲッターを追加する。

```java
    private volatile int excavationMinDurability;
```

```java
        excavationMinDurability = intAtLeast("excavation.min-durability", 10, 0);
```

```java
    public int excavationMinDurability() { return excavationMinDurability; }
```

`config.yml` — `custom-enchants:` の `angler:` ブロックの後に追加する。

```yaml
  excavation:
    min-durability: 10          # 道具の残り耐久値がこの値を下回ると範囲破壊を止め、中心の1ブロックだけ掘る
```

`messages.yml` — `angler_daily_cap` の次の行に追加する。

```yaml
  excavation_low_durability: "&%c道具の耐久値が残りわずかのため、範囲破壊を止めました。"
```

- [ ] **Step 2: 既存テストが enum 追加で壊れていないことを確認する**

Run: `./gradlew :test --tests org.craftcore.stellaria.enchants.CustomEnchantRegistryTest`
Expected: PASS（`CustomEnchant.values().length` を使っているため件数変更の影響を受けない）

- [ ] **Step 3: リスナーを実装する**

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.RayTraceResult;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.enchants.ExcavationRules.Offset;
import org.craftcore.stellaria.utils.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 範囲破壊: 殴った面に垂直な 3x3 の範囲をまとめて掘る。周囲のブロックは player.breakBlock() で壊すため、
 * 土地保護・/mine の鉱石報酬・自動精錬・耐久消費が通常の破壊と同じく適用される。
 * breakBlock() は BlockBreakEvent を再び発火させるので、処理中のプレイヤーを excavating で除外して連鎖を防ぐ。
 * 土地保護（LOW）・ロビー保護（NORMAL）がキャンセルした破壊では動かないよう HIGH で受ける。
 */
public final class ExcavationListener implements Listener {

    private static final double DEFAULT_REACH = 4.5;

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final Set<UUID> excavating = new HashSet<>();

    public ExcavationListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (excavating.contains(uuid)
                || registry.level(player.getInventory().getItemInMainHand(), CustomEnchant.EXCAVATION) <= 0
                || player.isSneaking()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Block center = event.getBlock();
        BlockFace face = hitFace(player, center);
        if (face == null) {
            return; // 面を特定できないときは誤った方向に掘らないよう中心だけにする
        }
        float centerHardness = center.getType().getHardness();

        excavating.add(uuid);
        try {
            for (Offset offset : ExcavationRules.offsets(face)) {
                ItemStack tool = player.getInventory().getItemInMainHand();
                if (registry.level(tool, CustomEnchant.EXCAVATION) <= 0) {
                    return;
                }
                Block target = center.getRelative(offset.dx(), offset.dy(), offset.dz());
                if (!canBreak(target, tool, centerHardness)) {
                    continue;
                }
                if (!ExcavationRules.hasEnoughDurability(remainingDurability(tool), config.excavationMinDurability())) {
                    warnLowDurability(player);
                    return;
                }
                player.breakBlock(target);
            }
        } finally {
            excavating.remove(uuid);
        }
    }

    private static @Nullable BlockFace hitFace(Player player, Block center) {
        AttributeInstance reach = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        double distance = (reach == null ? DEFAULT_REACH : reach.getValue()) + 1.0;
        RayTraceResult hit = player.rayTraceBlocks(distance, FluidCollisionMode.NEVER);
        if (hit == null || !center.equals(hit.getHitBlock())) {
            return null;
        }
        return hit.getHitBlockFace();
    }

    private static boolean canBreak(Block target, ItemStack tool, float centerHardness) {
        Material type = target.getType();
        return ExcavationRules.canBreakAround(
                type.isAir() || target.isLiquid(),
                target.isPreferredTool(tool),
                type.getHardness(),
                centerHardness,
                target.getState(false) instanceof Container);
    }

    /** 残り耐久値。耐久値の無い道具（Unbreakable など）は null。 */
    private static @Nullable Integer remainingDurability(ItemStack tool) {
        if (!(tool.getItemMeta() instanceof Damageable damageable) || damageable.isUnbreakable()) {
            return null;
        }
        int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : tool.getType().getMaxDurability();
        if (max <= 0) {
            return null;
        }
        return max - damageable.getDamage();
    }

    private void warnLowDurability(Player player) {
        String message = plugin.getConfigManager().getMessage("custom-enchants.excavation_low_durability", player);
        plugin.getActionBarManager().flash(player, "excavation", ColorUtil.component(message), 60L);
    }
}
```

注: 耐久値の確認は「壊せるブロックが見つかったとき」だけ行う。周囲に壊せるブロックが無い場合（空中の 1 ブロックを掘ったなど）に警告が出ないようにするため。

- [ ] **Step 4: モジュールに登録する**

`CustomEnchantModule#enable()` の `register(new AnglerListener(plugin, registry, config));` の後に追加する。

```java
        register(new ExcavationListener(plugin, registry, config));
```

- [ ] **Step 5: ビルドとテストが通ることを確認する**

Run: `./gradlew build --no-daemon -q`
Expected: 終了コード 0（非推奨 API の警告は既存のもの）

- [ ] **Step 6: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants src/main/resources/config.yml src/main/resources/messages.yml
git commit -m "feat(enchants): 範囲破壊でツルハシ・シャベルの3x3を掘れるようにする

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 古代都市のチェストでの入手（AncientCityLootListener）

**Files:**
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantRegistry.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantConfig.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/AncientCityLootListener.java`
- Test: `src/test/java/org/craftcore/stellaria/enchants/AncientCityLootListenerTest.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`
- Modify: `src/main/resources/config.yml`（`custom-enchants.excavation`）

**Interfaces:**
- Consumes: Task 3 の `CustomEnchant.EXCAVATION`、`CustomEnchantConfig`
- Produces:
  - `CustomEnchantRegistry#enchantment(CustomEnchant) : @Nullable Enchantment`
  - `CustomEnchantConfig#excavationAncientCityChance() : double`
  - `static boolean AncientCityLootListener.shouldAddBook(NamespacedKey lootTable, double chance, double roll)`
  - `new AncientCityLootListener(CustomEnchantRegistry registry, CustomEnchantConfig config)`

- [ ] **Step 1: 失敗するテストを書く**

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AncientCityLootListenerTest {

    private static final NamespacedKey ANCIENT_CITY = NamespacedKey.minecraft("chests/ancient_city");

    @Test
    void addsTheBookWhenTheRollIsBelowTheChance() {
        assertTrue(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 0.10, 0.05));
        assertFalse(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 0.10, 0.10));
        assertFalse(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 0.10, 0.50));
    }

    @Test
    void chanceZeroNeverAddsAndChanceOneAlwaysAdds() {
        assertFalse(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 0.0, 0.0));
        assertTrue(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 1.0, 0.999));
    }

    @Test
    void otherLootTablesIncludingTheIceBoxNeverAddTheBook() {
        assertFalse(AncientCityLootListener.shouldAddBook(NamespacedKey.minecraft("chests/ancient_city_ice_box"), 1.0, 0.0));
        assertFalse(AncientCityLootListener.shouldAddBook(NamespacedKey.minecraft("chests/stronghold_library"), 1.0, 0.0));
        assertFalse(AncientCityLootListener.shouldAddBook(NamespacedKey.minecraft("chests/end_city_treasure"), 1.0, 0.0));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :test --tests org.craftcore.stellaria.enchants.AncientCityLootListenerTest`
Expected: コンパイルエラー（`AncientCityLootListener` が無い）

- [ ] **Step 3: レジストリと設定を拡張する**

`CustomEnchantRegistry.java` — `level(...)` の前に追加する。

```java
    /** 登録済みのエンチャント本体。無効なら null。 */
    public @Nullable Enchantment enchantment(CustomEnchant enchant) {
        return resolved.get(enchant);
    }
```

`CustomEnchantConfig.java`:

```java
    private volatile double excavationAncientCityChance;
```

```java
        excavationAncientCityChance = chance("excavation.ancient-city-chance", 0.10);
```

```java
    public double excavationAncientCityChance() { return excavationAncientCityChance; }
```

`config.yml` — Task 3 で追加した `excavation:` ブロックを次の形にする。

```yaml
  excavation:
    ancient-city-chance: 0.10   # 古代都市のチェスト1個ごとに、範囲破壊の本が入る確率（0.0〜1.0）
    min-durability: 10          # 道具の残り耐久値がこの値を下回ると範囲破壊を止め、中心の1ブロックだけ掘る
```

- [ ] **Step 4: リスナーを実装する**

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 範囲破壊の入手手段。範囲破壊はエンチャントテーブル・取引に出ない（StellariaEnchants 側で treasure 扱い）ため、
 * 古代都市のチェストが生成されたときだけ、設定した確率で範囲破壊の本を 1 冊加える。氷室のチェストは対象外。
 */
public final class AncientCityLootListener implements Listener {

    private static final NamespacedKey ANCIENT_CITY = NamespacedKey.minecraft("chests/ancient_city");

    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;

    public AncientCityLootListener(CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.registry = registry;
        this.config = config;
    }

    static boolean shouldAddBook(NamespacedKey lootTable, double chance, double roll) {
        return ANCIENT_CITY.equals(lootTable) && roll < chance;
    }

    @EventHandler(ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        Enchantment excavation = registry.enchantment(CustomEnchant.EXCAVATION);
        if (excavation == null
                || !shouldAddBook(event.getLootTable().getKey(), config.excavationAncientCityChance(),
                        ThreadLocalRandom.current().nextDouble())) {
            return;
        }
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, meta -> meta.addStoredEnchant(excavation, 1, true));
        event.getLoot().add(book);
    }
}
```

- [ ] **Step 5: モジュールに登録する**

`CustomEnchantModule#enable()` の `register(new ExcavationListener(plugin, registry, config));` の後に追加する。

```java
        register(new AncientCityLootListener(registry, config));
```

- [ ] **Step 6: テストとビルドが通ることを確認する**

Run: `./gradlew :test --tests org.craftcore.stellaria.enchants.AncientCityLootListenerTest`
Expected: PASS（3 件）

Run: `./gradlew build --no-daemon -q`
Expected: 終了コード 0

- [ ] **Step 7: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants src/test/java/org/craftcore/stellaria/enchants/AncientCityLootListenerTest.java src/main/resources/config.yml
git commit -m "feat(enchants): 古代都市のチェストから範囲破壊の本が出るようにする

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: ドキュメント更新と実機確認

**Files:**
- Modify: `CLAUDE.md`（「Custom enchantments are split across two plugins」の段落）

**Interfaces:**
- Consumes: Task 1〜4 のすべて
- Produces: なし

- [ ] **Step 1: CLAUDE.md を更新する**

「Custom enchantments are split across two plugins…」の段落で、次の 2 か所を直す。

- `registers 10 enchantments via RegistryEvents.ENCHANTMENT and adds them to #minecraft:in_enchanting_table/#minecraft:tradeable` を、次の文に置き換える。
  `registers 11 enchantments via RegistryEvents.ENCHANTMENT and adds the non-treasure ones (EnchantDefinitions.discoverable()) to #minecraft:in_enchanting_table/#minecraft:tradeable; treasure ones (currently only excavation, the 3x3 pickaxe/shovel enchant targeting the custom stellaria:enchantable/excavation item tag) are obtainable only through StellariaCore's AncientCityLootListener, which adds a book to minecraft:chests/ancient_city loot at custom-enchants.excavation.ancient-city-chance`
- Pure logic の列挙に `ExcavationRules` を加える。

- [ ] **Step 2: ビルドして実機で確認する**

Run: `./gradlew build --no-daemon -q && ./gradlew runServer`

サーバーに入り、次を確認する（`/give @s minecraft:diamond_pickaxe[minecraft:enchantments={"stellaria:excavation":1}]` で道具を用意する）。

1. 床・天井・東西南北の壁を掘ると、殴った面に垂直な 3x3 が壊れ、範囲が 3x3 を超えない（再入防止の確認）。
2. しゃがみ中は 1 ブロックだけ壊れる。
3. 石を掘ったとき、隣の黒曜石・岩盤・チェスト・かまどは残る。
4. シャベルで土を掘ると 3x3、ツルハシで土を掘ると中心だけ。
5. `/give @s minecraft:diamond_pickaxe[minecraft:damage=1552,minecraft:enchantments={"stellaria:excavation":1}]`（残り 9）で掘ると中心だけ壊れ、アクションバーに警告が出る。
6. エンチャントテーブルで何度かツルハシにエンチャントしても範囲破壊が出ない。
7. `/loot give @s loot minecraft:chests/ancient_city` を繰り返すと、およそ 10 回に 1 回範囲破壊の本が出る。`minecraft:chests/ancient_city_ice_box` では出ない。
8. 土地保護された区画の境界で掘ると、保護された側のブロックは壊れない。

注: `/loot give` でも `LootGenerateEvent` が発火しない場合は、`/locate structure minecraft:ancient_city` で古代都市へ行き、未開封のチェストを開けて確認する。

- [ ] **Step 3: コミットする**

```bash
git add CLAUDE.md
git commit -m "docs: 範囲破壊エンチャントと treasure の扱いを CLAUDE.md に追記する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
