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
        register(new SmeltingListener(registry));
        register(new CombatEnchantListener(plugin, registry, config));
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
