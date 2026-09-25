package org.craftcore.stellaria.enchants;

import org.bukkit.event.Listener;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.enchants.premium.PremiumCropGuardListener;
import org.craftcore.stellaria.enchants.premium.PremiumCropRecipes;
import org.craftcore.stellaria.enchants.premium.PremiumCrops;

/**
 * カスタムエンチャントの効果側の入口。StellariaCore#onEnable から enable()、
 * StellariaCore#reloadFeatureManagers から reload() を呼ぶ。
 * StellariaEnchants が導入されていない場合はリスナーを一切登録しない。
 */
public final class CustomEnchantModule {

    private final StellariaCore plugin;
    private final CustomEnchantConfig config;
    private CustomEnchantRegistry registry;
    private PremiumCrops premiumCrops;

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
        register(new SmeltingListener(registry));
        register(new CombatEnchantListener(plugin, registry, config));
        plugin.getKikoriManager().setFellCompleteHandler(new ReplantHandler(plugin, registry));
        this.premiumCrops = new PremiumCrops(plugin);
        PremiumCropRecipes premiumRecipes = new PremiumCropRecipes(plugin, premiumCrops);
        premiumRecipes.register();
        register(new PremiumCropGuardListener(plugin, premiumCrops, premiumRecipes));
        register(new HarvestListener(registry, config, premiumCrops));
        register(new DoubleJumpListener(registry, config));
        register(new LaunchListener(plugin, registry, config));
        register(new GlideBoostListener(plugin, registry, config));
        register(new AnglerListener(plugin, registry, config));
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

    public PremiumCrops premiumCrops() {
        return premiumCrops;
    }

    private void register(Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }
}
