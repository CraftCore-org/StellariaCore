package org.craftcore.stellaria.listeners;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.jspecify.annotations.NonNull;

import java.util.List;

public class DiscordListener extends ListenerAdapter {
    private StellariaCore plugin;
    public DiscordListener(StellariaCore plugin) { this.plugin = plugin; }

    @Override
    public void onMessageReceived(@NonNull MessageReceivedEvent event){
        if (event.getAuthor().isBot() || event.getMessage().isWebhookMessage()) { return; }
        if (plugin.getConfigManager().getString("discord.bot.server-guild-id","").isEmpty() && plugin.getConfigManager().getString("discord.bot.admin-guild-id","").isEmpty()) { return; }
        if (plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id").isEmpty()){ return; }
        List<String> channels = plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id");
        if (!channels.contains(event.getChannel().getId())) { return; }
        if (!event.getChannelType().isMessage()) { return; }
        String username = event.getAuthor().getName();
        String message = event.getMessage().getContentDisplay();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.broadcastMessage(FormatUtil.color(plugin.getConfigManager().getString("discord.bot.discordchat-format","").replace("%username%",username).replace("%message%",message)));
        });
    }

    @Override
    public void onSlashCommandInteraction(@NonNull SlashCommandInteractionEvent event) {
        plugin.getDiscordBotManager().handleSlashCommand(event);
    }
}
