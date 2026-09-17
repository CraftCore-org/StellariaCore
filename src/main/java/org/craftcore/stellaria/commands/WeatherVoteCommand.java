package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.WeatherVoteGui;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.bukkit.Bukkit.getServer;

public class WeatherVoteCommand implements CommandExecutor, TabCompleter {
    private final StellariaCore plugin;
    public WeatherVoteCommand(StellariaCore plugin) { this.plugin = plugin; }

    private Boolean isVoting = false;
    private Player voteStartPlayer;
    private String votingWeather;
    private World votingWorld;
    private int voteAccepts = 0;
    private int voteDenys = 0;
    private final List<Player> votedPlayers = new ArrayList<>();

    private Component button(String text, String command, String tooltipPath, Player viewer) {
        Component tooltip = plugin.getPlaceholderManager().resolveLines(plugin.getConfigManager().getMessageList(tooltipPath), viewer);
        Component btn = ColorUtil.component(text).clickEvent(ClickEvent.runCommand(command));
        return tooltip != null ? btn.hoverEvent(HoverEvent.showText(tooltip)) : btn;
    }

    private void startWeatherVote(Player player, String weatherType){
        votedPlayers.clear();
        votedPlayers.add(player);
        votingWeather = weatherType;
        votingWorld = player.getWorld();
        voteStartPlayer = player;
        voteAccepts = 1;
        voteDenys = 0;
        isVoting = true;
        Component voteMessage = Component.text("   ")
                .append(button(plugin.getConfigManager().getMessage("weathervote.accept",player), "/wvaccept", "weathervote.accept_tooltip", player))
                .append(Component.text("   "))
                .append(button(plugin.getConfigManager().getMessage("weathervote.deny",player), "/wvdeny", "weathervote.deny_tooltip", player));
        String message = FormatUtil.replace(FormatUtil.replace(FormatUtil.replace(plugin.getConfigManager().getMessage("weathervote.start_vote",player),"%world%",plugin.getConfigManager().getString("weathervote.worldname." + player.getWorld().getName(),"")),"%weather%",weatherType),"%votetime%", String.valueOf(plugin.getConfigManager().getInt("weathervote.votetime",15)));
        for (Player player1 : Bukkit.getOnlinePlayers()){
            player1.sendMessage(message);
            player1.sendMessage(voteMessage);
        }
        getServer().getScheduler().runTaskLater(plugin, this::endWeatherVote, plugin.getConfigManager().getInt("weathervote.votetime", 15) * 20L);
    }

    private void endWeatherVote(){
        if (voteAccepts >= voteDenys){
            String message = FormatUtil.replace(FormatUtil.replace(plugin.getConfigManager().getMessage("weathervote.vote_end_accept",(OfflinePlayer) voteStartPlayer),"%world%",plugin.getConfigManager().getString("weathervote.worldname." + votingWorld.getName(),"")),"%weather%",votingWeather);
            for (Player player1 : Bukkit.getOnlinePlayers()){
                player1.sendMessage(message);
            }
            if (votingWeather.equals("晴れ")){
                votingWorld.setStorm(false);
                votingWorld.setThundering(false);
            } else if (votingWeather.equals("雨")){
                votingWorld.setStorm(true);
                votingWorld.setThundering(false);
            } else if (votingWeather.equals("雷雨")){
                votingWorld.setStorm(true);
                votingWorld.setThundering(true);
            }
            resetWeatherVote();
        } else {
            String message = plugin.getConfigManager().getMessage("weathervote.vote_end_deny",voteStartPlayer);
            for (Player player1 : Bukkit.getOnlinePlayers()){
                player1.sendMessage(message);
            }
            resetWeatherVote();
        }
    }

    private void resetWeatherVote(){
        isVoting = false;
        voteStartPlayer = null;
        votingWeather = null;
        votingWorld = null;
        voteAccepts = 0;
        voteDenys = 0;
        votedPlayers.clear();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("weathervote.must_be_player", null));
            return true;
        }
        if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("weathervote.disabled-worlds", true), player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("weathervote.world_disabled", player));
            return true;
        }
        if (command.getName().equalsIgnoreCase("weathervote")){
            if (args.length == 0){
                new WeatherVoteGui(plugin, player).open(player);
                return true;
            }
            if (isVoting) {
                player.sendMessage(FormatUtil.text((OfflinePlayer) sender, plugin.getConfigManager().getMessage("weathervote.err_voting",(OfflinePlayer) sender)));
                return false;
            }
            if (plugin.getConfigManager().getString("weathervote.worldname." + player.getWorld().getName(), "", true).isEmpty()){
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_world",player));
                return false;
            }

            if (args[0].equals("晴れ")){
                startWeatherVote(player, args[0]);
                return true;
            } else if (args[0].equals("雨")){
                startWeatherVote(player, args[0]);
                return true;
            } else if (args[0].equals("雷雨")){
                startWeatherVote(player, args[0]);
                return true;
            }
            player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_unknown_weather",(OfflinePlayer) sender));
            return false;
        }
        if (command.getName().equalsIgnoreCase("wvaccept")){
            if (!isVoting){
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_notvoting",player));
                return false;
            }
            if (votedPlayers.contains(player)){
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_voted",player));
                return false;
            }
            votedPlayers.add(player);
            voteAccepts++;
            player.sendMessage(plugin.getConfigManager().getMessage("weathervote.vote_accept",player));
        }
        if (command.getName().equalsIgnoreCase("wvdeny")){
            if (!isVoting){
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_notvoting",player));
                return false;
            }
            if (votedPlayers.contains(player)){
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_voted",player));
                return false;
            }
            votedPlayers.add(player);
            voteDenys++;
            player.sendMessage(plugin.getConfigManager().getMessage("weathervote.vote_deny",player));
        }
        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("weathervote")){
            List<String> completes = new ArrayList<>();
            completes.add("晴れ");
            completes.add("雨");
            completes.add("雷雨");
            return completes;
        }
        return List.of();
    }
}
