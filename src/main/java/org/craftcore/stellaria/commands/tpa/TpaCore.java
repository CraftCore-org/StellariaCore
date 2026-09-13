package org.craftcore.stellaria.commands.tpa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class TpaCore implements CommandExecutor {
    private final static Map<UUID, List<UUID>> tpRequest = new HashMap<>();
    private final static Map<UUID,List<UUID>> tpHere = new HashMap<>();

    public static void resetPlayerTeleportRequests(Player player){
        tpRequest.remove(player.getUniqueId());
        tpHere.remove(player.getUniqueId());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player)) return false;

        if (command.getName().equalsIgnoreCase("tpa")){
            if (args.length == 0){
                sender.sendMessage("§c§l| §7テレポートリクエストを送るプレイヤーを指定してください。");
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage("§c§l| §7指定されたプレイヤーはオンラインではありません。");
                return false;
            } else {
                if (player.getName().equals(sender.getName())){
                    sender.sendMessage("§c§l| §7自分自身にテレポートリクエストを送ることはできません。");
                    return false;
                }
                if (!(tpRequest.containsKey(player.getUniqueId()) && tpRequest.get(player.getUniqueId()).contains(((Player) sender).getUniqueId()))){
                    tpRequest.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(((Player) sender).getUniqueId());
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,0);
                }
                ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,1);
                Component message = Component.text("§a[許可]").clickEvent(ClickEvent.runCommand("/tpaccept " + sender.getName()))
                        .append(Component.text("   "))
                        .append(Component.text("§c[拒否]").clickEvent(ClickEvent.runCommand("/tpdeny " + sender.getName())));
                sender.sendMessage("§e§l| §7" + player.getName() + "§7にテレポートリクエストを送信しました。");
                player.sendMessage("§e§l| §7" + sender.getName() + "§7からテレポートリクエストが届きました。");
                player.sendMessage(message);
                return true;
            }
        }
        if (command.getName().equalsIgnoreCase("tpaccept")){
            if (args.length == 0){
                sender.sendMessage("§c§l| §7プレイヤーを指定してください。");
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage("§c§l| §7指定されたプレイヤーはオンラインではありません。");
                return false;
            } else {
                if (tpRequest.containsKey(((Player) sender).getUniqueId()) && tpRequest.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    sender.sendMessage("§a§l| §7テレポートリクエストを許可しました。");
                    player.sendMessage("§a§l| §7" + sender.getName() + "§7がテレポートリクエストを許可しました。");
                    player.teleport(((Player) sender).getLocation());
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,1,1);
                    tpRequest.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    return true;
                } else {
                    sender.sendMessage("§c§l| §7指定されたプレイヤーからテレポートリクエストは届いていません。");
                    return false;
                }
            }
        }
        if (command.getName().equalsIgnoreCase("tpdeny")){
            if (args.length == 0){
                sender.sendMessage("§c§l| §7プレイヤーを指定してください。");
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage("§c§l| §7指定されたプレイヤーはオンラインではありません。");
                return false;
            } else {
                if (tpRequest.containsKey(((Player) sender).getUniqueId()) && tpRequest.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    sender.sendMessage("§c§l| §7テレポートリクエストを拒否しました。");
                    player.sendMessage("§c§l| §7" + sender.getName() + "§7がテレポートリクエストを拒否しました。");
                    tpRequest.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    return true;
                } else {
                    sender.sendMessage("§c§l| §7指定されたプレイヤーからテレポートリクエストは届いていません。");
                    return false;
                }
            }
        }

        if (command.getName().equalsIgnoreCase("tphere")){
            if (args.length == 0){
                sender.sendMessage("§c§l| §7呼び出しリクエストを送るプレイヤーを指定してください。");
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage("§c§l| §7指定されたプレイヤーはオンラインではありません。");
                return false;
            } else {
                if (player.getName().equals(sender.getName())){
                    sender.sendMessage("§c§l| §7自分自身に呼び出しリクエストを送ることはできません。");
                    return false;
                }
                if (!(tpHere.containsKey(player.getUniqueId()) && tpHere.get(player.getUniqueId()).contains(((Player) sender).getUniqueId()))) {
                    tpHere.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(((Player) sender).getUniqueId());
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,0);
                }
                ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,1);
                Component message = Component.text("§a[許可]").clickEvent(ClickEvent.runCommand("/tphaccept " + sender.getName()))
                        .append(Component.text("   "))
                        .append(Component.text("§c[拒否]").clickEvent(ClickEvent.runCommand("/tphdeny " + sender.getName())));
                sender.sendMessage("§e§l| §7" + player.getName() + "§7に呼び出しリクエストを送信しました。");
                player.sendMessage("§e§l| §7" + sender.getName() + "§7から呼び出しリクエストが届きました。");
                player.sendMessage(message);
                return true;
            }
        }
        if (command.getName().equalsIgnoreCase("tphaccept")){
            if (args.length == 0){
                sender.sendMessage("§c§l| §7プレイヤーを指定してください。");
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage("§c§l| §7指定されたプレイヤーはオンラインではありません。");
                return false;
            } else {
                if (tpHere.containsKey(((Player) sender).getUniqueId()) && tpHere.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    sender.sendMessage("§a§l| §7呼び出しリクエストを許可しました。");
                    player.sendMessage("§a§l| §7" + sender.getName() + "§7が呼び出しリクエストを許可しました。");
                    ((Player)sender).teleport(player.getLocation());
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME,1,1);
                    tpHere.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    return true;
                } else {
                    sender.sendMessage("§c§l| §7指定されたプレイヤーから呼び出しリクエストは届いていません。");
                    return false;
                }
            }
        }
        if (command.getName().equalsIgnoreCase("tphdeny")){
            if (args.length == 0){
                sender.sendMessage("§c§l| §7プレイヤーを指定してください。");
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage("§c§l| §7指定されたプレイヤーはオンラインではありません。");
                return false;
            } else {
                if (tpHere.containsKey(((Player) sender).getUniqueId()) && tpHere.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    sender.sendMessage("§c§l| §7呼び出しリクエストを拒否しました。");
                    player.sendMessage("§c§l| §7" + sender.getName() + "§7が呼び出しリクエストを拒否しました。");
                    tpHere.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    return true;
                } else {
                    sender.sendMessage("§c§l| §7指定されたプレイヤーから呼び出しリクエストは届いていません。");
                    return false;
                }
            }
        }
        return false;
    }


}
