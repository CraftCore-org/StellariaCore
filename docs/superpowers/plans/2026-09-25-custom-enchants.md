# カスタムエンチャント 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Paper の Registry API で本物のエンチャントとして 10 種類のカスタムエンチャントを登録し、その効果を StellariaCore に実装する。

**Architecture:** 同じリポジトリに Gradle サブプロジェクトを 2 つ追加する。`enchant-keys`（キー定数だけ）と `stellaria-enchants`（`paper-plugin.yml` + bootstrapper による登録専用プラグイン）である。StellariaCore 本体は `plugin.yml` のまま、新パッケージ `org.craftcore.stellaria.enchants` で効果を実装する。本体は起動時にレジストリからキーでエンチャントを引き、見つからなければその機能だけを無効にする。

**Tech Stack:** Java 21, Gradle Kotlin DSL (マルチプロジェクト), Shadow 9.6.1, Paper API 1.21.11-R0.1-SNAPSHOT（Registry API は `@ApiStatus.Experimental`）、JUnit 5.11.4

**Spec:** `docs/superpowers/specs/2026-09-25-custom-enchants-design.md`

## Global Constraints

- Paper API は `1.21.11-R0.1-SNAPSHOT`、Java 21、ソースエンコーディングは UTF-8。
- StellariaCore 本体の `plugin.yml` は変更しない（`paper-plugin.yml` に移行しない）。
- エンチャントの名前空間は `stellaria`。ID は `smelting`, `pursuit`, `replant`, `harvest`, `glide_boost`, `launch`, `lifesteal`, `last_stand`, `double_jump`, `angler` の 10 個。
- 最大レベル: smelting I, pursuit III, replant I, harvest III, glide_boost III, launch II, lifesteal III, last_stand I, double_jump II, angler III。
- プレイヤー向けの文言はすべて `messages.yml` の `custom-enchants.*`、調整値はすべて `config.yml` の `custom-enchants.*` に置く。`plugin.getConfig()` は使わず `ConfigManager` を通す。
- `messages.yml` の色は `&%<char>` パレットで書く（`&%a`, `&%c` など）。太字などの書式コードだけは `&l` のような通常のコードを使う。
- 背水のクールダウン・釣りボーナスの 1 日上限はメモリで管理する（DB 変更なし、再起動でリセット、再ログインではリセットしない）。
- 移動系（二段跳び・跳躍・滑空加速）は満腹度が 6 以下なら発動しない（`min-food-level: 7`）。
- ファイルに書く文章（コメント・コミットメッセージ・ドキュメント）は標準語で書く。
- コミットメッセージの末尾に `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` を付ける。
- バージョン（`build.gradle.kts` の `version`）は手で変更しない。

## Review Focus

1. **上質作物がバニラの用途に流れ込む経路**: 作業台のシフトクリック一括クラフト、クラフター、ホッパー経由のコンポスター、かまど・焚き火、村人の取引画面。どれを通っても上質作物が 1 個分の通常作物として消費されてはいけない。判定の核（`PremiumCraftRules.judge`）は Task 7 の単体テストで固定し、経路ごとの確認は Task 13 の手動チェックリストに入れる。
2. **ビートルートの逆変換と赤色の染料の衝突**: 通常のビートルート 1 個からは今までどおり赤色の染料が作れること。上質ビートルート 1 個からは通常のビートルート 3 個が作れること。Task 7 で「ビートルートには逆変換レシピを登録しない」ことを単体テストで固定する。
3. **追撃の再帰・キルの帰属・ノックバック**: 追撃のダメージで追撃がさらに発動しないこと。追撃で倒したときにプレイヤーのキルとして扱われ、吸命が発動すること。追撃でノックバックが二重にかからないこと。Task 5 の実装で対処し、Task 13 の手動チェックに入れる。
4. **二段跳びとエリトラの競合**: エリトラ着用中は二段跳びを発動させず、バニラの滑空開始を優先する。Task 9 の判定関数 `DoubleJumpRules.canAirJump` の単体テストで固定する。
5. **StellariaEnchants 未導入での起動**: 登録プラグインがなくても本体が例外なく起動し、他の機能が動くこと。Task 3 の `CustomEnchantRegistry` の単体テスト（lookup が常に null を返すケース）で固定する。

---

## ファイル構成

```
settings.gradle.kts                                   (変更) サブプロジェクトを追加
build.gradle.kts                                      (変更) enchant-keys の同梱、run/plugins へのコピー
.github/workflows/version-bump.yml                    (変更) StellariaEnchants の jar もリリースに添付

enchant-keys/
  build.gradle.kts
  src/main/java/org/craftcore/stellaria/enchantkeys/EnchantKeys.java
  src/test/java/org/craftcore/stellaria/enchantkeys/EnchantKeysTest.java

stellaria-enchants/
  build.gradle.kts
  src/main/resources/paper-plugin.yml
  src/main/java/org/craftcore/stellariaenchants/StellariaEnchantsPlugin.java     空の JavaPlugin
  src/main/java/org/craftcore/stellariaenchants/StellariaEnchantsBootstrap.java  登録処理
  src/main/java/org/craftcore/stellariaenchants/EnchantDefinition.java           1 エンチャント分の定義
  src/main/java/org/craftcore/stellariaenchants/EnchantDefinitions.java          10 個の定義一覧
  src/test/java/org/craftcore/stellariaenchants/EnchantDefinitionsTest.java

src/main/java/org/craftcore/stellaria/enchants/
  CustomEnchant.java                 10 個の enum
  CustomEnchantRegistry.java         キー → Enchantment の解決、レベル取得
  CustomEnchantConfig.java           config.yml の custom-enchants.* をキャッシュ
  CustomEnchantModule.java           生成・リスナー登録・リロードをまとめる入口
  EnchantMath.java                   確率・経験値・満腹度などの純粋な計算
  CooldownTracker.java               背水のクールダウン
  SaplingPlanner.java                植樹の苗木の種類と植える位置
  AnglerStreakTracker.java           釣りボーナスの連続回数・上限
  DoubleJumpRules.java               二段跳びの発動可否
  SmeltingListener.java              自動精錬
  CombatEnchantListener.java         追撃・吸命・背水
  ReplantHandler.java                植樹（KikoriManager の伐採完了フック）
  HarvestListener.java               豊穣
  DoubleJumpListener.java            二段跳び
  LaunchListener.java                跳躍
  GlideBoostListener.java            滑空加速
  AnglerListener.java                釣り人の粘り
  premium/PremiumCrops.java          上質作物のアイテム生成・判定
  premium/PremiumCraftRules.java     クラフト可否の純粋な判定
  premium/PremiumCropRecipes.java    変換レシピの登録
  premium/PremiumCropGuardListener.java  上質作物の使用禁止

src/test/java/org/craftcore/stellaria/enchants/
  EnchantMathTest.java, CooldownTrackerTest.java, SaplingPlannerTest.java,
  AnglerStreakTrackerTest.java, DoubleJumpRulesTest.java, CustomEnchantRegistryTest.java
  premium/PremiumCraftRulesTest.java, premium/PremiumCropRecipesTest.java

src/main/java/org/craftcore/stellaria/StellariaCore.java       (変更) モジュールの生成・リロード
src/main/java/org/craftcore/stellaria/managers/KikoriManager.java  (変更) 伐採完了フック
src/main/resources/config.yml                                   (変更) custom-enchants.* を追加
src/main/resources/messages.yml                                 (変更) custom-enchants.* を追加
CLAUDE.md                                                       (変更) アーキテクチャ説明を追記
```

---

### Task 1: Gradle マルチプロジェクト化と enchant-keys

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `.github/workflows/version-bump.yml:94-95`
- Create: `enchant-keys/build.gradle.kts`
- Create: `enchant-keys/src/main/java/org/craftcore/stellaria/enchantkeys/EnchantKeys.java`
- Test: `enchant-keys/src/test/java/org/craftcore/stellaria/enchantkeys/EnchantKeysTest.java`
- Create: `stellaria-enchants/build.gradle.kts`
- Create: `stellaria-enchants/src/main/resources/paper-plugin.yml`
- Create: `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/StellariaEnchantsPlugin.java`
- Create: `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/StellariaEnchantsBootstrap.java`（この Task では中身が空）

**Interfaces:**
- Produces: `EnchantKeys.NAMESPACE`（`"stellaria"`）、各 ID 定数（`EnchantKeys.SMELTING` など）、`EnchantKeys.ALL`（`List<String>`、10 個、上の順）、`EnchantKeys.namespaced(String id)`（`"stellaria:" + id`）。
- Produces: `./gradlew build` の成果物として `build/libs/StellariaCore-<version>.jar` と `stellaria-enchants/build/libs/StellariaEnchants-<version>.jar`。

- [ ] **Step 1: 現状のビルドが通ることを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`（失敗する場合は作業を止めて報告する）

- [ ] **Step 2: サブプロジェクトを登録する**

`settings.gradle.kts` を次の内容にする。

```kotlin
rootProject.name = "StellariaCore"

include("enchant-keys", "stellaria-enchants")
```

- [ ] **Step 3: enchant-keys のビルド設定を作る**

`enchant-keys/build.gradle.kts`:

```kotlin
plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: 失敗するテストを書く**

`enchant-keys/src/test/java/org/craftcore/stellaria/enchantkeys/EnchantKeysTest.java`:

```java
package org.craftcore.stellaria.enchantkeys;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantKeysTest {

    @Test
    void allContainsTenDistinctIds() {
        assertEquals(10, EnchantKeys.ALL.size());
        assertEquals(10, new HashSet<>(EnchantKeys.ALL).size());
    }

    @Test
    void idsAreValidResourceLocationPaths() {
        for (String id : EnchantKeys.ALL) {
            assertTrue(id.matches("[a-z0-9_]+"), id);
        }
    }

    @Test
    void namespacedPrefixesTheStellariaNamespace() {
        assertEquals("stellaria:smelting", EnchantKeys.namespaced(EnchantKeys.SMELTING));
        assertEquals("stellaria", EnchantKeys.NAMESPACE);
    }
}
```

- [ ] **Step 5: テストが失敗することを確認する**

Run: `./gradlew :enchant-keys:test --no-daemon`
Expected: FAIL（`EnchantKeys` が存在しないためコンパイルエラー）

- [ ] **Step 6: EnchantKeys を実装する**

`enchant-keys/src/main/java/org/craftcore/stellaria/enchantkeys/EnchantKeys.java`:

```java
package org.craftcore.stellaria.enchantkeys;

import java.util.List;

/**
 * カスタムエンチャントのキー定数。登録側（StellariaEnchants）と効果側（StellariaCore）の両方に同梱し、
 * 名前のずれを防ぐ。Paper プラグインのクラスを別プラグインから実行時に参照しないよう、共有するのは定数だけにしている。
 */
public final class EnchantKeys {

    public static final String NAMESPACE = "stellaria";

    public static final String SMELTING = "smelting";
    public static final String PURSUIT = "pursuit";
    public static final String REPLANT = "replant";
    public static final String HARVEST = "harvest";
    public static final String GLIDE_BOOST = "glide_boost";
    public static final String LAUNCH = "launch";
    public static final String LIFESTEAL = "lifesteal";
    public static final String LAST_STAND = "last_stand";
    public static final String DOUBLE_JUMP = "double_jump";
    public static final String ANGLER = "angler";

    public static final List<String> ALL = List.of(
            SMELTING, PURSUIT, REPLANT, HARVEST, GLIDE_BOOST,
            LAUNCH, LIFESTEAL, LAST_STAND, DOUBLE_JUMP, ANGLER
    );

    private EnchantKeys() {
    }

    public static String namespaced(String id) {
        return NAMESPACE + ":" + id;
    }
}
```

- [ ] **Step 7: テストが通ることを確認する**

Run: `./gradlew :enchant-keys:test --no-daemon`
Expected: PASS（3 tests）

- [ ] **Step 8: stellaria-enchants の骨組みを作る**

`stellaria-enchants/build.gradle.kts`:

```kotlin
plugins {
    java
    id("com.gradleup.shadow")
}

version = rootProject.version

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation(project(":enchant-keys"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("StellariaEnchants")
    archiveClassifier.set("")
    // StellariaCore 本体にも同じ EnchantKeys が同梱されるため、クラス名の衝突を避けて移設する
    relocate("org.craftcore.stellaria.enchantkeys", "org.craftcore.stellariaenchants.shaded.enchantkeys")
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
```

`stellaria-enchants/src/main/resources/paper-plugin.yml`:

```yaml
name: StellariaEnchants
version: ${version}
main: org.craftcore.stellariaenchants.StellariaEnchantsPlugin
bootstrapper: org.craftcore.stellariaenchants.StellariaEnchantsBootstrap
api-version: '1.21'
description: StellariaCore のカスタムエンチャントを登録するプラグイン
author: Nyaffle, shu4700
```

`stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/StellariaEnchantsPlugin.java`:

```java
package org.craftcore.stellariaenchants;

import org.bukkit.plugin.java.JavaPlugin;

/** paper-plugin.yml が main を必須とするための空のプラグイン。処理はすべて bootstrapper で行う。 */
public final class StellariaEnchantsPlugin extends JavaPlugin {
}
```

`stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/StellariaEnchantsBootstrap.java`:

```java
package org.craftcore.stellariaenchants;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

/** サーバー起動の初期段階でカスタムエンチャントを登録する。登録内容は Task 2 で追加する。 */
public final class StellariaEnchantsBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
    }
}
```

- [ ] **Step 9: 本体に enchant-keys を同梱し、2 つの jar を run/plugins へコピーする**

`build.gradle.kts` の `dependencies { ... }` ブロックの末尾（閉じ括弧の直前）に 1 行追加する。

```kotlin
    implementation(project(":enchant-keys"))
```

`tasks.named("build") { ... }` ブロックを次に置き換える。

```kotlin
tasks.named("build") {
    dependsOn(":stellaria-enchants:shadowJar")
    doLast {
        copy {
            from(tasks.shadowJar.get().archiveFile)
            into("run/plugins")
        }
        copy {
            from(layout.projectDirectory.dir("stellaria-enchants/build/libs")) {
                include("StellariaEnchants-${version}.jar")
            }
            into("run/plugins")
        }
    }
}
```

- [ ] **Step 10: リリースに StellariaEnchants の jar を添付する**

`.github/workflows/version-bump.yml` の `gh release create` の jar 指定行を次のように 2 行にする。

```yaml
          gh release create "v${{ steps.new-version.outputs.version }}" \
            build/libs/StellariaCore-*.jar \
            stellaria-enchants/build/libs/StellariaEnchants-*.jar \
```

- [ ] **Step 11: ビルド全体を確認する**

Run: `./gradlew build --no-daemon && ls build/libs stellaria-enchants/build/libs run/plugins | rg "Stellaria"`
Expected: `BUILD SUCCESSFUL`。`StellariaCore-<version>.jar` と `StellariaEnchants-<version>.jar` が両方の場所に表示される。

Run: `jar tf build/libs/StellariaCore-*.jar | rg enchantkeys; jar tf stellaria-enchants/build/libs/StellariaEnchants-*.jar | rg "enchantkeys|paper-plugin.yml"`
Expected: 本体側は `org/craftcore/stellaria/enchantkeys/EnchantKeys.class`、登録側は `org/craftcore/stellariaenchants/shaded/enchantkeys/EnchantKeys.class` と `paper-plugin.yml`。

- [ ] **Step 12: コミットする**

```bash
git add settings.gradle.kts build.gradle.kts .github/workflows/version-bump.yml enchant-keys stellaria-enchants
git commit -m "build: カスタムエンチャント用のサブプロジェクトを追加

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: StellariaEnchants でエンチャントを登録する

**Files:**
- Create: `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/EnchantDefinition.java`
- Create: `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/EnchantDefinitions.java`
- Modify: `stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/StellariaEnchantsBootstrap.java`
- Test: `stellaria-enchants/src/test/java/org/craftcore/stellariaenchants/EnchantDefinitionsTest.java`

**Interfaces:**
- Consumes: `EnchantKeys.*`（Task 1）
- Produces: サーバー起動後、レジストリに `stellaria:<id>` のエンチャント 10 個が存在する。`#minecraft:in_enchanting_table` と `#minecraft:tradeable` に全 10 個が入る。`stellaria:smelting` は `minecraft:silk_touch` と排他になる。

- [ ] **Step 1: 失敗するテストを書く**

`stellaria-enchants/src/test/java/org/craftcore/stellariaenchants/EnchantDefinitionsTest.java`:

```java
package org.craftcore.stellariaenchants;

import org.craftcore.stellaria.enchantkeys.EnchantKeys;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantDefinitionsTest {

    @Test
    void definesEveryKeyExactlyOnceInOrder() {
        assertEquals(EnchantKeys.ALL, EnchantDefinitions.ALL.stream().map(EnchantDefinition::id).toList());
    }

    @Test
    void maxLevelsMatchTheSpec() {
        Map<String, Integer> levels = EnchantDefinitions.ALL.stream()
                .collect(Collectors.toMap(EnchantDefinition::id, EnchantDefinition::maxLevel));
        assertEquals(Map.of(
                EnchantKeys.SMELTING, 1, EnchantKeys.PURSUIT, 3, EnchantKeys.REPLANT, 1,
                EnchantKeys.HARVEST, 3, EnchantKeys.GLIDE_BOOST, 3, EnchantKeys.LAUNCH, 2,
                EnchantKeys.LIFESTEAL, 3, EnchantKeys.LAST_STAND, 1, EnchantKeys.DOUBLE_JUMP, 2,
                EnchantKeys.ANGLER, 3), levels);
    }

    @Test
    void onlySmeltingIsExclusiveWithSilkTouch() {
        for (EnchantDefinition definition : EnchantDefinitions.ALL) {
            if (definition.id().equals(EnchantKeys.SMELTING)) {
                assertTrue(definition.exclusiveWithSilkTouch());
            } else {
                assertFalse(definition.exclusiveWithSilkTouch(), definition.id());
            }
        }
    }

    @Test
    void weightsAndCostsAreInVanillaRange() {
        for (EnchantDefinition definition : EnchantDefinitions.ALL) {
            assertTrue(definition.weight() >= 1 && definition.weight() <= 1024, definition.id());
            assertTrue(definition.minCostBase() >= 1, definition.id());
            assertTrue(definition.anvilCost() >= 0, definition.id());
        }
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew :stellaria-enchants:test --no-daemon`
Expected: FAIL（`EnchantDefinitions` が存在しないためコンパイルエラー）

- [ ] **Step 3: EnchantDefinition を実装する**

`stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/EnchantDefinition.java`:

```java
package org.craftcore.stellariaenchants;

import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemType;
import org.craftcore.stellaria.enchantkeys.EnchantKeys;
import org.jetbrains.annotations.Nullable;

/**
 * エンチャント 1 種類分の登録内容。対応アイテムはアイテムタグ（itemTag）か単体アイテム（singleItem）のどちらか一方で指定する。
 * 最小コストは「minCostBase + minCostPerLevel × (レベル - 1)」、最大コストは最小コスト + 30。
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
        boolean exclusiveWithSilkTouch
) {

    public TypedKey<Enchantment> typedKey() {
        return TypedKey.create(RegistryKey.ENCHANTMENT, Key.key(EnchantKeys.NAMESPACE, id));
    }
}
```

- [ ] **Step 4: EnchantDefinitions を実装する**

`stellaria-enchants/src/main/java/org/craftcore/stellariaenchants/EnchantDefinitions.java`:

```java
package org.craftcore.stellariaenchants;

import io.papermc.paper.registry.keys.ItemTypeKeys;
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.craftcore.stellaria.enchantkeys.EnchantKeys;

import java.util.List;

/**
 * 10 種類のエンチャントの登録内容。出現重みはバニラの目安（10=よく出る、5=普通、2=珍しい、1=とても珍しい）に合わせる。
 * 剣・斧用は #minecraft:enchantable/sharp_weapon（ダメージ増加と同じ対象）を使う。
 */
@SuppressWarnings("UnstableApiUsage")
public final class EnchantDefinitions {

    public static final List<EnchantDefinition> ALL = List.of(
            new EnchantDefinition(EnchantKeys.SMELTING, "自動精錬",
                    ItemTypeTagKeys.PICKAXES, null, 1, 2, 15, 0, 4, EquipmentSlotGroup.MAINHAND, true),
            new EnchantDefinition(EnchantKeys.PURSUIT, "追撃",
                    ItemTypeTagKeys.ENCHANTABLE_SHARP_WEAPON, null, 3, 5, 10, 10, 2, EquipmentSlotGroup.MAINHAND, false),
            new EnchantDefinition(EnchantKeys.REPLANT, "植樹",
                    ItemTypeTagKeys.AXES, null, 1, 5, 10, 0, 2, EquipmentSlotGroup.MAINHAND, false),
            new EnchantDefinition(EnchantKeys.HARVEST, "豊穣",
                    ItemTypeTagKeys.HOES, null, 3, 5, 10, 10, 2, EquipmentSlotGroup.MAINHAND, false),
            new EnchantDefinition(EnchantKeys.GLIDE_BOOST, "滑空加速",
                    null, ItemTypeKeys.ELYTRA, 3, 2, 15, 10, 4, EquipmentSlotGroup.CHEST, false),
            new EnchantDefinition(EnchantKeys.LAUNCH, "跳躍",
                    null, ItemTypeKeys.ELYTRA, 2, 2, 15, 12, 4, EquipmentSlotGroup.CHEST, false),
            new EnchantDefinition(EnchantKeys.LIFESTEAL, "吸命",
                    ItemTypeTagKeys.ENCHANTABLE_SHARP_WEAPON, null, 3, 2, 15, 10, 4, EquipmentSlotGroup.MAINHAND, false),
            new EnchantDefinition(EnchantKeys.LAST_STAND, "背水",
                    ItemTypeTagKeys.ENCHANTABLE_HEAD_ARMOR, null, 1, 1, 20, 0, 8, EquipmentSlotGroup.HEAD, false),
            new EnchantDefinition(EnchantKeys.DOUBLE_JUMP, "二段跳び",
                    ItemTypeTagKeys.ENCHANTABLE_FOOT_ARMOR, null, 2, 2, 15, 12, 4, EquipmentSlotGroup.FEET, false),
            new EnchantDefinition(EnchantKeys.ANGLER, "釣り人の粘り",
                    ItemTypeTagKeys.ENCHANTABLE_FISHING, null, 3, 5, 10, 10, 2, EquipmentSlotGroup.MAINHAND, false)
    );

    private EnchantDefinitions() {
    }
}
```

- [ ] **Step 5: テストが通ることを確認する**

Run: `./gradlew :stellaria-enchants:test --no-daemon`
Expected: PASS（4 tests）

- [ ] **Step 6: bootstrapper で登録とタグ追加を行う**

`StellariaEnchantsBootstrap.java` を次に置き換える。

```java
package org.craftcore.stellariaenchants;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import io.papermc.paper.registry.event.RegistryComposeEvent;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.EnchantmentKeys;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.registry.set.RegistrySet;
import io.papermc.paper.tag.TagEntry;
import net.kyori.adventure.text.Component;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemType;

import java.util.List;

/**
 * サーバー起動の初期段階でカスタムエンチャントを登録する。登録はこの段階でしか行えないため、
 * 導入・更新時はサーバーの再起動が必要（/reload では反映されない）。
 */
@SuppressWarnings("UnstableApiUsage")
public final class StellariaEnchantsBootstrap implements PluginBootstrap {

    private static final int MAX_COST_SPREAD = 30;

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(RegistryEvents.ENCHANTMENT.compose().newHandler(event -> {
            for (EnchantDefinition definition : EnchantDefinitions.ALL) {
                event.registry().register(definition.typedKey(), builder -> builder
                        .description(Component.text(definition.displayName()))
                        .supportedItems(supportedItems(event, definition))
                        .weight(definition.weight())
                        .maxLevel(definition.maxLevel())
                        .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(
                                definition.minCostBase(), definition.minCostPerLevel()))
                        .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(
                                definition.minCostBase() + MAX_COST_SPREAD, definition.minCostPerLevel()))
                        .anvilCost(definition.anvilCost())
                        .activeSlots(definition.slot())
                        .exclusiveWith(definition.exclusiveWithSilkTouch()
                                ? RegistrySet.keySet(RegistryKey.ENCHANTMENT, EnchantmentKeys.SILK_TOUCH)
                                : RegistrySet.keySet(RegistryKey.ENCHANTMENT)));
            }
        }));

        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.TAGS.preFlatten(RegistryKey.ENCHANTMENT).newHandler(event -> {
                    List<TagEntry<Enchantment>> entries = EnchantDefinitions.ALL.stream()
                            .map(definition -> TagEntry.valueEntry(definition.typedKey()))
                            .toList();
                    event.registrar().addToTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE, entries);
                    event.registrar().addToTag(EnchantmentTagKeys.TRADEABLE, entries);
                }));
    }

    private static RegistryKeySet<ItemType> supportedItems(
            RegistryComposeEvent<Enchantment, EnchantmentRegistryEntry.Builder> event,
            EnchantDefinition definition) {
        if (definition.itemTag() != null) {
            return event.getOrCreateTag(definition.itemTag());
        }
        return RegistrySet.keySet(RegistryKey.ITEM, definition.singleItem());
    }
}
```

- [ ] **Step 7: ビルドする**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: テストサーバーで登録を確認する（手動）**

Run: `./gradlew runServer`（起動後、コンソールで以下を実行）

```
give <自分の名前> diamond_pickaxe
enchant <自分の名前> stellaria:smelting
give <自分の名前> enchanted_book[stored_enchantments={"stellaria:angler":3}]
```

Expected: エラーなく付与され、ツールチップに「自動精錬」「釣り人の粘り III」が表示される。起動ログに StellariaEnchants のエラーが出ない。確認できたらサーバーを `stop` で止める。

- [ ] **Step 9: コミットする**

```bash
git add stellaria-enchants
git commit -m "feat(enchants): 10種類のカスタムエンチャントを登録する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 本体側の土台（enum・レジストリ・設定・計算・モジュール）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchant.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantRegistry.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantConfig.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/EnchantMath.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`
- Modify: `src/main/java/org/craftcore/stellaria/StellariaCore.java`（フィールド、`onEnable` の 383 行目付近、`reloadFeatureManagers()`）
- Modify: `src/main/resources/config.yml`（末尾に追記）
- Modify: `src/main/resources/messages.yml`（末尾に追記）
- Test: `src/test/java/org/craftcore/stellaria/enchants/EnchantMathTest.java`
- Test: `src/test/java/org/craftcore/stellaria/enchants/CustomEnchantRegistryTest.java`

**Interfaces:**
- Consumes: `EnchantKeys`（Task 1）
- Produces:
  - `enum CustomEnchant { SMELTING, PURSUIT, REPLANT, HARVEST, GLIDE_BOOST, LAUNCH, LIFESTEAL, LAST_STAND, DOUBLE_JUMP, ANGLER }`、`String id()`、`NamespacedKey key()`
  - `CustomEnchantRegistry.resolve(Function<NamespacedKey, Enchantment> lookup, Logger logger)`、`CustomEnchantRegistry.fromServer(Logger logger)`、`boolean isAvailable(CustomEnchant)`、`boolean anyAvailable()`、`List<String> missingIds()`、`int level(@Nullable ItemStack item, CustomEnchant enchant)`
  - `EnchantMath.roll(double chance, DoubleSupplier random)`、`EnchantMath.chanceForLevel(double perLevel, int level)`、`EnchantMath.rollSuccesses(int amount, double chance, DoubleSupplier random)`、`EnchantMath.roundExperience(double exp, DoubleSupplier random)`、`EnchantMath.hasEnoughFood(int foodLevel, int minFoodLevel)`、`EnchantMath.perLevel(double[] values, int level)`、`EnchantMath.healedHealth(double current, double max, double amount)`、`EnchantMath.shouldTriggerLastStand(double remainingHealth, double maxHealth, double threshold)`
  - `CustomEnchantConfig` の getter 群（本 Task の Step 8 のコードに列挙）、`void reload()`
  - `CustomEnchantModule(StellariaCore plugin)`、`void enable()`、`void reload()`、`CustomEnchantRegistry registry()`、`CustomEnchantConfig config()`。以降の Task は `enable()` の「// 各エンチャントのリスナー」の下に登録行を追加する。

- [ ] **Step 1: EnchantMath の失敗するテストを書く**

`src/test/java/org/craftcore/stellaria/enchants/EnchantMathTest.java`:

```java
package org.craftcore.stellaria.enchants;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantMathTest {

    private static DoubleSupplier sequence(Double... values) {
        Iterator<Double> iterator = List.of(values).iterator();
        return iterator::next;
    }

    @Test
    void rollSucceedsOnlyBelowChance() {
        assertTrue(EnchantMath.roll(0.3, () -> 0.29));
        assertFalse(EnchantMath.roll(0.3, () -> 0.3));
        assertFalse(EnchantMath.roll(0.0, () -> 0.0));
    }

    @Test
    void chanceForLevelScalesAndClampsToOne() {
        assertEquals(0.3, EnchantMath.chanceForLevel(0.1, 3), 1e-9);
        assertEquals(1.0, EnchantMath.chanceForLevel(0.6, 2), 1e-9);
        assertEquals(0.0, EnchantMath.chanceForLevel(0.1, 0), 1e-9);
    }

    @Test
    void rollSuccessesCountsEachUnitIndependently() {
        assertEquals(2, EnchantMath.rollSuccesses(3, 0.5, sequence(0.1, 0.9, 0.2)));
        assertEquals(0, EnchantMath.rollSuccesses(0, 0.5, sequence()));
    }

    @Test
    void roundExperienceUsesFractionAsChance() {
        assertEquals(2, EnchantMath.roundExperience(2.7, () -> 0.8));
        assertEquals(3, EnchantMath.roundExperience(2.7, () -> 0.6));
        assertEquals(0, EnchantMath.roundExperience(0.0, () -> 0.0));
    }

    @Test
    void foodGateRequiresAtLeastTheMinimum() {
        assertTrue(EnchantMath.hasEnoughFood(7, 7));
        assertFalse(EnchantMath.hasEnoughFood(6, 7));
    }

    @Test
    void perLevelPicksTheLevelEntryAndClampsToTheLast() {
        double[] values = {15, 12, 10};
        assertEquals(15, EnchantMath.perLevel(values, 1));
        assertEquals(10, EnchantMath.perLevel(values, 3));
        assertEquals(10, EnchantMath.perLevel(values, 5));
        assertEquals(15, EnchantMath.perLevel(values, 0));
    }

    @Test
    void healedHealthNeverExceedsMax() {
        assertEquals(14.0, EnchantMath.healedHealth(10.0, 20.0, 4.0));
        assertEquals(20.0, EnchantMath.healedHealth(18.0, 20.0, 6.0));
    }

    @Test
    void lastStandTriggersOnlyWhenAliveAndAtOrBelowThreshold() {
        assertTrue(EnchantMath.shouldTriggerLastStand(6.0, 20.0, 0.3));
        assertFalse(EnchantMath.shouldTriggerLastStand(6.5, 20.0, 0.3));
        assertFalse(EnchantMath.shouldTriggerLastStand(0.0, 20.0, 0.3));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.EnchantMathTest' --no-daemon`
Expected: FAIL（`EnchantMath` が存在しない）

- [ ] **Step 3: EnchantMath を実装する**

`src/main/java/org/craftcore/stellaria/enchants/EnchantMath.java`:

```java
package org.craftcore.stellaria.enchants;

import java.util.function.DoubleSupplier;

/** カスタムエンチャントの確率・回復量などの計算。Bukkit に依存しないため単体テストできる。 */
public final class EnchantMath {

    private EnchantMath() {
    }

    /** random() が chance 未満なら成功。 */
    public static boolean roll(double chance, DoubleSupplier random) {
        return chance > 0 && random.getAsDouble() < chance;
    }

    /** レベルごとの確率。1.0 を超えない。 */
    public static double chanceForLevel(double perLevel, int level) {
        if (level <= 0) {
            return 0.0;
        }
        return Math.min(1.0, perLevel * level);
    }

    /** amount 個それぞれを独立に判定し、成功した個数を返す。 */
    public static int rollSuccesses(int amount, double chance, DoubleSupplier random) {
        int successes = 0;
        for (int i = 0; i < amount; i++) {
            if (roll(chance, random)) {
                successes++;
            }
        }
        return successes;
    }

    /** 小数の経験値を整数にする。小数部分を確率として 1 を足すか決める（かまどと同じ考え方）。 */
    public static int roundExperience(double exp, DoubleSupplier random) {
        if (exp <= 0) {
            return 0;
        }
        int whole = (int) Math.floor(exp);
        double fraction = exp - whole;
        return whole + (roll(fraction, random) ? 1 : 0);
    }

    public static boolean hasEnoughFood(int foodLevel, int minFoodLevel) {
        return foodLevel >= minFoodLevel;
    }

    /** レベル別の値。範囲外のレベルは最も近い端の値を使う。 */
    public static double perLevel(double[] values, int level) {
        int index = Math.max(0, Math.min(values.length - 1, level - 1));
        return values[index];
    }

    public static double healedHealth(double current, double max, double amount) {
        return Math.min(max, current + amount);
    }

    /** ダメージ後も生きていて、残り体力が最大体力×threshold 以下なら発動する。 */
    public static boolean shouldTriggerLastStand(double remainingHealth, double maxHealth, double threshold) {
        return remainingHealth > 0 && remainingHealth <= maxHealth * threshold;
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.EnchantMathTest' --no-daemon`
Expected: PASS（8 tests）

- [ ] **Step 5: CustomEnchant を実装する**

`src/main/java/org/craftcore/stellaria/enchants/CustomEnchant.java`:

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.NamespacedKey;
import org.craftcore.stellaria.enchantkeys.EnchantKeys;

/** StellariaEnchants が登録するカスタムエンチャント。ID は EnchantKeys と共有する。 */
public enum CustomEnchant {
    SMELTING(EnchantKeys.SMELTING),
    PURSUIT(EnchantKeys.PURSUIT),
    REPLANT(EnchantKeys.REPLANT),
    HARVEST(EnchantKeys.HARVEST),
    GLIDE_BOOST(EnchantKeys.GLIDE_BOOST),
    LAUNCH(EnchantKeys.LAUNCH),
    LIFESTEAL(EnchantKeys.LIFESTEAL),
    LAST_STAND(EnchantKeys.LAST_STAND),
    DOUBLE_JUMP(EnchantKeys.DOUBLE_JUMP),
    ANGLER(EnchantKeys.ANGLER);

    private final String id;

    CustomEnchant(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public NamespacedKey key() {
        return new NamespacedKey(EnchantKeys.NAMESPACE, id);
    }
}
```

- [ ] **Step 6: CustomEnchantRegistry の失敗するテストを書く**

`src/test/java/org/craftcore/stellaria/enchants/CustomEnchantRegistryTest.java`:

```java
package org.craftcore.stellaria.enchants;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CustomEnchantRegistryTest {

    @Test
    void everythingIsUnavailableWhenStellariaEnchantsIsMissing() {
        CustomEnchantRegistry registry = CustomEnchantRegistry.resolve(key -> null, Logger.getAnonymousLogger());

        assertFalse(registry.anyAvailable());
        for (CustomEnchant enchant : CustomEnchant.values()) {
            assertFalse(registry.isAvailable(enchant));
            assertEquals(0, registry.level(null, enchant));
        }
        assertEquals(CustomEnchant.values().length, registry.missingIds().size());
    }
}
```

- [ ] **Step 7: CustomEnchantRegistry を実装する**

`src/main/java/org/craftcore/stellaria/enchants/CustomEnchantRegistry.java`:

```java
package org.craftcore.stellaria.enchants;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * StellariaEnchants が登録したエンチャントをキーで引いて保持する。見つからないエンチャントは無効扱いにし、
 * StellariaEnchants が導入されていない場合でも本体の起動を止めない。
 */
public final class CustomEnchantRegistry {

    private final Map<CustomEnchant, Enchantment> resolved = new EnumMap<>(CustomEnchant.class);
    private final List<String> missingIds = new ArrayList<>();

    private CustomEnchantRegistry() {
    }

    public static CustomEnchantRegistry fromServer(Logger logger) {
        Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        return resolve(registry::get, logger);
    }

    static CustomEnchantRegistry resolve(Function<NamespacedKey, Enchantment> lookup, Logger logger) {
        CustomEnchantRegistry result = new CustomEnchantRegistry();
        for (CustomEnchant enchant : CustomEnchant.values()) {
            Enchantment enchantment = lookup.apply(enchant.key());
            if (enchantment == null) {
                result.missingIds.add(enchant.id());
            } else {
                result.resolved.put(enchant, enchantment);
            }
        }
        if (result.resolved.isEmpty()) {
            logger.warning("StellariaEnchants が導入されていないため、カスタムエンチャントを無効化します。");
        } else if (!result.missingIds.isEmpty()) {
            logger.warning("次のカスタムエンチャントが見つからないため無効化します: " + String.join(", ", result.missingIds));
        }
        return result;
    }

    public boolean isAvailable(CustomEnchant enchant) {
        return resolved.containsKey(enchant);
    }

    public boolean anyAvailable() {
        return !resolved.isEmpty();
    }

    public List<String> missingIds() {
        return Collections.unmodifiableList(missingIds);
    }

    /** アイテムに付いているレベル。アイテムが無い・エンチャントが無効なら 0。 */
    public int level(@Nullable ItemStack item, CustomEnchant enchant) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        Enchantment enchantment = resolved.get(enchant);
        return enchantment == null ? 0 : item.getEnchantmentLevel(enchantment);
    }
}
```

- [ ] **Step 8: テストが通ることを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.CustomEnchantRegistryTest' --no-daemon`
Expected: PASS（1 test）

- [ ] **Step 9: config.yml に custom-enchants を追記する**

`src/main/resources/config.yml` の末尾に追記する。

```yaml

# カスタムエンチャント（StellariaEnchants で登録したエンチャントの効果の調整値）
# 最大レベル・対応アイテム・出現しやすさは StellariaEnchants 側の定義で、変更には再起動が必要。
custom-enchants:
  # 移動系（二段跳び・跳躍・滑空加速）が発動できる最低の満腹度。6以下（ダッシュできない値）では発動しない
  movement:
    min-food-level: 7
  pursuit:
    chance-per-level: 0.10      # 追撃の発動確率（レベル×この値）
    delay-ticks: 8              # 本命の攻撃から追撃までの間隔
    damage-multiplier: 0.5      # 元のダメージに対する追撃ダメージの倍率
  lifesteal:
    heal-per-level: 2.0         # 倒したときの回復量（HP、2でハート1個）×レベル
  last-stand:
    health-threshold: 0.3       # 最大体力に対する割合。これ以下になったら発動
    duration-ticks: 200
    speed-amplifier: 1          # 0=移動速度I、1=移動速度II
    strength-amplifier: 0       # 0=攻撃力上昇I
    cooldown-seconds: 900
  harvest:
    chance-per-level: 0.05      # 作物1個ごとに上質作物になる確率（レベル×この値）
  glide-boost:
    interval-seconds: [15, 12, 10]   # レベルI/II/IIIの加速間隔
    strength: 0.6
    exhaustion: 1.5
  launch:
    charge-ticks: 30
    velocity: [1.4, 1.9]        # レベルI/IIの打ち上げの速さ
    exhaustion: 3.0
  double-jump:
    velocity-y: 0.6
    forward: 0.3
    exhaustion: 1.0
  angler:
    streak-window-seconds: 60   # 前回の釣り上げからこの秒数以内なら連続扱い
    base-amount: 5
    max-streak: 10
    level-multipliers: [1.0, 1.5, 2.0]
    daily-cap: 3000             # 1日（日本時間0時リセット）の支払い上限
```

- [ ] **Step 10: messages.yml に custom-enchants を追記する**

`src/main/resources/messages.yml` の末尾に追記する。

```yaml

custom-enchants:
  replant_not_enough_saplings: "&%c苗木が足りないため植樹できませんでした。（必要: %required%本）"
  last_stand_activated: "&%6&l背水！ &%f%seconds%秒間、力がみなぎる！"
  launch_charged: "&%e跳躍の準備完了！ &%fジャンプで発射"
  angler_streak: "&%b連続釣り &%fx%streak% &%a+%amount%"
  angler_daily_cap: "&%7今日の連続釣りボーナスは上限に達しました。"
  premium_crop_blocked: "&%c上質作物はこのままでは使えません。クラフトで通常の作物3個に戻してください。"
  premium-crop:
    name: "&%e上質な%crop%"
    lore:
      - "&%7作業台に1個だけ置くと、通常の作物3個に戻せます。"
    crop-names:
      WHEAT: "小麦"
      CARROT: "ニンジン"
      POTATO: "ジャガイモ"
      BEETROOT: "ビートルート"
      NETHER_WART: "ネザーウォート"
```

- [ ] **Step 11: CustomEnchantConfig を実装する**

`src/main/java/org/craftcore/stellaria/enchants/CustomEnchantConfig.java`:

```java
package org.craftcore.stellaria.enchants;

import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ConfigManager;

import java.util.Arrays;
import java.util.List;

/**
 * config.yml の custom-enchants.* を読み込んでキャッシュする。移動・攻撃のたびに呼ばれるため、
 * RailConfig と同じく ConfigManager への文字列引きを毎回行わない。不正な値は既定値に戻して警告する。
 * /stellariareload 時は CustomEnchantModule#reload() から reload() が呼ばれる。
 */
public final class CustomEnchantConfig {

    private static final String ROOT = "custom-enchants.";

    private final StellariaCore plugin;

    private volatile int movementMinFoodLevel;
    private volatile double pursuitChancePerLevel;
    private volatile int pursuitDelayTicks;
    private volatile double pursuitDamageMultiplier;
    private volatile double lifestealHealPerLevel;
    private volatile double lastStandHealthThreshold;
    private volatile int lastStandDurationTicks;
    private volatile int lastStandSpeedAmplifier;
    private volatile int lastStandStrengthAmplifier;
    private volatile long lastStandCooldownMillis;
    private volatile double harvestChancePerLevel;
    private volatile double[] glideBoostIntervalSeconds;
    private volatile double glideBoostStrength;
    private volatile float glideBoostExhaustion;
    private volatile int launchChargeTicks;
    private volatile double[] launchVelocity;
    private volatile float launchExhaustion;
    private volatile double doubleJumpVelocityY;
    private volatile double doubleJumpForward;
    private volatile float doubleJumpExhaustion;
    private volatile long anglerStreakWindowMillis;
    private volatile long anglerBaseAmount;
    private volatile int anglerMaxStreak;
    private volatile double[] anglerLevelMultipliers;
    private volatile long anglerDailyCap;

    public CustomEnchantConfig(StellariaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        movementMinFoodLevel = intAtLeast("movement.min-food-level", 7, 0);
        pursuitChancePerLevel = chance("pursuit.chance-per-level", 0.10);
        pursuitDelayTicks = intAtLeast("pursuit.delay-ticks", 8, 1);
        pursuitDamageMultiplier = nonNegative("pursuit.damage-multiplier", 0.5);
        lifestealHealPerLevel = nonNegative("lifesteal.heal-per-level", 2.0);
        lastStandHealthThreshold = chance("last-stand.health-threshold", 0.3);
        lastStandDurationTicks = intAtLeast("last-stand.duration-ticks", 200, 1);
        lastStandSpeedAmplifier = intAtLeast("last-stand.speed-amplifier", 1, 0);
        lastStandStrengthAmplifier = intAtLeast("last-stand.strength-amplifier", 0, 0);
        lastStandCooldownMillis = intAtLeast("last-stand.cooldown-seconds", 900, 0) * 1000L;
        harvestChancePerLevel = chance("harvest.chance-per-level", 0.05);
        glideBoostIntervalSeconds = perLevel("glide-boost.interval-seconds", new double[]{15, 12, 10});
        glideBoostStrength = nonNegative("glide-boost.strength", 0.6);
        glideBoostExhaustion = (float) nonNegative("glide-boost.exhaustion", 1.5);
        launchChargeTicks = intAtLeast("launch.charge-ticks", 30, 1);
        launchVelocity = perLevel("launch.velocity", new double[]{1.4, 1.9});
        launchExhaustion = (float) nonNegative("launch.exhaustion", 3.0);
        doubleJumpVelocityY = nonNegative("double-jump.velocity-y", 0.6);
        doubleJumpForward = nonNegative("double-jump.forward", 0.3);
        doubleJumpExhaustion = (float) nonNegative("double-jump.exhaustion", 1.0);
        anglerStreakWindowMillis = intAtLeast("angler.streak-window-seconds", 60, 1) * 1000L;
        anglerBaseAmount = intAtLeast("angler.base-amount", 5, 0);
        anglerMaxStreak = intAtLeast("angler.max-streak", 10, 1);
        anglerLevelMultipliers = perLevel("angler.level-multipliers", new double[]{1.0, 1.5, 2.0});
        anglerDailyCap = intAtLeast("angler.daily-cap", 3000, 0);
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    private void warnInvalid(String path, Object fallback) {
        plugin.getLogger().warning("config.yml の " + ROOT + path + " が不正なため、既定値 " + fallback + " を使います。");
    }

    private double chance(String path, double def) {
        double value = config().getDouble(ROOT + path, def);
        if (value < 0 || value > 1) {
            warnInvalid(path, def);
            return def;
        }
        return value;
    }

    private double nonNegative(String path, double def) {
        double value = config().getDouble(ROOT + path, def);
        if (value < 0) {
            warnInvalid(path, def);
            return def;
        }
        return value;
    }

    private int intAtLeast(String path, int def, int min) {
        int value = config().getInt(ROOT + path, def);
        if (value < min) {
            warnInvalid(path, def);
            return def;
        }
        return value;
    }

    private double[] perLevel(String path, double[] def) {
        List<String> raw = config().getStringList(ROOT + path);
        if (raw.isEmpty()) {
            return def;
        }
        try {
            double[] values = raw.stream().mapToDouble(Double::parseDouble).toArray();
            if (Arrays.stream(values).anyMatch(value -> value < 0)) {
                warnInvalid(path, Arrays.toString(def));
                return def;
            }
            return values;
        } catch (NumberFormatException e) {
            warnInvalid(path, Arrays.toString(def));
            return def;
        }
    }

    public int movementMinFoodLevel() { return movementMinFoodLevel; }
    public double pursuitChancePerLevel() { return pursuitChancePerLevel; }
    public int pursuitDelayTicks() { return pursuitDelayTicks; }
    public double pursuitDamageMultiplier() { return pursuitDamageMultiplier; }
    public double lifestealHealPerLevel() { return lifestealHealPerLevel; }
    public double lastStandHealthThreshold() { return lastStandHealthThreshold; }
    public int lastStandDurationTicks() { return lastStandDurationTicks; }
    public int lastStandSpeedAmplifier() { return lastStandSpeedAmplifier; }
    public int lastStandStrengthAmplifier() { return lastStandStrengthAmplifier; }
    public long lastStandCooldownMillis() { return lastStandCooldownMillis; }
    public double harvestChancePerLevel() { return harvestChancePerLevel; }
    public double[] glideBoostIntervalSeconds() { return glideBoostIntervalSeconds; }
    public double glideBoostStrength() { return glideBoostStrength; }
    public float glideBoostExhaustion() { return glideBoostExhaustion; }
    public int launchChargeTicks() { return launchChargeTicks; }
    public double[] launchVelocity() { return launchVelocity; }
    public float launchExhaustion() { return launchExhaustion; }
    public double doubleJumpVelocityY() { return doubleJumpVelocityY; }
    public double doubleJumpForward() { return doubleJumpForward; }
    public float doubleJumpExhaustion() { return doubleJumpExhaustion; }
    public long anglerStreakWindowMillis() { return anglerStreakWindowMillis; }
    public long anglerBaseAmount() { return anglerBaseAmount; }
    public int anglerMaxStreak() { return anglerMaxStreak; }
    public double[] anglerLevelMultipliers() { return anglerLevelMultipliers; }
    public long anglerDailyCap() { return anglerDailyCap; }
}
```

- [ ] **Step 12: CustomEnchantModule を実装する**

`src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`:

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.event.Listener;
import org.craftcore.stellaria.StellariaCore;

/**
 * カスタムエンチャントの効果側の入口。StellariaCore#onEnable から enable()、
 * StellariaCore#reloadFeatureManagers から reload() を呼ぶ。
 * StellariaEnchants が導入されていない場合はリスナーを一切登録しない。
 */
public final class CustomEnchantModule {

    private final StellariaCore plugin;
    private final CustomEnchantConfig config;
    private CustomEnchantRegistry registry;

    public CustomEnchantModule(StellariaCore plugin) {
        this.plugin = plugin;
        this.config = new CustomEnchantConfig(plugin);
    }

    public void enable() {
        this.registry = CustomEnchantRegistry.fromServer(plugin.getLogger());
        if (!registry.anyAvailable()) {
            return;
        }
        // 各エンチャントのリスナー
    }

    public void reload() {
        config.reload();
    }

    public CustomEnchantRegistry registry() {
        return registry;
    }

    public CustomEnchantConfig config() {
        return config;
    }

    private void register(Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }
}
```

- [ ] **Step 13: StellariaCore に組み込む**

`src/main/java/org/craftcore/stellaria/StellariaCore.java`:

1. import を追加する: `import org.craftcore.stellaria.enchants.CustomEnchantModule;`
2. フィールド宣言（`private ShopManager shopManager;` の下）に追加する: `private CustomEnchantModule customEnchantModule;`
3. `onEnable` の `getServer().getPluginManager().registerEvents(new WorldResetListener(this), this);` の直後に追加する。

```java
        this.customEnchantModule = new CustomEnchantModule(this);
        customEnchantModule.enable();
```

4. `reloadFeatureManagers()` の末尾（`discordBotManager.restartAfterConfigReload();` の後）に追加する: `customEnchantModule.reload();`

- [ ] **Step 14: 全テストとビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`（既存テストも含めて全て PASS）

- [ ] **Step 15: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants src/test/java/org/craftcore/stellaria/enchants src/main/java/org/craftcore/stellaria/StellariaCore.java src/main/resources/config.yml src/main/resources/messages.yml
git commit -m "feat(enchants): カスタムエンチャントの効果側の土台を追加

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 自動精錬（smelting）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/SmeltingListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`（`enable()`）
- Test: `src/test/java/org/craftcore/stellaria/enchants/SmeltingListenerTest.java`

**Interfaces:**
- Consumes: `CustomEnchantRegistry.level(...)`、`EnchantMath.roundExperience(...)`（Task 3）、`OreUtil.isOre(Material)`（既存）
- Produces: `SmeltingListener(CustomEnchantRegistry registry)`、`static boolean isSmeltTarget(Material material)`

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/org/craftcore/stellaria/enchants/SmeltingListenerTest.java`:

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmeltingListenerTest {

    @Test
    void rawOresAndAncientDebrisAreSmeltTargets() {
        assertTrue(SmeltingListener.isSmeltTarget(Material.RAW_IRON));
        assertTrue(SmeltingListener.isSmeltTarget(Material.RAW_GOLD));
        assertTrue(SmeltingListener.isSmeltTarget(Material.RAW_COPPER));
        assertTrue(SmeltingListener.isSmeltTarget(Material.ANCIENT_DEBRIS));
        assertTrue(SmeltingListener.isSmeltTarget(Material.IRON_ORE));
    }

    @Test
    void nonOreDropsAreNotSmeltTargets() {
        assertFalse(SmeltingListener.isSmeltTarget(Material.COBBLESTONE));
        assertFalse(SmeltingListener.isSmeltTarget(Material.COBBLED_DEEPSLATE));
        assertFalse(SmeltingListener.isSmeltTarget(Material.SAND));
        assertFalse(SmeltingListener.isSmeltTarget(Material.DIAMOND));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.SmeltingListenerTest' --no-daemon`
Expected: FAIL（`SmeltingListener` が存在しない）

- [ ] **Step 3: SmeltingListener を実装する**

`src/main/java/org/craftcore/stellaria/enchants/SmeltingListener.java`:

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.craftcore.stellaria.utils.OreUtil;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 自動精錬: 鉱石を掘ったときのドロップを、かまどレシピの精錬結果に置き換える。
 * 幸運で増えた分もドロップに含まれるため、そのまま精錬される。/mine の一括採掘も player.breakBlock() 経由で
 * 同じイベントが発火するため対象になる。シルクタッチとは StellariaEnchants 側で排他にしている。
 */
public final class SmeltingListener implements Listener {

    private final CustomEnchantRegistry registry;
    private final Map<Material, FurnaceRecipe> recipesByInput = new EnumMap<>(Material.class);

    public SmeltingListener(CustomEnchantRegistry registry) {
        this.registry = registry;
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof FurnaceRecipe recipe
                    && recipe.getInputChoice() instanceof RecipeChoice.MaterialChoice choice) {
                for (Material input : choice.getChoices()) {
                    if (isSmeltTarget(input)) {
                        recipesByInput.putIfAbsent(input, recipe);
                    }
                }
            }
        }
    }

    /** 鉱石・原石系だけを精錬の対象にする（丸石→石などは変換しない）。 */
    static boolean isSmeltTarget(Material material) {
        return material == Material.RAW_IRON
                || material == Material.RAW_GOLD
                || material == Material.RAW_COPPER
                || OreUtil.isOre(material);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!OreUtil.isOre(event.getBlockState().getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (registry.level(player.getInventory().getItemInMainHand(), CustomEnchant.SMELTING) <= 0) {
            return;
        }

        double experience = 0;
        for (Item item : event.getItems()) {
            ItemStack drop = item.getItemStack();
            FurnaceRecipe recipe = recipesByInput.get(drop.getType());
            if (recipe == null) {
                continue;
            }
            ItemStack result = recipe.getResult().clone();
            result.setAmount(drop.getAmount() * result.getAmount());
            item.setItemStack(result);
            experience += recipe.getExperience() * drop.getAmount();
        }

        int orbs = EnchantMath.roundExperience(experience, ThreadLocalRandom.current()::nextDouble);
        if (orbs > 0) {
            player.giveExp(orbs);
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.SmeltingListenerTest' --no-daemon`
Expected: PASS（2 tests）

- [ ] **Step 5: モジュールに登録する**

`CustomEnchantModule#enable()` の `// 各エンチャントのリスナー` の下に追加する。

```java
        register(new SmeltingListener(registry));
```

- [ ] **Step 6: ビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants src/test/java/org/craftcore/stellaria/enchants
git commit -m "feat(enchants): 自動精錬を実装する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 戦闘系（追撃・吸命・背水）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/CooldownTracker.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/CombatEnchantListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`（`enable()`）
- Test: `src/test/java/org/craftcore/stellaria/enchants/CooldownTrackerTest.java`

**Interfaces:**
- Consumes: `CustomEnchantRegistry`、`CustomEnchantConfig`、`EnchantMath.roll/chanceForLevel/healedHealth/shouldTriggerLastStand`（Task 3）、`LandManager#isPvpAllowed(Location)`（既存）
- Produces: `CooldownTracker#tryUse(UUID id, long nowMillis, long cooldownMillis)`、`CooldownTracker#remainingMillis(UUID id, long nowMillis)`、`CombatEnchantListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config)`

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/org/craftcore/stellaria/enchants/CooldownTrackerTest.java`:

```java
package org.craftcore.stellaria.enchants;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownTrackerTest {

    private final UUID player = UUID.randomUUID();

    @Test
    void firstUseSucceedsAndStartsTheCooldown() {
        CooldownTracker tracker = new CooldownTracker();
        assertTrue(tracker.tryUse(player, 1_000, 900_000));
        assertFalse(tracker.tryUse(player, 1_000 + 899_999, 900_000));
        assertEquals(1, tracker.remainingMillis(player, 1_000 + 899_999));
    }

    @Test
    void useSucceedsAgainOnceTheCooldownHasPassed() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.tryUse(player, 0, 900_000);
        assertTrue(tracker.tryUse(player, 900_000, 900_000));
    }

    @Test
    void playersHaveIndependentCooldowns() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.tryUse(player, 0, 900_000);
        assertTrue(tracker.tryUse(UUID.randomUUID(), 0, 900_000));
        assertEquals(0, tracker.remainingMillis(UUID.randomUUID(), 0));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.CooldownTrackerTest' --no-daemon`
Expected: FAIL（`CooldownTracker` が存在しない）

- [ ] **Step 3: CooldownTracker を実装する**

`src/main/java/org/craftcore/stellaria/enchants/CooldownTracker.java`:

```java
package org.craftcore.stellaria.enchants;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとのクールダウン。メモリのみで管理し、再ログインでは消さない（サーバー再起動でリセットされる）。
 * イベントはメインスレッドからしか呼ばれないため同期はしない。
 */
public final class CooldownTracker {

    private final Map<UUID, Long> readyAtMillis = new HashMap<>();

    /** クールダウン中でなければ使用済みにして true、クールダウン中なら false。 */
    public boolean tryUse(UUID id, long nowMillis, long cooldownMillis) {
        Long readyAt = readyAtMillis.get(id);
        if (readyAt != null && nowMillis < readyAt) {
            return false;
        }
        readyAtMillis.put(id, nowMillis + cooldownMillis);
        return true;
    }

    public long remainingMillis(UUID id, long nowMillis) {
        Long readyAt = readyAtMillis.get(id);
        return readyAt == null ? 0 : Math.max(0, readyAt - nowMillis);
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.CooldownTrackerTest' --no-daemon`
Expected: PASS（3 tests）

- [ ] **Step 5: CombatEnchantListener を実装する**

`src/main/java/org/craftcore/stellaria/enchants/CombatEnchantListener.java`:

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 戦闘系のカスタムエンチャント（追撃・吸命・背水）。 */
public final class CombatEnchantListener implements Listener {

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final CooldownTracker lastStandCooldowns = new CooldownTracker();
    /** 追撃ダメージを与えている最中の対象。追撃のダメージから追撃が再発動しないようにする。 */
    private final Set<UUID> pursuitInFlight = new HashSet<>();

    public CombatEnchantListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    // ------------------------------------------------------------------
    // 追撃: モンスターへの近接攻撃で、確率で少し遅れて追加ダメージを与える
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target) || !(target instanceof Enemy)) {
            return;
        }
        // なぎ払い（ENTITY_SWEEP_ATTACK）や追撃自身のダメージでは発動させない
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || pursuitInFlight.contains(target.getUniqueId())) {
            return;
        }
        int level = registry.level(player.getInventory().getItemInMainHand(), CustomEnchant.PURSUIT);
        if (level <= 0) {
            return;
        }
        double chance = EnchantMath.chanceForLevel(config.pursuitChancePerLevel(), level);
        if (!EnchantMath.roll(chance, ThreadLocalRandom.current()::nextDouble)) {
            return;
        }
        double damage = event.getDamage() * config.pursuitDamageMultiplier();
        UUID playerId = player.getUniqueId();
        target.getScheduler().runDelayed(plugin, task -> strikePursuit(target, playerId, damage), null,
                config.pursuitDelayTicks());
    }

    private void strikePursuit(LivingEntity target, UUID playerId, double damage) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || target.isDead() || !target.isValid()) {
            return;
        }
        // 無敵時間を無視し、ノックバックは付けない（元の速度に戻す）。source にプレイヤーを渡してキルの帰属を保つ
        Vector velocity = target.getVelocity();
        target.setNoDamageTicks(0);
        pursuitInFlight.add(target.getUniqueId());
        try {
            target.damage(damage, player);
        } finally {
            pursuitInFlight.remove(target.getUniqueId());
        }
        target.setVelocity(velocity);
        target.getWorld().spawnParticle(Particle.CRIT,
                target.getLocation().add(0, target.getHeight() / 2, 0), 12, 0.3, 0.3, 0.3, 0.1);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.4f);
    }

    // ------------------------------------------------------------------
    // 吸命: 倒したときに回復する（PvP でも有効）
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.isDead()) {
            return;
        }
        int level = registry.level(killer.getInventory().getItemInMainHand(), CustomEnchant.LIFESTEAL);
        if (level <= 0) {
            return;
        }
        double healed = EnchantMath.healedHealth(killer.getHealth(), maxHealth(killer),
                config.lifestealHealPerLevel() * level);
        killer.setHealth(healed);
        killer.getWorld().spawnParticle(Particle.HEART, killer.getLocation().add(0, 2, 0), 3, 0.3, 0.2, 0.3, 0);
    }

    // ------------------------------------------------------------------
    // 背水: 体力が閾値以下になったら一定時間強化。PvP 可能な場所では発動しない
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (registry.level(player.getInventory().getHelmet(), CustomEnchant.LAST_STAND) <= 0) {
            return;
        }
        double remaining = player.getHealth() - event.getFinalDamage();
        if (!EnchantMath.shouldTriggerLastStand(remaining, maxHealth(player), config.lastStandHealthThreshold())) {
            return;
        }
        if (isPvpZone(player)) {
            return;
        }
        if (!lastStandCooldowns.tryUse(player.getUniqueId(), System.currentTimeMillis(),
                config.lastStandCooldownMillis())) {
            return;
        }
        int duration = config.lastStandDurationTicks();
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, config.lastStandSpeedAmplifier()));
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, duration, config.lastStandStrengthAmplifier()));
        player.sendMessage(plugin.getConfigManager().getMessage("custom-enchants.last_stand_activated", player)
                .replace("%seconds%", String.valueOf(duration / 20)));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.6f, 1.2f);
    }

    /** ワールドの PvP が有効で、かつ土地のルールでも PvP が許可されている場所。 */
    private boolean isPvpZone(Player player) {
        return player.getWorld().getPVP() && plugin.getLandManager().isPvpAllowed(player.getLocation());
    }

    private static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }
}
```

- [ ] **Step 6: モジュールに登録する**

`CustomEnchantModule#enable()` の登録行の下に追加する。

```java
        register(new CombatEnchantListener(plugin, registry, config));
```

- [ ] **Step 7: ビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants src/test/java/org/craftcore/stellaria/enchants
git commit -m "feat(enchants): 追撃・吸命・背水を実装する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 植樹（replant）と KikoriManager の伐採完了フック

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/SaplingPlanner.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/ReplantHandler.java`
- Modify: `src/main/java/org/craftcore/stellaria/managers/KikoriManager.java`（`tryStartFelling`、`startFellTask`、フック追加）
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`（`enable()`）
- Test: `src/test/java/org/craftcore/stellaria/enchants/SaplingPlannerTest.java`

**Interfaces:**
- Consumes: `CustomEnchantRegistry`（Task 3）
- Produces:
  - `KikoriManager.FellCompleteHandler`（`void onFellComplete(Player player, ItemStack axe, Material logType, List<Block> roots)`）、`KikoriManager#setFellCompleteHandler(FellCompleteHandler handler)`
  - `SaplingPlanner.Pos(int x, int y, int z)`、`SaplingPlanner.saplingFor(String logMaterialName)`（見つからなければ null）、`SaplingPlanner.plan(List<Pos> roots)`
  - `ReplantHandler(StellariaCore plugin, CustomEnchantRegistry registry)`（`FellCompleteHandler` を実装）

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/org/craftcore/stellaria/enchants/SaplingPlannerTest.java`:

```java
package org.craftcore.stellaria.enchants;

import org.craftcore.stellaria.enchants.SaplingPlanner.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaplingPlannerTest {

    @Test
    void mapsLogsAndWoodToTheirSapling() {
        assertEquals("OAK_SAPLING", SaplingPlanner.saplingFor("OAK_LOG"));
        assertEquals("DARK_OAK_SAPLING", SaplingPlanner.saplingFor("DARK_OAK_LOG"));
        assertEquals("PALE_OAK_SAPLING", SaplingPlanner.saplingFor("PALE_OAK_WOOD"));
        assertEquals("CHERRY_SAPLING", SaplingPlanner.saplingFor("CHERRY_LOG"));
        assertEquals("MANGROVE_PROPAGULE", SaplingPlanner.saplingFor("MANGROVE_LOG"));
    }

    @Test
    void returnsNullForUnplantableLogs() {
        assertNull(SaplingPlanner.saplingFor("STRIPPED_OAK_LOG"));
        assertNull(SaplingPlanner.saplingFor("CRIMSON_STEM"));
        assertNull(SaplingPlanner.saplingFor("BAMBOO_BLOCK"));
    }

    @Test
    void singleTrunkPlantsOneSapling() {
        assertEquals(List.of(new Pos(5, 64, 5)), SaplingPlanner.plan(List.of(new Pos(5, 64, 5))));
    }

    @Test
    void twoByTwoTrunkPlantsFourSaplingsRegardlessOfOrder() {
        List<Pos> roots = List.of(new Pos(1, 64, 1), new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(0, 64, 1));
        Set<Pos> planned = new HashSet<>(SaplingPlanner.plan(roots));
        assertEquals(Set.of(new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(0, 64, 1), new Pos(1, 64, 1)), planned);
    }

    @Test
    void incompleteSquareFallsBackToOneSapling() {
        List<Pos> roots = List.of(new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(0, 64, 1));
        assertEquals(List.of(new Pos(0, 64, 0)), SaplingPlanner.plan(roots));
    }

    @Test
    void emptyRootsPlantNothing() {
        assertTrue(SaplingPlanner.plan(List.of()).isEmpty());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.SaplingPlannerTest' --no-daemon`
Expected: FAIL（`SaplingPlanner` が存在しない）

- [ ] **Step 3: SaplingPlanner を実装する**

`src/main/java/org/craftcore/stellaria/enchants/SaplingPlanner.java`:

```java
package org.craftcore.stellaria.enchants;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 植樹で「どの苗木を」「どこに」植えるかを決める。Bukkit に依存しないため単体テストできる。 */
public final class SaplingPlanner {

    public record Pos(int x, int y, int z) {
    }

    private SaplingPlanner() {
    }

    /** 丸太・木の Material 名から苗木の Material 名を返す。植えられない種類（皮むき、ネザーの幹など）は null。 */
    public static String saplingFor(String logMaterialName) {
        String base;
        if (logMaterialName.endsWith("_LOG")) {
            base = logMaterialName.substring(0, logMaterialName.length() - "_LOG".length());
        } else if (logMaterialName.endsWith("_WOOD")) {
            base = logMaterialName.substring(0, logMaterialName.length() - "_WOOD".length());
        } else {
            return null;
        }
        return switch (base) {
            case "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "CHERRY", "PALE_OAK" -> base + "_SAPLING";
            case "MANGROVE" -> "MANGROVE_PROPAGULE";
            default -> null;
        };
    }

    /**
     * roots は伐採前に最下段にあった丸太の座標。2x2 の正方形が含まれていればその 4 点、無ければ先頭の 1 点を返す。
     */
    public static List<Pos> plan(List<Pos> roots) {
        if (roots.isEmpty()) {
            return List.of();
        }
        Set<Pos> set = new HashSet<>(roots);
        for (Pos corner : roots) {
            Pos east = new Pos(corner.x() + 1, corner.y(), corner.z());
            Pos south = new Pos(corner.x(), corner.y(), corner.z() + 1);
            Pos southEast = new Pos(corner.x() + 1, corner.y(), corner.z() + 1);
            if (set.contains(east) && set.contains(south) && set.contains(southEast)) {
                return List.of(corner, east, south, southEast);
            }
        }
        return List.of(roots.get(0));
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.SaplingPlannerTest' --no-daemon`
Expected: PASS（6 tests）

- [ ] **Step 5: KikoriManager に伐採完了フックを追加する**

`src/main/java/org/craftcore/stellaria/managers/KikoriManager.java`:

1. import に `org.bukkit.Material` と `java.util.Collection`（無ければ）を追加する。
2. `private final Set<Block> claimedBlocks = new HashSet<>();` の下に追加する。

```java
    /** 伐採が最後まで終わったときの通知先。植樹エンチャント（CustomEnchantModule）が設定する。 */
    @FunctionalInterface
    public interface FellCompleteHandler {
        void onFellComplete(Player player, ItemStack axe, Material logType, List<Block> roots);
    }

    private FellCompleteHandler fellCompleteHandler = (player, axe, logType, roots) -> { };

    public void setFellCompleteHandler(FellCompleteHandler handler) {
        this.fellCompleteHandler = handler;
    }
```

3. `tryStartFelling` の `int leafRadius = ...` の直前に追加する。

```java
        Material logType = origin.getType();
        List<Block> roots = lowestLayer(collectedLogs);
```

4. `tryStartFelling` の次の部分を置き換える。

置き換え前:

```java
        if (breakQueue.isEmpty()) {
            return; // 起点1本だけの木（隣接丸太も葉も無し） — バニラの単発破壊のみで完結
        }

        claimedBlocks.addAll(breakQueue);
        startFellTask(player, breakQueue);
    }
```

置き換え後:

```java
        if (breakQueue.isEmpty()) {
            // 起点1本だけの木（隣接丸太も葉も無し） — バニラの単発破壊のみで完結。
            // 起点はこのイベントの後にバニラが壊すため、完了通知は1tick後に出す
            ItemStack axe = player.getInventory().getItemInMainHand();
            player.getScheduler().run(plugin,
                    task -> fellCompleteHandler.onFellComplete(player, axe, logType, roots), null);
            return;
        }

        claimedBlocks.addAll(breakQueue);
        startFellTask(player, breakQueue, logType, roots);
    }

    /** 収集した丸太のうち最も低い段にあるもの（木の根元）。 */
    private static List<Block> lowestLayer(Collection<Block> logs) {
        int minY = logs.stream().mapToInt(Block::getY).min().orElse(0);
        return logs.stream().filter(block -> block.getY() == minY).toList();
    }
```

5. `startFellTask` のシグネチャを変える。

```java
    private void startFellTask(Player player, Deque<Block> breakQueue, Material logType, List<Block> roots) {
```

6. `startFellTask` 内のキューが空になったときの処理を置き換える。

置き換え前:

```java
            if (breakQueue.isEmpty()) {
                scheduledTask.cancel();
                removeActiveTask(uuid, taskRef[0]);
            }
```

置き換え後:

```java
            if (breakQueue.isEmpty()) {
                scheduledTask.cancel();
                removeActiveTask(uuid, taskRef[0]);
                fellCompleteHandler.onFellComplete(current, current.getInventory().getItem(axeSlot), logType, roots);
            }
```

（斧が途中で無くなって中断した場合と、切断で中断した場合は通知しない。）

- [ ] **Step 6: ReplantHandler を実装する**

`src/main/java/org/craftcore/stellaria/enchants/ReplantHandler.java`:

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.enchants.SaplingPlanner.Pos;
import org.craftcore.stellaria.managers.KikoriManager;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.List;

/**
 * 植樹: 木こり機能での伐採が終わったとき、根元に同じ種類の苗木をインベントリから消費して植える。
 * 2x2 の木は苗木が 4 本そろっているときだけ植える（ダークオークは 1 本では育たないため）。
 */
public final class ReplantHandler implements KikoriManager.FellCompleteHandler {

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;

    public ReplantHandler(StellariaCore plugin, CustomEnchantRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public void onFellComplete(Player player, ItemStack axe, Material logType, List<Block> roots) {
        if (!player.isOnline() || roots.isEmpty() || registry.level(axe, CustomEnchant.REPLANT) <= 0) {
            return;
        }
        String saplingName = SaplingPlanner.saplingFor(logType.name());
        Material sapling = saplingName == null ? null : Material.matchMaterial(saplingName);
        if (sapling == null) {
            return;
        }

        World world = roots.get(0).getWorld();
        List<Block> targets = SaplingPlanner.plan(roots.stream()
                        .map(block -> new Pos(block.getX(), block.getY(), block.getZ()))
                        .toList())
                .stream()
                .map(pos -> world.getBlockAt(pos.x(), pos.y(), pos.z()))
                .toList();
        for (Block target : targets) {
            if (!target.getType().isAir() || !Tag.DIRT.isTagged(target.getRelative(BlockFace.DOWN).getType())) {
                return;
            }
        }

        int required = targets.size();
        if (!player.getInventory().containsAtLeast(new ItemStack(sapling), required)) {
            String message = plugin.getConfigManager()
                    .getMessage("custom-enchants.replant_not_enough_saplings", player)
                    .replace("%required%", String.valueOf(required));
            plugin.getActionBarManager().flash(player, "replant", ColorUtil.component(message), 60L);
            return;
        }
        player.getInventory().removeItem(new ItemStack(sapling, required));
        for (Block target : targets) {
            target.setType(sapling);
        }
        world.playSound(targets.get(0).getLocation(), Sound.BLOCK_GRASS_PLACE, 1.0f, 1.0f);
    }
}
```

- [ ] **Step 7: モジュールに登録する**

`CustomEnchantModule#enable()` の登録行の下に追加する。

```java
        plugin.getKikoriManager().setFellCompleteHandler(new ReplantHandler(plugin, registry));
```

- [ ] **Step 8: ビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants src/test/java/org/craftcore/stellaria/enchants src/main/java/org/craftcore/stellaria/managers/KikoriManager.java
git commit -m "feat(enchants): 木こり伐採後に苗木を植える植樹を実装する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 上質作物（アイテム・変換レシピ・使用禁止）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/premium/PremiumCrops.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/premium/PremiumCraftRules.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/premium/PremiumCropRecipes.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/premium/PremiumCropGuardListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`（`enable()`、`premiumCrops()` の追加）
- Test: `src/test/java/org/craftcore/stellaria/enchants/premium/PremiumCraftRulesTest.java`
- Test: `src/test/java/org/craftcore/stellaria/enchants/premium/PremiumCropRecipesTest.java`

**Interfaces:**
- Consumes: `ConfigManager#getRawMessage/getMessageList/getMessage`、`ColorUtil.component`（既存）
- Produces:
  - `PremiumCrops.CROPS`（`Set<Material>`: WHEAT, CARROT, POTATO, BEETROOT, NETHER_WART）、`PremiumCrops.CONVERSION_RATIO`（3）、`PremiumCrops(StellariaCore plugin)`、`boolean isPremium(@Nullable ItemStack item)`、`ItemStack create(Material crop, int amount)`
  - `PremiumCraftRules.Verdict { ALLOW, BLOCK, CONVERT_TO_NORMAL }`、`PremiumCraftRules.judge(int premiumSlots, int nonEmptySlots)`
  - `PremiumCropRecipes(StellariaCore plugin, PremiumCrops crops)`、`void register()`、`boolean isToPremium(NamespacedKey key)`、`boolean isToNormal(NamespacedKey key)`、`static boolean hasToNormalRecipe(Material crop)`
  - `PremiumCropGuardListener(StellariaCore plugin, PremiumCrops crops, PremiumCropRecipes recipes)`
  - `CustomEnchantModule#premiumCrops()`（Task 8 が使う）

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/org/craftcore/stellaria/enchants/premium/PremiumCraftRulesTest.java`:

```java
package org.craftcore.stellaria.enchants.premium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PremiumCraftRulesTest {

    @Test
    void recipesWithoutPremiumCropsAreUntouched() {
        assertEquals(PremiumCraftRules.Verdict.ALLOW, PremiumCraftRules.judge(0, 3));
    }

    @Test
    void aLonePremiumCropConvertsBackToNormalCrops() {
        assertEquals(PremiumCraftRules.Verdict.CONVERT_TO_NORMAL, PremiumCraftRules.judge(1, 1));
    }

    @Test
    void premiumCropsMixedIntoAnyOtherRecipeAreBlocked() {
        assertEquals(PremiumCraftRules.Verdict.BLOCK, PremiumCraftRules.judge(1, 2));
        assertEquals(PremiumCraftRules.Verdict.BLOCK, PremiumCraftRules.judge(3, 3));
    }
}
```

`src/test/java/org/craftcore/stellaria/enchants/premium/PremiumCropRecipesTest.java`:

```java
package org.craftcore.stellaria.enchants.premium;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PremiumCropRecipesTest {

    @Test
    void beetrootHasNoToNormalRecipeBecauseItWouldShadowRedDye() {
        assertFalse(PremiumCropRecipes.hasToNormalRecipe(Material.BEETROOT));
    }

    @Test
    void otherCropsHaveAToNormalRecipe() {
        assertTrue(PremiumCropRecipes.hasToNormalRecipe(Material.WHEAT));
        assertTrue(PremiumCropRecipes.hasToNormalRecipe(Material.CARROT));
        assertTrue(PremiumCropRecipes.hasToNormalRecipe(Material.POTATO));
        assertTrue(PremiumCropRecipes.hasToNormalRecipe(Material.NETHER_WART));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.premium.*' --no-daemon`
Expected: FAIL（クラスが存在しない）

- [ ] **Step 3: PremiumCraftRules を実装する**

`src/main/java/org/craftcore/stellaria/enchants/premium/PremiumCraftRules.java`:

```java
package org.craftcore.stellaria.enchants.premium;

/**
 * クラフトグリッドの中身から、上質作物を含むクラフトを許可するかを決める。
 * 上質作物 1 個だけを置いたときは通常作物 3 個への逆変換、それ以外で上質作物が混ざっていれば禁止する。
 */
public final class PremiumCraftRules {

    public enum Verdict {
        ALLOW,
        BLOCK,
        CONVERT_TO_NORMAL
    }

    private PremiumCraftRules() {
    }

    /**
     * @param premiumSlots  上質作物が入っているスロット数
     * @param nonEmptySlots 何かが入っているスロット数
     */
    public static Verdict judge(int premiumSlots, int nonEmptySlots) {
        if (premiumSlots == 0) {
            return Verdict.ALLOW;
        }
        if (premiumSlots == 1 && nonEmptySlots == 1) {
            return Verdict.CONVERT_TO_NORMAL;
        }
        return Verdict.BLOCK;
    }
}
```

- [ ] **Step 4: PremiumCrops を実装する**

`src/main/java/org/craftcore/stellaria/enchants/premium/PremiumCrops.java`:

```java
package org.craftcore.stellaria.enchants.premium;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ConfigManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * 上質作物のアイテム。見た目はバニラの作物と同じ Material に、名前・説明文・エンチャントの輝き・識別用 PDC タグを付けたもの。
 * 判定は PDC タグで行うため、/stellariareload で名前を変えても既存のアイテムは上質作物のまま扱われる。
 */
public final class PremiumCrops {

    public static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT, Material.NETHER_WART);
    public static final int CONVERSION_RATIO = 3;

    private final ConfigManager config;
    private final NamespacedKey markerKey;

    public PremiumCrops(StellariaCore plugin) {
        this.config = plugin.getConfigManager();
        this.markerKey = new NamespacedKey(plugin, "premium_crop");
    }

    public boolean isPremium(@Nullable ItemStack item) {
        return item != null
                && !item.isEmpty()
                && CROPS.contains(item.getType())
                && item.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    public ItemStack create(Material crop, int amount) {
        ItemStack stack = new ItemStack(crop, amount);
        String cropName = config.getRawMessage("custom-enchants.premium-crop.crop-names." + crop.name());
        String name = config.getRawMessage("custom-enchants.premium-crop.name").replace("%crop%", cropName);
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            meta.setEnchantmentGlintOverride(true);
            meta.displayName(ColorUtil.component(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(config.getMessageList("custom-enchants.premium-crop.lore").stream()
                    .map(line -> ColorUtil.component(line).decoration(TextDecoration.ITALIC, false))
                    .toList());
        });
        return stack;
    }
}
```

- [ ] **Step 5: PremiumCropRecipes を実装する**

`src/main/java/org/craftcore/stellaria/enchants/premium/PremiumCropRecipes.java`:

```java
package org.craftcore.stellaria.enchants.premium;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashSet;
import java.util.Set;

/**
 * 上質作物の変換レシピ。
 * - 通常 → 上質: 縦一列に 3 個（横一列だと小麦 3 個のパンと衝突するため）。
 * - 上質 → 通常: 作物 1 個の不定形レシピ。実際の結果は PremiumCropGuardListener が上質作物のときだけ 3 個に差し替え、
 *   通常作物 1 個のときは結果を消す。ビートルートはバニラの「ビートルート → 赤色の染料」と衝突するため登録せず、
 *   赤色の染料のレシピに乗って差し替えだけで逆変換する。
 */
public final class PremiumCropRecipes {

    private final StellariaCore plugin;
    private final PremiumCrops crops;
    private final Set<NamespacedKey> toPremiumKeys = new HashSet<>();
    private final Set<NamespacedKey> toNormalKeys = new HashSet<>();

    public PremiumCropRecipes(StellariaCore plugin, PremiumCrops crops) {
        this.plugin = plugin;
        this.crops = crops;
    }

    static boolean hasToNormalRecipe(Material crop) {
        return crop != Material.BEETROOT;
    }

    public void register() {
        for (Material crop : PremiumCrops.CROPS) {
            String name = crop.name().toLowerCase();

            NamespacedKey toPremiumKey = new NamespacedKey(plugin, "premium_" + name);
            ShapedRecipe toPremium = new ShapedRecipe(toPremiumKey, crops.create(crop, 1));
            toPremium.shape("C", "C", "C");
            toPremium.setIngredient('C', crop);
            Bukkit.removeRecipe(toPremiumKey);
            Bukkit.addRecipe(toPremium);
            toPremiumKeys.add(toPremiumKey);

            if (hasToNormalRecipe(crop)) {
                NamespacedKey toNormalKey = new NamespacedKey(plugin, "unpremium_" + name);
                ShapelessRecipe toNormal = new ShapelessRecipe(toNormalKey,
                        new ItemStack(crop, PremiumCrops.CONVERSION_RATIO));
                toNormal.addIngredient(crop);
                Bukkit.removeRecipe(toNormalKey);
                Bukkit.addRecipe(toNormal);
                toNormalKeys.add(toNormalKey);
            }
        }
    }

    public boolean isToPremium(NamespacedKey key) {
        return toPremiumKeys.contains(key);
    }

    public boolean isToNormal(NamespacedKey key) {
        return toNormalKeys.contains(key);
    }
}
```

- [ ] **Step 6: テストが通ることを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.premium.*' --no-daemon`
Expected: PASS（5 tests）

- [ ] **Step 7: PremiumCropGuardListener を実装する**

`src/main/java/org/craftcore/stellaria/enchants/premium/PremiumCropGuardListener.java`:

```java
package org.craftcore.stellaria.enchants.premium;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.Recipe;
import org.craftcore.stellaria.StellariaCore;

import java.util.EnumSet;
import java.util.Set;

/**
 * 上質作物を変換レシピ以外で使えないようにする。上質作物は見た目がバニラの作物と同じ Material のため、
 * 何もしないとレシピの材料・植え付け・食事・調理・コンポスター・動物や村人への受け渡しに 1 個分として使えてしまう。
 */
public final class PremiumCropGuardListener implements Listener {

    private static final Set<Material> BLOCKED_INTERACT_TARGETS = EnumSet.of(
            Material.COMPOSTER, Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

    private final StellariaCore plugin;
    private final PremiumCrops crops;
    private final PremiumCropRecipes recipes;

    public PremiumCropGuardListener(StellariaCore plugin, PremiumCrops crops, PremiumCropRecipes recipes) {
        this.plugin = plugin;
        this.crops = crops;
        this.recipes = recipes;
    }

    // ------------------------------------------------------------------
    // クラフト（作業台・クラフター）
    // ------------------------------------------------------------------

    /** クラフトの判定結果。blocked=true なら結果を消す。replacement が非 null なら結果をそれに差し替える。 */
    private record CraftOutcome(boolean blocked, ItemStack replacement) {
        static final CraftOutcome UNCHANGED = new CraftOutcome(false, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null) {
            return;
        }
        CraftOutcome outcome = judge(event.getInventory().getMatrix(), recipe);
        if (outcome.blocked()) {
            event.getInventory().setResult(null);
        } else if (outcome.replacement() != null) {
            event.getInventory().setResult(outcome.replacement());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (!(event.getBlock().getState(false) instanceof Crafter crafter)) {
            return;
        }
        CraftOutcome outcome = judge(crafter.getInventory().getContents(), event.getRecipe());
        if (outcome.blocked()) {
            event.setCancelled(true);
        } else if (outcome.replacement() != null) {
            event.setResult(outcome.replacement());
        }
    }

    private CraftOutcome judge(ItemStack[] matrix, Recipe recipe) {
        int premium = 0;
        int nonEmpty = 0;
        ItemStack lone = null;
        for (ItemStack item : matrix) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            nonEmpty++;
            lone = item;
            if (crops.isPremium(item)) {
                premium++;
            }
        }
        NamespacedKey key = recipe instanceof Keyed keyed ? keyed.getKey() : null;
        return switch (PremiumCraftRules.judge(premium, nonEmpty)) {
            case BLOCK -> new CraftOutcome(true, null);
            case CONVERT_TO_NORMAL -> new CraftOutcome(false,
                    new ItemStack(lone.getType(), PremiumCrops.CONVERSION_RATIO));
            case ALLOW -> {
                if (key != null && recipes.isToNormal(key)) {
                    // 通常作物 1 個が逆変換レシピに乗った（1 個 → 3 個の増殖になるため消す）
                    yield new CraftOutcome(true, null);
                }
                if (key != null && recipes.isToPremium(key)) {
                    // 登録時のアイテムではなく、今の messages.yml の名前で作り直す
                    yield new CraftOutcome(false, crops.create(recipe.getResult().getType(), 1));
                }
                yield CraftOutcome.UNCHANGED;
            }
        };
    }

    // ------------------------------------------------------------------
    // 植え付け・食事・調理・コンポスター
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (crops.isPremium(event.getItemInHand())) {
            deny(event, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (crops.isPremium(event.getItem())) {
            deny(event, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (crops.isPremium(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (crops.isPremium(event.getItem())
                && BLOCKED_INTERACT_TARGETS.contains(event.getClickedBlock().getType())) {
            deny(event, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        InventoryType destination = event.getDestination().getType();
        if (destination == InventoryType.COMPOSTER && crops.isPremium(event.getItem())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // 動物・村人への受け渡し
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (crops.isPremium(hand)) {
            deny(event, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player) && crops.isPremium(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory() instanceof MerchantInventory)) {
            return;
        }
        ItemStack hotbar = event.getHotbarButton() >= 0
                ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                : null;
        if (crops.isPremium(event.getCurrentItem()) || crops.isPremium(event.getCursor()) || crops.isPremium(hotbar)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMerchantDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory() instanceof MerchantInventory && crops.isPremium(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    private void deny(Cancellable event, Player player) {
        event.setCancelled(true);
        player.sendMessage(plugin.getConfigManager().getMessage("custom-enchants.premium_crop_blocked", player));
    }
}
```

- [ ] **Step 8: モジュールに組み込む**

`CustomEnchantModule` にフィールドと getter を追加する。

```java
    private PremiumCrops premiumCrops;
```

```java
    public PremiumCrops premiumCrops() {
        return premiumCrops;
    }
```

import に `org.craftcore.stellaria.enchants.premium.PremiumCropGuardListener`、`org.craftcore.stellaria.enchants.premium.PremiumCropRecipes`、`org.craftcore.stellaria.enchants.premium.PremiumCrops` を追加する。

`enable()` の登録行の下に追加する（上質作物は豊穣エンチャントが無効でも、既存アイテムを守るため豊穣の有無に関係なく登録する）。

```java
        this.premiumCrops = new PremiumCrops(plugin);
        PremiumCropRecipes premiumRecipes = new PremiumCropRecipes(plugin, premiumCrops);
        premiumRecipes.register();
        register(new PremiumCropGuardListener(plugin, premiumCrops, premiumRecipes));
```

- [ ] **Step 9: ビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants src/test/java/org/craftcore/stellaria/enchants
git commit -m "feat(enchants): 上質作物のアイテム・変換レシピ・使用禁止を実装する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 豊穣（harvest）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/HarvestListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`（`enable()`）

**Interfaces:**
- Consumes: `PremiumCrops.CROPS / isPremium / create`（Task 7）、`EnchantMath.chanceForLevel / rollSuccesses`（Task 3）
- Produces: `HarvestListener(CustomEnchantRegistry registry, CustomEnchantConfig config, PremiumCrops crops)`

乱数の判定は `EnchantMath.rollSuccesses` のテストで固定済みのため、この Task で新しい単体テストは追加しない（イベント処理は Task 13 の手動確認で見る）。

- [ ] **Step 1: HarvestListener を実装する**

`src/main/java/org/craftcore/stellaria/enchants/HarvestListener.java`:

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.Tag;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.enchants.premium.PremiumCrops;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 豊穣: 成熟した作物をクワで収穫したとき、作物 1 個ごとに確率で上質作物に置き換える。
 * 種（小麦の種・ビートルートの種）は PremiumCrops.CROPS に含まれないため対象外。
 */
public final class HarvestListener implements Listener {

    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final PremiumCrops crops;

    public HarvestListener(CustomEnchantRegistry registry, CustomEnchantConfig config, PremiumCrops crops) {
        this.registry = registry;
        this.config = config;
        this.crops = crops;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!(event.getBlockState().getBlockData() instanceof Ageable ageable)
                || ageable.getAge() < ageable.getMaximumAge()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!Tag.ITEMS_HOES.isTagged(tool.getType())) {
            return;
        }
        int level = registry.level(tool, CustomEnchant.HARVEST);
        if (level <= 0) {
            return;
        }
        double chance = EnchantMath.chanceForLevel(config.harvestChancePerLevel(), level);

        List<ItemStack> premiumDrops = new ArrayList<>();
        for (Item item : event.getItems()) {
            ItemStack drop = item.getItemStack();
            if (!PremiumCrops.CROPS.contains(drop.getType()) || crops.isPremium(drop)) {
                continue;
            }
            int premium = EnchantMath.rollSuccesses(drop.getAmount(), chance, ThreadLocalRandom.current()::nextDouble);
            if (premium == 0) {
                continue;
            }
            premiumDrops.add(crops.create(drop.getType(), premium));
            if (premium == drop.getAmount()) {
                item.setItemStack(premiumDrops.removeLast());
            } else {
                drop.setAmount(drop.getAmount() - premium);
                item.setItemStack(drop);
            }
        }
        for (ItemStack premium : premiumDrops) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), premium);
        }
    }
}
```

- [ ] **Step 2: モジュールに登録する**

`CustomEnchantModule#enable()` の上質作物の登録行の下に追加する。

```java
        register(new HarvestListener(registry, config, premiumCrops));
```

- [ ] **Step 3: ビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants
git commit -m "feat(enchants): 収穫時に上質作物が出る豊穣を実装する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 二段跳び（double_jump）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/DoubleJumpRules.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/DoubleJumpListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`（`enable()`）
- Test: `src/test/java/org/craftcore/stellaria/enchants/DoubleJumpRulesTest.java`

**Interfaces:**
- Consumes: `CustomEnchantRegistry`、`CustomEnchantConfig`、`EnchantMath.hasEnoughFood`（Task 3）
- Produces:
  - `DoubleJumpRules.State(boolean onGround, boolean survivalLike, boolean flying, boolean gliding, boolean inWater, boolean climbing, boolean wearingElytra, int foodLevel)`
  - `DoubleJumpRules.canAirJump(State state, int airJumpsUsed, int level, int minFoodLevel)`
  - `DoubleJumpListener(CustomEnchantRegistry registry, CustomEnchantConfig config)`

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/org/craftcore/stellaria/enchants/DoubleJumpRulesTest.java`:

```java
package org.craftcore.stellaria.enchants;

import org.craftcore.stellaria.enchants.DoubleJumpRules.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleJumpRulesTest {

    private static State airborne() {
        return new State(false, true, false, false, false, false, false, 20);
    }

    @Test
    void levelOneAllowsOneAirJumpAndLevelTwoAllowsTwo() {
        assertTrue(DoubleJumpRules.canAirJump(airborne(), 0, 1, 7));
        assertFalse(DoubleJumpRules.canAirJump(airborne(), 1, 1, 7));
        assertTrue(DoubleJumpRules.canAirJump(airborne(), 1, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(airborne(), 2, 2, 7));
    }

    @Test
    void groundJumpIsLeftToVanilla() {
        State onGround = new State(true, true, false, false, false, false, false, 20);
        assertFalse(DoubleJumpRules.canAirJump(onGround, 0, 2, 7));
    }

    @Test
    void elytraTakesPriorityOverDoubleJump() {
        State wearingElytra = new State(false, true, false, false, false, false, true, 20);
        assertFalse(DoubleJumpRules.canAirJump(wearingElytra, 0, 2, 7));
    }

    @Test
    void blockedWhileFlyingGlidingSwimmingClimbingOrInCreative() {
        assertFalse(DoubleJumpRules.canAirJump(new State(false, false, false, false, false, false, false, 20), 0, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(new State(false, true, true, false, false, false, false, 20), 0, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(new State(false, true, false, true, false, false, false, 20), 0, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(new State(false, true, false, false, true, false, false, 20), 0, 2, 7));
        assertFalse(DoubleJumpRules.canAirJump(new State(false, true, false, false, false, true, false, 20), 0, 2, 7));
    }

    @Test
    void blockedWhenHungry() {
        State hungry = new State(false, true, false, false, false, false, false, 6);
        assertFalse(DoubleJumpRules.canAirJump(hungry, 0, 2, 7));
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.DoubleJumpRulesTest' --no-daemon`
Expected: FAIL（`DoubleJumpRules` が存在しない）

- [ ] **Step 3: DoubleJumpRules を実装する**

`src/main/java/org/craftcore/stellaria/enchants/DoubleJumpRules.java`:

```java
package org.craftcore.stellaria.enchants;

/**
 * 二段跳びを発動できるかの判定。レベル I で空中 1 回（地面を含め合計 2 回）、レベル II で空中 2 回（合計 3 回）。
 * エリトラ着用中は、空中でのジャンプキーがバニラの滑空開始と重なるため発動しない（滑空を優先）。
 */
public final class DoubleJumpRules {

    public record State(
            boolean onGround,
            boolean survivalLike,
            boolean flying,
            boolean gliding,
            boolean inWater,
            boolean climbing,
            boolean wearingElytra,
            int foodLevel
    ) {
    }

    private DoubleJumpRules() {
    }

    public static boolean canAirJump(State state, int airJumpsUsed, int level, int minFoodLevel) {
        return level > 0
                && airJumpsUsed < level
                && !state.onGround()
                && state.survivalLike()
                && !state.flying()
                && !state.gliding()
                && !state.inWater()
                && !state.climbing()
                && !state.wearingElytra()
                && EnchantMath.hasEnoughFood(state.foodLevel(), minFoodLevel);
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.DoubleJumpRulesTest' --no-daemon`
Expected: PASS（5 tests）

- [ ] **Step 5: DoubleJumpListener を実装する**

`src/main/java/org/craftcore/stellaria/enchants/DoubleJumpListener.java`:

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 二段跳び: 空中でジャンプキーを押すともう一度跳ぶ。PlayerInputEvent でキーの押下を直接検知する
 * （setAllowFlight を使う方式は、ログアウト時に飛行許可が保存されて無限飛行になる危険があるため使わない）。
 */
public final class DoubleJumpListener implements Listener {

    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final Map<UUID, Integer> airJumpsUsed = new HashMap<>();

    public DoubleJumpListener(CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.registry = registry;
        this.config = config;
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        if (!event.getInput().isJump()) {
            return;
        }
        Player player = event.getPlayer();
        int level = registry.level(player.getInventory().getBoots(), CustomEnchant.DOUBLE_JUMP);
        if (level <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        int used = airJumpsUsed.getOrDefault(id, 0);
        if (!DoubleJumpRules.canAirJump(stateOf(player), used, level, config.movementMinFoodLevel())) {
            return;
        }
        airJumpsUsed.put(id, used + 1);

        Vector forward = player.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() > 0) {
            forward.normalize().multiply(config.doubleJumpForward());
        }
        player.setVelocity(forward.setY(config.doubleJumpVelocityY()));
        player.setFallDistance(0);
        player.setExhaustion(player.getExhaustion() + config.doubleJumpExhaustion());
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 8, 0.3, 0.05, 0.3, 0.02);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.6f, 1.2f);
    }

    @EventHandler(ignoreCancelled = true)
    @SuppressWarnings("deprecation") // Player#isOnGround はクライアント申告値だが、着地判定の用途には十分
    public void onMove(PlayerMoveEvent event) {
        if (event.getPlayer().isOnGround()) {
            airJumpsUsed.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        airJumpsUsed.remove(event.getPlayer().getUniqueId());
    }

    @SuppressWarnings("deprecation")
    private static DoubleJumpRules.State stateOf(Player player) {
        GameMode mode = player.getGameMode();
        ItemStack chest = player.getInventory().getChestplate();
        return new DoubleJumpRules.State(
                player.isOnGround(),
                mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE,
                player.isFlying(),
                player.isGliding(),
                player.isInWater(),
                player.isClimbing(),
                chest != null && chest.getType() == Material.ELYTRA,
                player.getFoodLevel()
        );
    }
}
```

- [ ] **Step 6: モジュールに登録する**

`CustomEnchantModule#enable()` の登録行の下に追加する。

```java
        register(new DoubleJumpListener(registry, config));
```

- [ ] **Step 7: ビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants src/test/java/org/craftcore/stellaria/enchants
git commit -m "feat(enchants): 二段跳びを実装する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 跳躍（launch）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/LaunchListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`（`enable()`）

**Interfaces:**
- Consumes: `CustomEnchantRegistry`、`CustomEnchantConfig.launchChargeTicks/launchVelocity/launchExhaustion/movementMinFoodLevel`、`EnchantMath.perLevel/hasEnoughFood`（Task 3）
- Produces: `LaunchListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config)`

判定の核は `EnchantMath.perLevel` と `hasEnoughFood` で固定済み。溜めと発射の流れは Task 13 の手動確認で見る。

- [ ] **Step 1: LaunchListener を実装する**

`src/main/java/org/craftcore/stellaria/enchants/LaunchListener.java`:

```java
package org.craftcore.stellaria.enchants;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 跳躍（エリトラ）: 地上でスニークを長押しして溜め、満タンの状態でジャンプキーを押すと打ち上がる。
 * 上昇が止まったら自動で滑空を始める。建築中のスニークで誤発射しないよう、発射にはジャンプキーを必須にしている。
 */
public final class LaunchListener implements Listener {

    private static final int CHARGE_TICK_STEP = 2;
    private static final int GLIDE_WATCH_MAX_TICKS = 60;

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final Map<UUID, ScheduledTask> chargeTasks = new HashMap<>();
    private final Set<UUID> charged = new HashSet<>();

    public LaunchListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    private int level(Player player) {
        return registry.level(player.getInventory().getChestplate(), CustomEnchant.LAUNCH);
    }

    @EventHandler
    @SuppressWarnings("deprecation") // Player#isOnGround はクライアント申告値だが、地上判定の用途には十分
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (event.getInput().isJump() && event.getInput().isSneak() && charged.contains(id)) {
            launch(player);
            return;
        }
        if (event.getInput().isSneak() && !chargeTasks.containsKey(id)
                && player.isOnGround() && !player.isGliding() && level(player) > 0) {
            startCharging(player);
        }
    }

    @SuppressWarnings("deprecation")
    private void startCharging(Player player) {
        UUID id = player.getUniqueId();
        int[] progress = {0};
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!player.getCurrentInput().isSneak() || !player.isOnGround() || level(player) <= 0) {
                stopCharging(id);
                return;
            }
            int chargeTicks = config.launchChargeTicks();
            progress[0] = Math.min(chargeTicks, progress[0] + CHARGE_TICK_STEP);
            double ratio = (double) progress[0] / chargeTicks;
            drawRing(player.getLocation(), 0.3 + ratio * 1.2);
            if (ratio >= 1.0 && charged.add(id)) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.6f);
                plugin.getActionBarManager().flash(player, "launch",
                        ColorUtil.component(plugin.getConfigManager().getMessage("custom-enchants.launch_charged", player)),
                        40L);
            }
        }, () -> stopCharging(id), 1L, CHARGE_TICK_STEP);
        if (task != null) {
            chargeTasks.put(id, task);
        }
    }

    private void stopCharging(UUID id) {
        ScheduledTask task = chargeTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        charged.remove(id);
    }

    private static void drawRing(Location center, double radius) {
        for (int i = 0; i < 16; i++) {
            double angle = 2 * Math.PI * i / 16;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius);
            center.getWorld().spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
        }
    }

    private void launch(Player player) {
        stopCharging(player.getUniqueId());
        int level = level(player);
        if (level <= 0 || !EnchantMath.hasEnoughFood(player.getFoodLevel(), config.movementMinFoodLevel())) {
            return;
        }
        player.setVelocity(new Vector(0, EnchantMath.perLevel(config.launchVelocity(), level), 0));
        player.setExhaustion(player.getExhaustion() + config.launchExhaustion());
        player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 1);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 0.8f);

        int[] waited = {0};
        player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            waited[0]++;
            if (waited[0] > GLIDE_WATCH_MAX_TICKS || player.isGliding() || player.isInWater()) {
                scheduled.cancel();
                return;
            }
            if (player.getVelocity().getY() <= 0 && level(player) > 0) {
                player.setGliding(true);
                scheduled.cancel();
            }
        }, null, 2L, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopCharging(event.getPlayer().getUniqueId());
    }
}
```

- [ ] **Step 2: モジュールに登録する**

`CustomEnchantModule#enable()` の登録行の下に追加する。

```java
        register(new LaunchListener(plugin, registry, config));
```

- [ ] **Step 3: ビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants
git commit -m "feat(enchants): スニークで溜めて打ち上がる跳躍を実装する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 滑空加速（glide_boost）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/GlideBoostListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`（`enable()`）

**Interfaces:**
- Consumes: `CustomEnchantRegistry`、`CustomEnchantConfig.glideBoostIntervalSeconds/glideBoostStrength/glideBoostExhaustion/movementMinFoodLevel`、`EnchantMath.perLevel/hasEnoughFood`（Task 3）
- Produces: `GlideBoostListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config)`

- [ ] **Step 1: GlideBoostListener を実装する**

`src/main/java/org/craftcore/stellaria/enchants/GlideBoostListener.java`:

```java
package org.craftcore.stellaria.enchants;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 滑空加速（エリトラ）: 滑空を始めてから一定間隔ごとに、進行方向へ自動で加速する。
 * 着地・入水などで滑空が終わるとタスクが止まり、次の滑空で間隔のカウントが最初からになる。
 */
public final class GlideBoostListener implements Listener {

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final Map<UUID, ScheduledTask> boostTasks = new HashMap<>();

    public GlideBoostListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        cancel(player.getUniqueId());
        if (!event.isGliding()) {
            return;
        }
        int level = registry.level(player.getInventory().getChestplate(), CustomEnchant.GLIDE_BOOST);
        if (level <= 0) {
            return;
        }
        long intervalTicks = Math.max(1L, Math.round(EnchantMath.perLevel(config.glideBoostIntervalSeconds(), level) * 20));
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!player.isGliding()) {
                cancel(player.getUniqueId());
                return;
            }
            if (!EnchantMath.hasEnoughFood(player.getFoodLevel(), config.movementMinFoodLevel())) {
                return;
            }
            Vector boost = player.getLocation().getDirection().multiply(config.glideBoostStrength());
            player.setVelocity(player.getVelocity().add(boost));
            player.setExhaustion(player.getExhaustion() + config.glideBoostExhaustion());
            player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation(), 10, 0.2, 0.2, 0.2, 0.05);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.3f);
        }, () -> boostTasks.remove(player.getUniqueId()), intervalTicks, intervalTicks);
        if (task != null) {
            boostTasks.put(player.getUniqueId(), task);
        }
    }

    private void cancel(UUID id) {
        ScheduledTask task = boostTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }
}
```

- [ ] **Step 2: モジュールに登録する**

`CustomEnchantModule#enable()` の登録行の下に追加する。

```java
        register(new GlideBoostListener(plugin, registry, config));
```

- [ ] **Step 3: ビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants
git commit -m "feat(enchants): エリトラの滑空加速を実装する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: 釣り人の粘り（angler）

**Files:**
- Create: `src/main/java/org/craftcore/stellaria/enchants/AnglerStreakTracker.java`
- Create: `src/main/java/org/craftcore/stellaria/enchants/AnglerListener.java`
- Modify: `src/main/java/org/craftcore/stellaria/enchants/CustomEnchantModule.java`（`enable()`）
- Test: `src/test/java/org/craftcore/stellaria/enchants/AnglerStreakTrackerTest.java`

**Interfaces:**
- Consumes: `CustomEnchantRegistry`、`CustomEnchantConfig.angler*`、`EnchantMath.perLevel`（Task 3）、`AfkManager#isAfk(UUID)`、`IncomeManager#reward(Player, long)`（既存）
- Produces:
  - `AnglerStreakTracker.Settings(long windowMillis, long baseAmount, int maxStreak, double levelMultiplier, long dailyCap)`
  - `AnglerStreakTracker.Result(int streak, long payout, boolean capJustReached)`
  - `AnglerStreakTracker#recordCatch(UUID id, long nowMillis, LocalDate today, Settings settings)`、`AnglerStreakTracker#resetStreak(UUID id)`
  - `AnglerListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config)`

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/org/craftcore/stellaria/enchants/AnglerStreakTrackerTest.java`:

```java
package org.craftcore.stellaria.enchants;

import org.craftcore.stellaria.enchants.AnglerStreakTracker.Result;
import org.craftcore.stellaria.enchants.AnglerStreakTracker.Settings;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnglerStreakTrackerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);
    private static final Settings SETTINGS = new Settings(60_000, 5, 10, 2.0, 3000);

    private final UUID player = UUID.randomUUID();

    @Test
    void firstCatchStartsAStreakWithoutBonus() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Result result = tracker.recordCatch(player, 0, DAY, SETTINGS);
        assertEquals(0, result.streak());
        assertEquals(0, result.payout());
    }

    @Test
    void catchesWithinTheWindowGrowTheStreakAndPay() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        tracker.recordCatch(player, 0, DAY, SETTINGS);
        Result second = tracker.recordCatch(player, 60_000, DAY, SETTINGS);
        assertEquals(1, second.streak());
        assertEquals(10, second.payout()); // 5 × 1 × 2.0
        Result third = tracker.recordCatch(player, 100_000, DAY, SETTINGS);
        assertEquals(2, third.streak());
        assertEquals(20, third.payout());
    }

    @Test
    void catchOutsideTheWindowResetsTheStreak() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        tracker.recordCatch(player, 0, DAY, SETTINGS);
        tracker.recordCatch(player, 10_000, DAY, SETTINGS);
        Result late = tracker.recordCatch(player, 70_001, DAY, SETTINGS);
        assertEquals(0, late.streak());
        assertEquals(0, late.payout());
    }

    @Test
    void bonusIsCappedAtMaxStreak() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Result last = null;
        for (int i = 0; i <= 15; i++) {
            last = tracker.recordCatch(player, i * 1_000L, DAY, SETTINGS);
        }
        assertEquals(15, last.streak());
        assertEquals(100, last.payout()); // 5 × min(15,10) × 2.0
    }

    @Test
    void dailyCapLimitsPayoutAndReportsReachingItOnce() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Settings smallCap = new Settings(60_000, 5, 10, 2.0, 25);
        tracker.recordCatch(player, 0, DAY, smallCap);
        assertEquals(10, tracker.recordCatch(player, 1_000, DAY, smallCap).payout());
        Result capped = tracker.recordCatch(player, 2_000, DAY, smallCap);
        assertEquals(15, capped.payout()); // 20 のうち残り 15 だけ
        assertTrue(capped.capJustReached());
        Result after = tracker.recordCatch(player, 3_000, DAY, smallCap);
        assertEquals(0, after.payout());
        assertFalse(after.capJustReached());
    }

    @Test
    void newDayResetsThePaidTotal() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Settings smallCap = new Settings(60_000, 5, 10, 2.0, 10);
        tracker.recordCatch(player, 0, DAY, smallCap);
        tracker.recordCatch(player, 1_000, DAY, smallCap);
        assertEquals(10, tracker.recordCatch(player, 2_000, DAY.plusDays(1), smallCap).payout());
    }

    @Test
    void resetStreakKeepsThePaidTotal() {
        AnglerStreakTracker tracker = new AnglerStreakTracker();
        Settings smallCap = new Settings(60_000, 5, 10, 2.0, 10);
        tracker.recordCatch(player, 0, DAY, smallCap);
        tracker.recordCatch(player, 1_000, DAY, smallCap);
        tracker.resetStreak(player);
        assertEquals(0, tracker.recordCatch(player, 2_000, DAY, smallCap).streak());
        assertEquals(0, tracker.recordCatch(player, 3_000, DAY, smallCap).payout());
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.AnglerStreakTrackerTest' --no-daemon`
Expected: FAIL（`AnglerStreakTracker` が存在しない）

- [ ] **Step 3: AnglerStreakTracker を実装する**

`src/main/java/org/craftcore/stellaria/enchants/AnglerStreakTracker.java`:

```java
package org.craftcore.stellaria.enchants;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 釣り人の粘りの連続回数と 1 日の支払い額を管理する。メモリのみで、再ログインでは消さない（再起動でリセット）。
 * 連続回数は最初の 1 匹が 0 で、windowMillis 以内に次を釣るたびに 1 増える。
 */
public final class AnglerStreakTracker {

    public record Settings(long windowMillis, long baseAmount, int maxStreak, double levelMultiplier, long dailyCap) {
    }

    public record Result(int streak, long payout, boolean capJustReached) {
    }

    private record State(long lastCatchMillis, int streak, LocalDate day, long paidToday) {
    }

    private final Map<UUID, State> states = new HashMap<>();

    public Result recordCatch(UUID id, long nowMillis, LocalDate today, Settings settings) {
        State previous = states.get(id);
        long paidBefore = previous != null && previous.day().equals(today) ? previous.paidToday() : 0;
        boolean continues = previous != null
                && previous.lastCatchMillis() >= 0
                && nowMillis - previous.lastCatchMillis() <= settings.windowMillis();
        int streak = continues ? previous.streak() + 1 : 0;

        long raw = Math.round(settings.baseAmount() * Math.min(streak, settings.maxStreak()) * settings.levelMultiplier());
        long payout = Math.max(0, Math.min(raw, settings.dailyCap() - paidBefore));
        long paidAfter = paidBefore + payout;
        boolean capJustReached = paidBefore < settings.dailyCap() && paidAfter >= settings.dailyCap();

        states.put(id, new State(nowMillis, streak, today, paidAfter));
        return new Result(streak, payout, capJustReached);
    }

    /** 連続回数だけを 0 に戻す（その日の支払い額は残す）。 */
    public void resetStreak(UUID id) {
        states.computeIfPresent(id, (key, state) -> new State(-1, 0, state.day(), state.paidToday()));
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `./gradlew test --tests 'org.craftcore.stellaria.enchants.AnglerStreakTrackerTest' --no-daemon`
Expected: PASS（7 tests）

- [ ] **Step 5: AnglerListener を実装する**

`src/main/java/org/craftcore/stellaria/enchants/AnglerListener.java`:

```java
package org.craftcore.stellaria.enchants;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 釣り人の粘り: 続けて釣り上げるほどボーナスが増える。支払いは既存の釣り収入と同じ IncomeManager を通す。
 * AFK 中は支払わず、連続回数も 0 に戻す（放置釣りの対策）。
 */
public final class AnglerListener implements Listener {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final AnglerStreakTracker tracker = new AnglerStreakTracker();

    public AnglerListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item)) {
            return;
        }
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        ItemStack rod = player.getInventory().getItem(hand);
        int level = registry.level(rod, CustomEnchant.ANGLER);
        if (level <= 0) {
            return;
        }
        if (plugin.getAfkManager().isAfk(player.getUniqueId())) {
            tracker.resetStreak(player.getUniqueId());
            return;
        }

        AnglerStreakTracker.Settings settings = new AnglerStreakTracker.Settings(
                config.anglerStreakWindowMillis(),
                config.anglerBaseAmount(),
                config.anglerMaxStreak(),
                EnchantMath.perLevel(config.anglerLevelMultipliers(), level),
                config.anglerDailyCap());
        AnglerStreakTracker.Result result = tracker.recordCatch(
                player.getUniqueId(), System.currentTimeMillis(), LocalDate.now(JAPAN), settings);

        if (result.payout() > 0) {
            plugin.getIncomeManager().reward(player, result.payout());
            String message = plugin.getConfigManager().getMessage("custom-enchants.angler_streak", player)
                    .replace("%streak%", String.valueOf(result.streak()))
                    .replace("%amount%", plugin.getEconomyManager().formatExact(result.payout()));
            plugin.getActionBarManager().flash(player, "angler", ColorUtil.component(message), 60L);
        }
        if (result.capJustReached()) {
            player.sendMessage(plugin.getConfigManager().getMessage("custom-enchants.angler_daily_cap", player));
        }
    }
}
```

（`StellariaCore#getIncomeManager()` と `EconomyManager#formatExact(double)` は既存のメソッドで、`IncomeManager` の収入通知と同じ組み合わせ。）

- [ ] **Step 6: モジュールに登録する**

`CustomEnchantModule#enable()` の登録行の下に追加する。

```java
        register(new AnglerListener(plugin, registry, config));
```

- [ ] **Step 7: ビルドを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: コミットする**

```bash
git add src/main/java/org/craftcore/stellaria/enchants src/test/java/org/craftcore/stellaria/enchants
git commit -m "feat(enchants): 連続釣りでボーナスが出る釣り人の粘りを実装する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: ドキュメント更新と手動の総合確認

**Files:**
- Modify: `CLAUDE.md`（Architecture セクションに段落を追加、Build & run に jar が 2 つになる旨を追記）

**Interfaces:**
- Consumes: Task 1〜12 のすべて

- [ ] **Step 1: CLAUDE.md を更新する**

`## Build & run` の最初の箇条書き（`./gradlew build` の説明）の直後に追加する。

```markdown
- The build is now a multi-project build: `enchant-keys` (shared enchantment key constants, shaded into both jars) and `stellaria-enchants` (a separate Paper plugin with `paper-plugin.yml` + bootstrapper that only registers the custom enchantments). `./gradlew build` produces and copies both `StellariaCore-<version>.jar` and `StellariaEnchants-<version>.jar` into `run/plugins/`, and the release workflow attaches both.
```

`## Architecture` の末尾に段落を追加する。

```markdown
**Custom enchantments are split across two plugins because registering them needs a Paper bootstrapper.** `stellaria-enchants` (Paper plugin) registers 10 enchantments via `RegistryEvents.ENCHANTMENT` and adds them to `#minecraft:in_enchanting_table`/`#minecraft:tradeable`; it has no runtime logic, and changing its definitions requires a full server restart. StellariaCore stays a `plugin.yml` plugin (migrating would break the ~96 `getCommand(...)` registrations) and implements the effects in `enchants/`, wired through `CustomEnchantModule` (`enable()` in `onEnable`, `reload()` in `reloadFeatureManagers()`). `CustomEnchantRegistry` looks the enchantments up by key at enable time and disables whatever is missing, so StellariaCore still boots without StellariaEnchants. Tunables live in `config.yml` `custom-enchants.*` (cached by `CustomEnchantConfig`), text in `messages.yml` `custom-enchants.*`. Pure logic (`EnchantMath`, `CooldownTracker`, `SaplingPlanner`, `AnglerStreakTracker`, `DoubleJumpRules`, `premium/PremiumCraftRules`) is unit-tested. Replant hooks `KikoriManager` through `setFellCompleteHandler(...)`, so it only works for players using the kikori feature. Premium crops (`enchants/premium/`) are vanilla crop materials tagged via PDC; `PremiumCropGuardListener` blocks every non-conversion use (recipes, crafter, planting, eating, cooking, composter, animals/villagers). Removing StellariaEnchants later strips these enchantments from players' items.
```

- [ ] **Step 2: 全体のビルドとテストを確認する**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL`、全テスト PASS

- [ ] **Step 3: テストサーバーで手動確認する**

Run: `./gradlew runServer`（サバイバルモードで確認する。付与はコンソールの `enchant` / `give` を使う）

次のチェックリストを上から順に確認し、結果（OK / NG と NG の内容）を記録する。

1. 起動ログに StellariaEnchants・StellariaCore のエラーや「カスタムエンチャントを無効化します」の警告が出ていない。
2. エンチャントテーブルで本をエンチャントし、カスタムエンチャントが付くことがある（何度か試す）。司書村人の取引候補にカスタムエンチャントの本が出ることがある。
3. シルクタッチ付きツルハシに金床で自動精錬の本を付けようとすると付かない。幸運 III 付きツルハシには付く。
4. 自動精錬＋幸運のツルハシで鉄鉱石を掘ると鉄インゴットが落ち、経験値が入る。`/mine` の一括採掘でも鉄インゴットになる。石は丸石のまま。
5. 追撃 III の剣でゾンビを殴ると、ときどき少し遅れて追加ダメージとクリティカルのエフェクトが出る。ノックバックが二重にならない。追撃でとどめを刺しても経験値とドロップが出る。
6. 吸命 III の剣でモンスターを倒すと体力が回復する。
7. 背水のヘルメットで体力が 30% 以下になると移動速度 II と攻撃力上昇 I が 10 秒付き、15 分以内は再発動しない。PvP が有効な土地では発動しない。
8. 木こり機能を有効にし、植樹の斧でオークを切ると根元にオークの苗木が植わり、インベントリの苗木が 1 本減る。ダークオークは苗木 4 本で 2x2 に植わり、3 本以下だとアクションバーに不足の通知が出る。
9. 豊穣 III のクワで成熟した小麦を収穫すると、ときどき光る「上質な小麦」が落ちる。種は上質にならない。
10. 上質作物の禁止ルール:
    - 上質な小麦 3 個を横一列に置いてもパンが作れない。通常の小麦 3 個を横一列に置くとパンが作れる。
    - 通常の小麦 3 個を縦一列に置くと上質な小麦 1 個が作れる。上質な小麦を 1 個だけ置くと通常の小麦 3 個が作れる（シフトクリックでまとめて作っても、上質な小麦 1 個につき 3 個）。
    - 通常のビートルート 1 個から赤色の染料が作れる。上質なビートルート 1 個からは通常のビートルート 3 個が作れる。
    - 上質なニンジン・ジャガイモ・ネザーウォートを植えられない。上質なニンジンを食べられない。
    - 上質なジャガイモをかまど・焚き火で焼けない。上質作物をコンポスターに手で入れられず、ホッパー経由でも入らない。
    - 上質なニンジンで豚を繁殖させられない。農民の村人の近くに投げても拾われない。取引画面に置けない。
    - クラフターに上質な小麦 3 個と他の材料を入れてパンを作ろうとしても作られない。
11. 二段跳びのブーツ: レベル I は空中で 1 回、レベル II は 2 回跳べる。着地で回数が戻る。落下ダメージが二段目の高さからの分だけになる。満腹度が 6 以下だと発動しない。エリトラを着ているときは空中のジャンプキーで滑空が始まり、二段跳びは発動しない。ログアウトして再ログインしても飛行できない（飛行許可が残っていない）。
12. 跳躍のエリトラ: 地上でスニークを長押しするとパーティクルの輪が広がり、満タンで音とアクションバーの通知が出る。そのままジャンプキーで打ち上がり、上昇が止まると滑空が始まる。満タン前にスニークを離すと何も起きない。スニークしながら普通に歩いても発射されない。
13. 滑空加速のエリトラ: 滑空を始めて 15 秒（レベル I）ごとに加速する。着地すると止まり、次の滑空では最初から数える。満腹度が 6 以下だと加速しない。
14. 釣り人の粘りの釣竿: 1 匹目はボーナスなし、60 秒以内に 2 匹目を釣るとアクションバーに連続回数とボーナスが出る。AFK 状態で釣るとボーナスが出ず、連続回数が戻る。
15. `/stellariareload` 後に `config.yml` の `custom-enchants.pursuit.chance-per-level` を 1.0 にすると、再起動なしで追撃が毎回発動する。
16. StellariaEnchants の jar を `run/plugins` から一時的に外して起動すると、StellariaCore が「StellariaEnchants が導入されていないため…」の警告だけを出して起動し、他のコマンド（`/tpa` など）が動く。確認後に jar を戻す。

NG があれば、該当する Task に戻って修正し、この Step をやり直す。

- [ ] **Step 4: コミットする**

```bash
git add CLAUDE.md
git commit -m "docs: カスタムエンチャントの構成を CLAUDE.md に追記する

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
