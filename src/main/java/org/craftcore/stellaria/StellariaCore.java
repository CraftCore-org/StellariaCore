package org.craftcore.stellaria;

import org.bukkit.plugin.java.JavaPlugin;
import org.craftcore.stellaria.managers.PluginManager;
import org.craftcore.stellaria.listeners.PlayerListener;
import org.bukkit.Bukkit;


import org.craftcore.stellaria.utils.Console;


public class StellariaCore extends JavaPlugin {
    
    @Override
    public void onEnable() {
        
        // Initialize managers
        // PluginManager.getInstance().initialize();
        
        // Register listeners
        // getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        
        Console.printLogo(getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        Console.printDisabledMessage();
        getLogger().info(getDescription().getName() + " has been disabled!");
    }
    
}
