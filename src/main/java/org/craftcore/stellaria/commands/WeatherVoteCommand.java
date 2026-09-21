package org.craftcore.stellaria.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.OfflinePlayer;
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
import org.craftcore.stellaria.utils.SchedulerIntervalUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.bukkit.Bukkit.getServer;

/**
 * 天気投票（/weathervote）。ワールドごとに独立して投票できるよう、
 * ワールドUUID -> {@link VoteSession} で状態を持つ（以前はコマンドインスタンスに1セッションしか
 * 持てず、同時に1ワールドしか投票できなかった）。
 */
public class WeatherVoteCommand implements CommandExecutor, TabCompleter {
    private final StellariaCore plugin;
    public WeatherVoteCommand(StellariaCore plugin) { this.plugin = plugin; }

    private final Map<UUID, VoteSession> activeVotes = new HashMap<>();

    private static final class VoteSession {
        Player voteStartPlayer;
        String votingWeather;
        World votingWorld;
        int voteAccepts = 1;
        int voteDenys = 0;
        final List<Player> votedPlayers = new ArrayList<>();
    }

    private Component button(String text, String command, String tooltipPath, Player viewer) {
        Component tooltip = plugin.getPlaceholderManager().resolveLines(plugin.getConfigManager().getMessageList(tooltipPath), viewer);
        Component btn = ColorUtil.component(text).clickEvent(ClickEvent.runCommand(command));
        return tooltip != null ? btn.hoverEvent(HoverEvent.showText(tooltip)) : btn;
    }

    private void startWeatherVote(Player player, String weatherType) {
        World world = player.getWorld();
        VoteSession session = new VoteSession();
        session.votedPlayers.add(player);
        session.votingWeather = weatherType;
        session.votingWorld = world;
        session.voteStartPlayer = player;
        activeVotes.put(world.getUID(), session);

        Component voteMessage = Component.text("   ")
                .append(button(plugin.getConfigManager().getMessage("weathervote.accept", player), "/wvaccept", "weathervote.accept_tooltip", player))
                .append(Component.text("   "))
                .append(button(plugin.getConfigManager().getMessage("weathervote.deny", player), "/wvdeny", "weathervote.deny_tooltip", player));
        String message = FormatUtil.replace(FormatUtil.replace(FormatUtil.replace(plugin.getConfigManager().getMessage("weathervote.start_vote", player), "%world%", plugin.getConfigManager().getString("weathervote.worldname." + world.getName(), "")), "%weather%", weatherType), "%votetime%", String.valueOf(plugin.getConfigManager().getInt("weathervote.votetime", 15)));
        for (Player recipient : world.getPlayers()) {
            recipient.sendMessage(message);
            recipient.sendMessage(voteMessage);
        }
        getServer().getScheduler().runTaskLater(plugin, () -> endWeatherVote(world.getUID()),
                SchedulerIntervalUtil.secondsToTicks(plugin.getConfigManager().getInt("weathervote.votetime", 15)));
    }

    private void endWeatherVote(UUID worldId) {
        VoteSession session = activeVotes.remove(worldId);
        if (session == null) return;
        if (session.voteAccepts >= session.voteDenys) {
            String message = FormatUtil.replace(FormatUtil.replace(plugin.getConfigManager().getMessage("weathervote.vote_end_accept", (OfflinePlayer) session.voteStartPlayer), "%world%", plugin.getConfigManager().getString("weathervote.worldname." + session.votingWorld.getName(), "")), "%weather%", session.votingWeather);
            for (Player recipient : session.votingWorld.getPlayers()) {
                recipient.sendMessage(message);
            }
            switch (session.votingWeather) {
                case "晴れ" -> {
                    session.votingWorld.setStorm(false);
                    session.votingWorld.setThundering(false);
                }
                case "雨" -> {
                    session.votingWorld.setStorm(true);
                    session.votingWorld.setThundering(false);
                }
                case "雷雨" -> {
                    session.votingWorld.setStorm(true);
                    session.votingWorld.setThundering(true);
                }
                default -> { }
            }
        } else {
            String message = plugin.getConfigManager().getMessage("weathervote.vote_end_deny", session.voteStartPlayer);
            for (Player recipient : session.votingWorld.getPlayers()) {
                recipient.sendMessage(message);
            }
        }
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
        UUID worldId = player.getWorld().getUID();
        if (command.getName().equalsIgnoreCase("weathervote")) {
            if (args.length == 0) {
                new WeatherVoteGui(plugin, player).open(player);
                return true;
            }
            if (activeVotes.containsKey(worldId)) {
                player.sendMessage(FormatUtil.text((OfflinePlayer) sender, plugin.getConfigManager().getMessage("weathervote.err_voting", (OfflinePlayer) sender)));
                return false;
            }
            if (plugin.getConfigManager().getString("weathervote.worldname." + player.getWorld().getName(), "", true).isEmpty()) {
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_world", player));
                return false;
            }

            if (args[0].equals("晴れ") || args[0].equals("雨") || args[0].equals("雷雨")) {
                startWeatherVote(player, args[0]);
                return true;
            }
            player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_unknown_weather", (OfflinePlayer) sender));
            return false;
        }
        if (command.getName().equalsIgnoreCase("wvaccept")) {
            VoteSession session = activeVotes.get(worldId);
            if (session == null) {
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_notvoting", player));
                return false;
            }
            if (session.votedPlayers.contains(player)) {
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_voted", player));
                return false;
            }
            session.votedPlayers.add(player);
            session.voteAccepts++;
            player.sendMessage(plugin.getConfigManager().getMessage("weathervote.vote_accept", player));
        }
        if (command.getName().equalsIgnoreCase("wvdeny")) {
            VoteSession session = activeVotes.get(worldId);
            if (session == null) {
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_notvoting", player));
                return false;
            }
            if (session.votedPlayers.contains(player)) {
                player.sendMessage(plugin.getConfigManager().getMessage("weathervote.err_voted", player));
                return false;
            }
            session.votedPlayers.add(player);
            session.voteDenys++;
            player.sendMessage(plugin.getConfigManager().getMessage("weathervote.vote_deny", player));
        }
        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("weathervote")) {
            List<String> completes = new ArrayList<>();
            completes.add("晴れ");
            completes.add("雨");
            completes.add("雷雨");
            return completes;
        }
        return List.of();
    }
}
