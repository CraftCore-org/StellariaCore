package org.craftcore.stellaria.commands.tpa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.Format;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class TpaCore implements CommandExecutor {
    private final static Map<UUID, List<UUID>> tpRequest = new HashMap<>();
    private final static Map<UUID,List<UUID>> tpHere = new HashMap<>();

    private final StellariaCore plugin;

    public TpaCore(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public static void resetPlayerTeleportRequests(Player player){
        tpRequest.remove(player.getUniqueId());
        tpHere.remove(player.getUniqueId());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player)) return false;

        if (command.getName().equalsIgnoreCase("tpa")){
            if (args.length == 0){
                String tpa_err_player = plugin.getConfig().getString("messages.tpa.tpa_err_player", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender,tpa_err_player));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                String tpa_err_onl = plugin.getConfig().getString("messages.tpa.tpa_err_online", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender, tpa_err_onl));
                return false;
            } else {
                if (player.getName().equals(sender.getName())){
                    String tpa_err_self = plugin.getConfig().getString("messages.tpa.tpa_err_self", "");
                    sender.sendMessage(Format.text((OfflinePlayer) sender, tpa_err_self));
                    return false;
                }
                if (!(tpRequest.containsKey(player.getUniqueId()) && tpRequest.get(player.getUniqueId()).contains(((Player) sender).getUniqueId()))){
                    tpRequest.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(((Player) sender).getUniqueId());
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,0);
                }

                String tpa_accept = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tpa_accept", ""));
                String tpa_deny = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tpa_deny", ""));
                String tpa_send = Format.text(player,plugin.getConfig().getString("messages.tpa.tpa_send", ""));
                String tpa_receive = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tpa_receive", ""));

                ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,1);
                Component message = Component.text(tpa_accept).clickEvent(ClickEvent.runCommand("/tpaccept " + sender.getName()))
                        .append(Component.text("   "))
                        .append(Component.text(tpa_deny).clickEvent(ClickEvent.runCommand("/tpdeny " + sender.getName())));
                sender.sendMessage(tpa_send);
                player.sendMessage(tpa_receive);
                player.sendMessage(message);
                return true;
            }
        }
        if (command.getName().equalsIgnoreCase("tpaccept")){
            if (args.length == 0){
                String tpa_err_player = plugin.getConfig().getString("messages.tpa.tpa_err_player", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender,tpa_err_player));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                String tpa_err_onl = plugin.getConfig().getString("messages.tpa.tpa_err_online", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender, tpa_err_onl));
                return false;
            } else {
                if (tpRequest.containsKey(((Player) sender).getUniqueId()) && tpRequest.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    String tpa_accept_sender = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tpa_accept_sender", ""));
                    String tpa_accept_receiver = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tpa_accept_receiver", ""));
                    sender.sendMessage(tpa_accept_receiver);
                    player.sendMessage(tpa_accept_sender);
                    player.teleport(((Player) sender).getLocation());
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,1,1);
                    tpRequest.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    return true;
                } else {
                    String tpa_err_notreceived = Format.text(player,plugin.getConfig().getString("messages.tpa.tpa_err_notreceived", ""));
                    sender.sendMessage(tpa_err_notreceived);
                    return false;
                }
            }
        }
        if (command.getName().equalsIgnoreCase("tpdeny")){
            if (args.length == 0){
                String tpa_err_player = plugin.getConfig().getString("messages.tpa.tpa_err_player", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender,tpa_err_player));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                String tpa_err_onl = plugin.getConfig().getString("messages.tpa.tpa_err_online", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender, tpa_err_onl));
                return false;
            } else {
                if (tpRequest.containsKey(((Player) sender).getUniqueId()) && tpRequest.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    String tpa_deny_sender = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tpa_deny_sender", ""));
                    String tpa_deny_receiver = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tpa_deny_receiver", ""));
                    sender.sendMessage(tpa_deny_receiver);
                    player.sendMessage(tpa_deny_sender);
                    tpRequest.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    return true;
                } else {
                    String tpa_err_notreceived = Format.text(player,plugin.getConfig().getString("messages.tpa.tpa_err_notreceived", ""));
                    sender.sendMessage(tpa_err_notreceived);
                    return false;
                }
            }
        }

        if (command.getName().equalsIgnoreCase("tphere")){
            if (args.length == 0){
                String tphere_err_player = plugin.getConfig().getString("messages.tpa.tphere_err_player", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender, tphere_err_player));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                String tphere_err_onl = plugin.getConfig().getString("messages.tpa.tphere_err_online", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender, tphere_err_onl));
                return false;
            } else {
                if (player.getName().equals(sender.getName())){
                    String tphere_err_self = plugin.getConfig().getString("messages.tpa.tphere_err_self", "");
                    sender.sendMessage(tphere_err_self);
                    return false;
                }
                if (!(tpHere.containsKey(player.getUniqueId()) && tpHere.get(player.getUniqueId()).contains(((Player) sender).getUniqueId()))) {
                    tpHere.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(((Player) sender).getUniqueId());
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,0);
                }
                String tphere_accept = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tpa_accept", ""));
                String tphere_deny = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tpa_deny", ""));
                String tphere_send = Format.text(player,plugin.getConfig().getString("messages.tpa.tphere_send", ""));
                String tphere_receive = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tphere_receive", ""));


                ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,1);
                Component message = Component.text(tphere_accept).clickEvent(ClickEvent.runCommand("/tphaccept " + sender.getName()))
                        .append(Component.text("   "))
                        .append(Component.text(tphere_deny).clickEvent(ClickEvent.runCommand("/tphdeny " + sender.getName())));
                sender.sendMessage(tphere_send);
                player.sendMessage(tphere_receive);
                player.sendMessage(message);
                return true;
            }
        }
        if (command.getName().equalsIgnoreCase("tphaccept")){
            if (args.length == 0){
                String tphere_err_player = plugin.getConfig().getString("messages.tpa.tphere_err_player", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender, tphere_err_player));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                String tphere_err_onl = plugin.getConfig().getString("messages.tpa.tphere_err_online", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender, tphere_err_onl));
                return false;
            } else {
                if (tpHere.containsKey(((Player) sender).getUniqueId()) && tpHere.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    String tphere_accept_sender = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tphere_accept_sender", ""));
                    String tphere_accept_receiver = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tphere_accept_receiver", ""));
                    sender.sendMessage(tphere_accept_receiver);
                    player.sendMessage(tphere_accept_sender);
                    ((Player)sender).teleport(player.getLocation());
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,1,1);
                    tpHere.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    return true;
                } else {
                    String tphere_err_notreceived = Format.text(player,plugin.getConfig().getString("messages.tpa.tphere_err_notreceived", ""));
                    sender.sendMessage(tphere_err_notreceived);
                    return false;
                }
            }
        }
        if (command.getName().equalsIgnoreCase("tphdeny")){
            if (args.length == 0){
                String tphere_err_player = plugin.getConfig().getString("messages.tpa.tphere_err_player", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender, tphere_err_player));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                String tphere_err_onl = plugin.getConfig().getString("messages.tpa.tphere_err_online", "");
                sender.sendMessage(Format.text((OfflinePlayer) sender, tphere_err_onl));
                return false;
            } else {
                if (tpHere.containsKey(((Player) sender).getUniqueId()) && tpHere.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    String tphere_deny_sender = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tphere_deny_sender", ""));
                    String tphere_deny_receiver = Format.text((OfflinePlayer) sender,plugin.getConfig().getString("messages.tpa.tphere_deny_receiver", ""));
                    sender.sendMessage(tphere_deny_receiver);
                    player.sendMessage(tphere_deny_sender);
                    tpHere.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    return true;
                } else {
                    String tphere_err_notreceived = Format.text(player,plugin.getConfig().getString("messages.tpa.tphere_err_notreceived", ""));
                    sender.sendMessage(tphere_err_notreceived);
                    return false;
                }
            }
        }
        return false;
    }


}
