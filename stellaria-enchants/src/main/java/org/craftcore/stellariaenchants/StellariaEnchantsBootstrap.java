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
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys;
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
        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.TAGS.preFlatten(RegistryKey.ITEM).newHandler(event ->
                        event.registrar().setTag(EnchantDefinitions.EXCAVATION_ITEMS, List.of(
                                TagEntry.tagEntry(ItemTypeTagKeys.PICKAXES),
                                TagEntry.tagEntry(ItemTypeTagKeys.SHOVELS)))));

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
                    // treasure のものはテーブル・取引に出さない（入手手段は StellariaCore 側で用意する）
                    List<TagEntry<Enchantment>> entries = EnchantDefinitions.discoverable().stream()
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
