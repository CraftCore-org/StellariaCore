package org.craftcore.stellaria.managers;

import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.craftcore.stellaria.StellariaCore;

public class LobbyManager {
    private StellariaCore plugin;
    public LobbyManager(StellariaCore plugin){
        this.plugin = plugin;
    }

    public void onLobbyBlockPlace(BlockPlaceEvent event){
        if (!plugin.getConfigManager().getBoolean("lobbyprotect.enabled",true)) { return; }
        if (event.getPlayer().getWorld().getName().equals(plugin.getConfigManager().getString("lobbyprotect.world-id",""))){
            event.setCancelled(true);
        }
    }

    public void onLobbyBlockBreak(BlockBreakEvent event){
        if (!plugin.getConfigManager().getBoolean("lobbyprotect.enabled",true)) { return; }
        if (event.getPlayer().getWorld().getName().equals(plugin.getConfigManager().getString("lobbyprotect.world-id",""))){
            event.setCancelled(true);
        }
    }

    public void onLobbyPlayerInteract(PlayerInteractEvent event){
        if (!plugin.getConfigManager().getBoolean("lobbyprotect.enabled",true)) { return; }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) { return; }
        if (event.getClickedBlock() == null) { return; }
        if (event.getPlayer().getWorld().getName().equals(plugin.getConfigManager().getString("lobbyprotect.world-id",""))){
            event.setCancelled(true);
        }
    }
}
