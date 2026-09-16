package org.craftcore.stellaria.managers;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.listeners.DiscordListener;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class DiscordBotManager {
    private StellariaCore plugin;
    public DiscordBotManager(StellariaCore plugin) { this.plugin = plugin; }
    private JDA jda;
    private String token;

    public void startBot(){
        token = plugin.getConfigManager().getString("discord.bot.token","");
        if (token.isEmpty()){
            plugin.getLogger().warning("botのtokenが指定されていません。");
            return;
        }
        try {
            jda = JDABuilder.createDefault(token)
                    .addEventListeners(new DiscordListener(plugin))
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .build();
            jda.awaitReady();
            if (plugin.getConfigManager().getString("discord.bot.server-guild-id", "").isEmpty() && plugin.getConfigManager().getString("discord.bot.admin-guild.id", "").isEmpty()) {
                return;
            }
            if (plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id").isEmpty()) {
                return;
            }
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle(plugin.getConfigManager().getString("discord.bot.serverstartlog-format", ""));
            embed.setColor(Color.GREEN);
            List<String> channels = plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id");
            for (String channel : channels) {
                if (jda.getTextChannelById(channel) == null) {
                    continue;
                }
                jda.getTextChannelById(channel)
                        .sendMessageEmbeds(embed.build())
                        .complete();
            }
            plugin.getLogger().info("DiscordBotを起動しました。");
        } catch (Exception e){
            plugin.getLogger().info("DiscordBotの起動に失敗しました。");
        }
    }

    public void stop(){
        try {
            if (plugin.getConfigManager().getString("discord.bot.server-guild-id", "").isEmpty() && plugin.getConfigManager().getString("discord.bot.admin-guild.id", "").isEmpty()) {
                return;
            }
            if (plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id").isEmpty()) {
                return;
            }
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle(plugin.getConfigManager().getString("discord.bot.serverstoplog-format", ""));
            embed.setColor(Color.RED);
            List<String> channels = plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id");
            for (String channel : channels) {
                if (jda.getTextChannelById(channel) == null) {
                    continue;
                }
                jda.getTextChannelById(channel)
                        .sendMessageEmbeds(embed.build())
                        .complete();
            }
        } catch (Exception e){
            plugin.getLogger().warning( "Discordへの停止通知に失敗しました: " + e.getMessage() );
        } finally {
            if (jda != null){
                jda.shutdown();
                jda = null;
            }
        }
    }

    public JDA getJda() {
        return jda;
    }

    public void mcChatToDiscord(AsyncChatEvent event){
        if (plugin.getConfigManager().getString("discord.bot.server-guild-id","").isEmpty() && plugin.getConfigManager().getString("discord.bot.admin-guild.id","").isEmpty()) { return; }
        if (plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id").isEmpty()){ return; }
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        List<String> channels = plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id");
        for (String channel : channels){
            if (jda.getTextChannelById(channel) == null) { continue; }
            jda.getTextChannelById(channel)
                    .sendMessage(plugin.getConfigManager().getString("discord.bot.serverchat-format","").replace("%player%",player.getName()).replace("%message%",message))
                    .queue();
        }
    }

    public void sendPlayerJoinLog(PlayerJoinEvent event){
        if (plugin.getConfigManager().getString("discord.bot.server-guild-id","").isEmpty() && plugin.getConfigManager().getString("discord.bot.admin-guild.id","").isEmpty()) { return; }
        if (plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id").isEmpty()){ return; }
        Player player = event.getPlayer();
        EmbedBuilder embed = new EmbedBuilder();
        embed.setDescription(plugin.getConfigManager().getString("discord.bot.joinlog-format","").replace("%player%",player.getName()));
        embed.setColor(Color.GREEN);
        List<String> channels = plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id");
        for (String channel : channels){
            if (jda.getTextChannelById(channel) == null) { continue; }
            jda.getTextChannelById(channel)
                    .sendMessageEmbeds(embed.build())
                    .queue();
        }
    }

    public void sendPlayerQuitLog(PlayerQuitEvent event){
        if (plugin.getConfigManager().getString("discord.bot.server-guild-id","").isEmpty() && plugin.getConfigManager().getString("discord.bot.admin-guild.id","").isEmpty()) { return; }
        if (plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id").isEmpty()){ return; }
        Player player = event.getPlayer();
        EmbedBuilder embed = new EmbedBuilder();
        embed.setDescription(plugin.getConfigManager().getString("discord.bot.quitlog-format","").replace("%player%",player.getName()));
        embed.setColor(Color.RED);
        List<String> channels = plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id");
        for (String channel : channels){
            if (jda.getTextChannelById(channel) == null) { continue; }
            jda.getTextChannelById(channel)
                    .sendMessageEmbeds(embed.build())
                    .queue();
        }
    }
}
