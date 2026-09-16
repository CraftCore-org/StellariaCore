package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.TimeVoteGui;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.bukkit.Bukkit.getServer;

public class TimeVoteCommand implements CommandExecutor, TabCompleter {
    private final StellariaCore plugin;
    public TimeVoteCommand(StellariaCore plugin) { this.plugin = plugin; }

    private Boolean isVoting = false;
    private Player voteStartPlayer;
    private String votingTime;
    private World votingWorld;
    private int voteAccepts = 0;
    private int voteDenys = 0;
    private final List<Player> votedPlayers = new ArrayList<>();

    private Component button(String text, String command, String tooltipPath, Player viewer) {
        Component tooltip = plugin.getPlaceholderManager().resolveLines(plugin.getConfigManager().getMessageList(tooltipPath), viewer);
        Component btn = ColorUtil.component(text).clickEvent(ClickEvent.runCommand(command));
        return tooltip != null ? btn.hoverEvent(HoverEvent.showText(tooltip)) : btn;
    }

    private void starttimeVote(Player player, String timeType){
        votedPlayers.clear();
        votedPlayers.add(player);
        votingTime = timeType;
        votingWorld = player.getWorld();
        voteStartPlayer = player;
        voteAccepts = 1;
        voteDenys = 0;
        isVoting = true;
        Component voteMessage = Component.text("   ")
                .append(button(plugin.getConfigManager().getMessage("timevote.accept",player), "/tvaccept", "timevote.accept_tooltip", player))
                .append(Component.text("   "))
                .append(button(plugin.getConfigManager().getMessage("timevote.deny",player), "/tvdeny", "timevote.deny_tooltip", player));
        String message = FormatUtil.replace(FormatUtil.replace(FormatUtil.replace(plugin.getConfigManager().getMessage("timevote.start_vote",player),"%world%",plugin.getConfigManager().getString("timevote.worldname." + player.getWorld().getName(),"")),"%time%",timeType),"%votetime%", String.valueOf(plugin.getConfigManager().getInt("timevote.votetime",15)));
        for (Player player1 : Bukkit.getOnlinePlayers()){
            player1.sendMessage(message);
            player1.sendMessage(voteMessage);
        }
        getServer().getScheduler().runTaskLater(plugin, this::endtimeVote, plugin.getConfigManager().getInt("timevote.votetime", 15) * 20L);
    }

    private void endtimeVote(){
        if (voteAccepts >= voteDenys){
            String message = FormatUtil.replace(FormatUtil.replace(plugin.getConfigManager().getMessage("timevote.vote_end_accept",(OfflinePlayer) voteStartPlayer),"%world%",plugin.getConfigManager().getString("timevote.worldname." + votingWorld.getName(),"")),"%time%", votingTime);
            for (Player player1 : Bukkit.getOnlinePlayers()){
                player1.sendMessage(message);
            }
            if (votingTime.equals("朝")){
                votingWorld.setTime(0);
            } else if (votingTime.equals("昼")){
                votingWorld.setTime(6000);
            } else if (votingTime.equals("夕方")){
                votingWorld.setTime(12000);
            } else if (votingTime.equals("夜")){
                votingWorld.setTime(18000);
            }
            resettimeVote();
        } else {
            String message = plugin.getConfigManager().getMessage("timevote.vote_end_deny",voteStartPlayer);
            for (Player player1 : Bukkit.getOnlinePlayers()){
                player1.sendMessage(message);
            }
            resettimeVote();
        }
    }

    private void resettimeVote(){
        isVoting = false;
        voteStartPlayer = null;
        votingTime = null;
        votingWorld = null;
        voteAccepts = 0;
        voteDenys = 0;
        votedPlayers.clear();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        Player player = (Player) sender;
        if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("timevote.disabled-worlds", true), player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("timevote.world_disabled", player));
            return true;
        }
        if (command.getName().equalsIgnoreCase("timevote")){
            if (args.length == 0){
                new TimeVoteGui(plugin, player).open(player);
                return true;
            }
            if (isVoting) {
                player.sendMessage(FormatUtil.text((OfflinePlayer) sender, plugin.getConfigManager().getMessage("timevote.err_voting",(OfflinePlayer) sender)));
                return false;
            }
            if (plugin.getConfigManager().getString("timevote.worldname." + player.getWorld().getName(), "", true).isEmpty()){
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_world",player));
                return false;
            }

            if (args[0].equals("朝")){
                starttimeVote(player, args[0]);
                return true;
            } else if (args[0].equals("昼")){
                starttimeVote(player, args[0]);
                return true;
            } else if (args[0].equals("夕方")){
                starttimeVote(player, args[0]);
                return true;
            } else if (args[0].equals("夜")) {
                starttimeVote(player, args[0]);
                return true;
            }
            player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_unknown_time",(OfflinePlayer) sender));
            return false;
        }
        if (command.getName().equalsIgnoreCase("tvaccept")){
            if (!isVoting){
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_notvoting",player));
                return false;
            }
            if (votedPlayers.contains(player)){
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_voted",player));
                return false;
            }
            votedPlayers.add(player);
            voteAccepts++;
            player.sendMessage(plugin.getConfigManager().getMessage("timevote.vote_accept",player));
        }
        if (command.getName().equalsIgnoreCase("tvdeny")){
            if (!isVoting){
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_notvoting",player));
                return false;
            }
            if (votedPlayers.contains(player)){
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_voted",player));
                return false;
            }
            votedPlayers.add(player);
            voteDenys++;
            player.sendMessage(plugin.getConfigManager().getMessage("timevote.vote_deny",player));
        }
        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("timevote")){
            List<String> completes = new ArrayList<>();
            completes.add("朝");
            completes.add("昼");
            completes.add("夕方");
            completes.add("夜");
            return completes;
        }
        return List.of();
    }
}
