package org.craftcore.stellaria.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.craftcore.stellaria.StellariaCore;

public class LobbyProtectListener implements Listener {

    private StellariaCore plugin;
    public LobbyProtectListener(StellariaCore plugin){
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event){
        plugin.getLobbyManager().onLobbyBlockPlace(event);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event){
        plugin.getLobbyManager().onLobbyBlockBreak(event);
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event){
        plugin.getLobbyManager().onLobbyPlayerInteract(event);
    }
}
