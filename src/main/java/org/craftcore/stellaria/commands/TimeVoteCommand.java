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
import org.craftcore.stellaria.gui.TimeVoteGui;
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
 * 時間投票（/timevote）。ワールドごとに独立して投票できるよう、
 * ワールドUUID -> {@link VoteSession} で状態を持つ（以前はコマンドインスタンスに1セッションしか
 * 持てず、同時に1ワールドしか投票できなかった）。
 */
public class TimeVoteCommand implements CommandExecutor, TabCompleter {
    private final StellariaCore plugin;
    public TimeVoteCommand(StellariaCore plugin) { this.plugin = plugin; }

    private final Map<UUID, VoteSession> activeVotes = new HashMap<>();

    private static final class VoteSession {
        Player voteStartPlayer;
        String votingTime;
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

    private void starttimeVote(Player player, String timeType) {
        World world = player.getWorld();
        VoteSession session = new VoteSession();
        session.votedPlayers.add(player);
        session.votingTime = timeType;
        session.votingWorld = world;
        session.voteStartPlayer = player;
        activeVotes.put(world.getUID(), session);
        plugin.getAdvancementManager().onVoteStarted(player, false);

        Component voteMessage = Component.text("   ")
                .append(button(plugin.getConfigManager().getMessage("timevote.accept", player), "/tvaccept", "timevote.accept_tooltip", player))
                .append(Component.text("   "))
                .append(button(plugin.getConfigManager().getMessage("timevote.deny", player), "/tvdeny", "timevote.deny_tooltip", player));
        String message = FormatUtil.replace(FormatUtil.replace(FormatUtil.replace(plugin.getConfigManager().getMessage("timevote.start_vote", player), "%world%", FormatUtil.color(plugin.getConfigManager().getString("timevote.worldname." + world.getName(), ""))), "%time%", timeType), "%votetime%", String.valueOf(plugin.getConfigManager().getInt("timevote.votetime", 15)));
        for (Player recipient : world.getPlayers()) {
            recipient.sendMessage(message);
            recipient.sendMessage(voteMessage);
        }
        getServer().getScheduler().runTaskLater(plugin, () -> endtimeVote(world.getUID()),
                SchedulerIntervalUtil.secondsToTicks(plugin.getConfigManager().getInt("timevote.votetime", 15)));
    }

    private void endtimeVote(UUID worldId) {
        VoteSession session = activeVotes.remove(worldId);
        if (session == null) return;
        plugin.getAdvancementManager().event(session.voteStartPlayer.getUniqueId(),
                session.voteAccepts >= session.voteDenys ? "vote.passed" : "vote.rejected");
        if (session.voteAccepts >= session.voteDenys) {
            String message = FormatUtil.replace(FormatUtil.replace(plugin.getConfigManager().getMessage("timevote.vote_end_accept", (OfflinePlayer) session.voteStartPlayer), "%world%", FormatUtil.color(plugin.getConfigManager().getString("timevote.worldname." + session.votingWorld.getName(), ""))), "%time%", session.votingTime);
            for (Player recipient : session.votingWorld.getPlayers()) {
                recipient.sendMessage(message);
            }
            switch (session.votingTime) {
                case "朝" -> session.votingWorld.setTime(0);
                case "昼" -> session.votingWorld.setTime(6000);
                case "夕方" -> session.votingWorld.setTime(12000);
                case "夜" -> session.votingWorld.setTime(18000);
                default -> { }
            }
        } else {
            String message = plugin.getConfigManager().getMessage("timevote.vote_end_deny", session.voteStartPlayer);
            for (Player recipient : session.votingWorld.getPlayers()) {
                recipient.sendMessage(message);
            }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("timevote.must_be_player", null));
            return true;
        }
        if (WorldBlacklistUtil.isBlacklisted(plugin.getConfigManager().getStringList("timevote.disabled-worlds", true), player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("timevote.world_disabled", player));
            return true;
        }
        UUID worldId = player.getWorld().getUID();
        if (command.getName().equalsIgnoreCase("timevote")) {
            if (args.length == 0) {
                new TimeVoteGui(plugin, player).open(player);
                return true;
            }
            if (activeVotes.containsKey(worldId)) {
                player.sendMessage(FormatUtil.text((OfflinePlayer) sender, plugin.getConfigManager().getMessage("timevote.err_voting", (OfflinePlayer) sender)));
                return false;
            }
            if (plugin.getConfigManager().getString("timevote.worldname." + player.getWorld().getName(), "", true).isEmpty()) {
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_world", player));
                return false;
            }

            if (args[0].equals("朝") || args[0].equals("昼") || args[0].equals("夕方") || args[0].equals("夜")) {
                starttimeVote(player, args[0]);
                return true;
            }
            player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_unknown_time", (OfflinePlayer) sender));
            return false;
        }
        if (command.getName().equalsIgnoreCase("tvaccept")) {
            VoteSession session = activeVotes.get(worldId);
            if (session == null) {
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_notvoting", player));
                return false;
            }
            if (session.votedPlayers.contains(player)) {
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_voted", player));
                return false;
            }
            session.votedPlayers.add(player);
            session.voteAccepts++;
            plugin.getAdvancementManager().onVoteCast(player, true);
            player.sendMessage(plugin.getConfigManager().getMessage("timevote.vote_accept", player));
        }
        if (command.getName().equalsIgnoreCase("tvdeny")) {
            VoteSession session = activeVotes.get(worldId);
            if (session == null) {
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_notvoting", player));
                return false;
            }
            if (session.votedPlayers.contains(player)) {
                player.sendMessage(plugin.getConfigManager().getMessage("timevote.err_voted", player));
                return false;
            }
            session.votedPlayers.add(player);
            session.voteDenys++;
            plugin.getAdvancementManager().onVoteCast(player, false);
            player.sendMessage(plugin.getConfigManager().getMessage("timevote.vote_deny", player));
        }
        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("timevote")) {
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
