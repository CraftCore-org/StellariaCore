package org.craftcore.stellaria.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ActiveBanRegistry;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.DurationParser;

/** Blocks players with an active moderation ban before a Bukkit player is created. */
public class BanLoginListener implements Listener {

    private final StellariaCore plugin;

    public BanLoginListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        ActiveBanRegistry.BanEntry ban = plugin.getModerationManager().getActiveBan(event.getUniqueId());
        if (ban == null) {
            return;
        }

        String expires = ban.expiresAt() == null
                ? "無期限"
                : DurationParser.formatRemaining(ban.expiresAt());
        String message = plugin.getConfigManager().getMessage("moderation.ban_login_screen", null)
                .replace("%reason%", ban.reason())
                .replace("%expires%", expires)
                .replace("%discord_invite%", plugin.getConfigManager().getString("discord.invite", ""));
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, ColorUtil.component(message));
    }
}
