package org.craftcore.stellaria.commands.tpa;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.ParticleUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class TpaCore implements CommandExecutor, Listener, TabCompleter {
    private final static Map<UUID, List<UUID>> tpRequest = new HashMap<>();
    private final static Map<UUID,List<UUID>> tpHere = new HashMap<>();
    // 送信者UUID -> 送信先UUID。送信者は未返答リクエストを同時に1件までしか持てないようにするための逆引き
    private final static Map<UUID, UUID> tpaPendingSender = new HashMap<>();
    private final static Map<UUID, UUID> tpHerePendingSender = new HashMap<>();
    // テレポート詠唱中（承認後の遅延待ち）のプレイヤーUUID -> キャンセル用ScheduledTask
    private final static Map<UUID, ScheduledTask> pendingTeleport = new HashMap<>();
    // テレポート詠唱中のアクションバーカウントダウン用ScheduledTask
    private final static Map<UUID, ScheduledTask> pendingCountdown = new HashMap<>();

    private final StellariaCore plugin;

    public TpaCore(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public static void resetPlayerTeleportRequests(Player player){
        tpRequest.remove(player.getUniqueId());
        tpHere.remove(player.getUniqueId());
        tpaPendingSender.remove(player.getUniqueId());
        tpHerePendingSender.remove(player.getUniqueId());
        ScheduledTask task = pendingTeleport.remove(player.getUniqueId());
        if (task != null) task.cancel();
        ScheduledTask countdownTask = pendingCountdown.remove(player.getUniqueId());
        if (countdownTask != null) countdownTask.cancel();
    }

    /**
     * ダメージを受けたら詠唱中のテレポートをキャンセルする。
     */
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        ScheduledTask task = pendingTeleport.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            stopCountdown(player);
            player.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_warmup_cancelled", player));
        }
    }

    /**
     * クリックでコマンドを実行するボタンを作る。{@code tooltipPath}（messages.yml、複数行リスト）に
     * 中身があれば、ホバーツールチップも付ける。TPA系ボタンで許可/拒否どっちにも使い回す。
     *
     * @param viewer このボタンを実際に見るプレイヤー（ツールチップのプレースホルダー解決に使う）
     */
    private Component button(String text, String command, String tooltipPath, Player viewer) {
        Component tooltip = plugin.getPlaceholderManager().resolveLines(plugin.getConfigManager().getMessageList(tooltipPath), viewer);
        Component btn = ColorUtil.component(text).clickEvent(ClickEvent.runCommand(command));
        return tooltip != null ? btn.hoverEvent(HoverEvent.showText(tooltip)) : btn;
    }

    /**
     * テレポート成功時、着地点に{@code config.yml}の{@code teleport-effect.*}で指定した円パーティクルを出す。
     * {@code particle}が{@code DUST}の時だけ{@code color}/{@code size}を読んで色付きで描画する。
     */
    private void playTeleportEffect(Location location) {
        if (!plugin.getConfigManager().getBoolean("teleport-effect.enabled", true)) return;

        Particle particle = Particle.valueOf(plugin.getConfigManager().getString("teleport-effect.particle", "DUST"));
        double radius = plugin.getConfigManager().getDouble("teleport-effect.radius", 1.0);
        int points = plugin.getConfigManager().getInt("teleport-effect.points", 30);

        if (particle == Particle.DUST) {
            Color color = ParticleUtil.parseColor(plugin.getConfigManager().getString("teleport-effect.color", "#FFFFFF"));
            float size = (float) plugin.getConfigManager().getDouble("teleport-effect.size", 1.0);
            ParticleUtil.spawnCircle(location, radius, points, color, size);
        } else {
            ParticleUtil.spawnCircle(location, radius, points, particle);
        }
    }

    /**
     * リクエスト承認後、{@code config.yml}の{@code tpa.teleport-delay-seconds}秒だけ待ってから
     * {@code mover}を{@code destination}の位置へテレポートさせる。0秒以下なら即テレポート。
     * 待機中に{@code mover}がダメージを受けると{@link #onEntityDamage}でキャンセルされる。
     */
    private void scheduleTeleport(Player mover, Player destination) {
        int delaySeconds = plugin.getConfigManager().getInt("tpa.teleport-delay-seconds", 5);
        long delayTicks = Math.max(0, delaySeconds) * 20L;
        UUID moverId = mover.getUniqueId();
        UUID destinationId = destination.getUniqueId();

        if (delayTicks <= 0) {
            performTeleport(mover, destination);
            return;
        }

        mover.sendMessage(FormatUtil.replace(
                plugin.getConfigManager().getMessage("tpa.tpa_warmup", mover),
                "%seconds%", String.valueOf(delaySeconds)));

        startCountdown(mover, delaySeconds);

        ScheduledTask task = mover.getScheduler().runDelayed(plugin, scheduledTask -> {
            pendingTeleport.remove(moverId);
            stopCountdown(mover);
            Player freshMover = Bukkit.getPlayer(moverId);
            Player freshDestination = Bukkit.getPlayer(destinationId);
            if (freshMover == null) return;
            if (freshDestination == null) {
                freshMover.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_warmup_target_offline", freshMover));
                return;
            }
            performTeleport(freshMover, freshDestination);
        }, () -> {
            pendingTeleport.remove(moverId);
            stopCountdown(mover);
        }, delayTicks);

        pendingTeleport.put(moverId, task);
    }

    /** テレポート待機中、1秒ごとに残り秒数をアクションバーへ表示する。 */
    private void startCountdown(Player mover, int totalSeconds) {
        UUID moverId = mover.getUniqueId();
        int[] remaining = {totalSeconds};

        ScheduledTask countdownTask = mover.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
            Player freshMover = Bukkit.getPlayer(moverId);
            if (freshMover == null || remaining[0] < 0) {
                scheduledTask.cancel();
                pendingCountdown.remove(moverId);
                return;
            }
            String message = FormatUtil.replace(
                    plugin.getConfigManager().getMessage("tpa.tpa_warmup_actionbar", freshMover),
                    "%seconds%", String.valueOf(remaining[0]));
            plugin.getActionBarManager().setChannel(freshMover, "tpa_countdown", ColorUtil.component(message));
            remaining[0]--;
        }, () -> pendingCountdown.remove(moverId), 0L, 20L);

        pendingCountdown.put(moverId, countdownTask);
    }

    /** カウントダウンタスクを止めてアクションバー表示も消す。 */
    private void stopCountdown(Player mover) {
        ScheduledTask task = pendingCountdown.remove(mover.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        plugin.getActionBarManager().clearChannel(mover, "tpa_countdown");
    }

    private void performTeleport(Player mover, Player destination) {
        mover.teleport(destination.getLocation());
        playTeleportEffect(destination.getLocation());
        mover.playSound(destination.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1, 1);
        destination.playSound(destination.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1, 1);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player)) return false;

        if (command.getName().equalsIgnoreCase("tpa")){
            if (args.length == 0){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_player", (OfflinePlayer) sender));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_online", (OfflinePlayer) sender));
                return false;
            } else {
                if (player.getName().equals(sender.getName())){
                    sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_self", (OfflinePlayer) sender));
                    return false;
                }
                if (tpaPendingSender.containsKey(((Player) sender).getUniqueId())){
                    sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_pending", (OfflinePlayer) sender));
                    return false;
                }
                tpRequest.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(((Player) sender).getUniqueId());
                tpaPendingSender.put(((Player) sender).getUniqueId(), player.getUniqueId());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,0);

                String tpa_accept = plugin.getConfigManager().getMessage("tpa.tpa_accept", (OfflinePlayer) sender);
                String tpa_deny = plugin.getConfigManager().getMessage("tpa.tpa_deny", (OfflinePlayer) sender);
                String tpa_send = plugin.getConfigManager().getMessage("tpa.tpa_send", player);
                String tpa_receive = plugin.getConfigManager().getMessage("tpa.tpa_receive", (OfflinePlayer) sender);

                ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,1);
                Component message = Component.text("   ")
                        .append(button(tpa_accept, "/tpaccept " + sender.getName(), "tpa.tpa_accept_tooltip", player))
                        .append(Component.text("   "))
                        .append(button(tpa_deny, "/tpdeny " + sender.getName(), "tpa.tpa_deny_tooltip", player));
                sender.sendMessage(tpa_send);
                player.sendMessage(tpa_receive);
                player.sendMessage(message);
                return true;
            }
        }
        if (command.getName().equalsIgnoreCase("tpaccept")){
            if (args.length == 0){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_player", (OfflinePlayer) sender));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_online", (OfflinePlayer) sender));
                return false;
            } else {
                if (tpRequest.containsKey(((Player) sender).getUniqueId()) && tpRequest.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    String tpa_accept_sender = plugin.getConfigManager().getMessage("tpa.tpa_accept_sender", (OfflinePlayer) sender);
                    String tpa_accept_receiver = plugin.getConfigManager().getMessage("tpa.tpa_accept_receiver", (OfflinePlayer) sender);
                    sender.sendMessage(tpa_accept_receiver);
                    player.sendMessage(tpa_accept_sender);
                    tpRequest.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    tpaPendingSender.remove(player.getUniqueId());
                    scheduleTeleport(player, (Player) sender);
                    return true;
                } else {
                    sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_notreceived", player));
                    return false;
                }
            }
        }
        if (command.getName().equalsIgnoreCase("tpdeny")){
            if (args.length == 0){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_player", (OfflinePlayer) sender));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_online", (OfflinePlayer) sender));
                return false;
            } else {
                if (tpRequest.containsKey(((Player) sender).getUniqueId()) && tpRequest.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    String tpa_deny_sender = plugin.getConfigManager().getMessage("tpa.tpa_deny_sender", (OfflinePlayer) sender);
                    String tpa_deny_receiver = plugin.getConfigManager().getMessage("tpa.tpa_deny_receiver", (OfflinePlayer) sender);
                    sender.sendMessage(tpa_deny_receiver);
                    player.sendMessage(tpa_deny_sender);
                    tpRequest.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    tpaPendingSender.remove(player.getUniqueId());
                    return true;
                } else {
                    sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_err_notreceived", player));
                    return false;
                }
            }
        }

        if (command.getName().equalsIgnoreCase("tphere")){
            if (args.length == 0){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_player", (OfflinePlayer) sender));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_online", (OfflinePlayer) sender));
                return false;
            } else {
                if (player.getName().equals(sender.getName())){
                    sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_self", (OfflinePlayer) sender));
                    return false;
                }
                if (tpHerePendingSender.containsKey(((Player) sender).getUniqueId())){
                    sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_pending", (OfflinePlayer) sender));
                    return false;
                }
                tpHere.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(((Player) sender).getUniqueId());
                tpHerePendingSender.put(((Player) sender).getUniqueId(), player.getUniqueId());
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,0);
                String tphere_accept = plugin.getConfigManager().getMessage("tpa.tpa_accept", (OfflinePlayer) sender);
                String tphere_deny = plugin.getConfigManager().getMessage("tpa.tpa_deny", (OfflinePlayer) sender);
                String tphere_send = plugin.getConfigManager().getMessage("tpa.tphere_send", player);
                String tphere_receive = plugin.getConfigManager().getMessage("tpa.tphere_receive", (OfflinePlayer) sender);


                ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_FLUTE,1,1);
                Component message = Component.text("   ")
                        .append(button(tphere_accept, "/tphaccept " + sender.getName(), "tpa.tpa_accept_tooltip", player))
                        .append(Component.text("   "))
                        .append(button(tphere_deny, "/tphdeny " + sender.getName(), "tpa.tpa_deny_tooltip", player));
                sender.sendMessage(tphere_send);
                player.sendMessage(tphere_receive);
                player.sendMessage(message);
                return true;
            }
        }
        if (command.getName().equalsIgnoreCase("tphaccept")){
            if (args.length == 0){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_player", (OfflinePlayer) sender));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_online", (OfflinePlayer) sender));
                return false;
            } else {
                if (tpHere.containsKey(((Player) sender).getUniqueId()) && tpHere.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    String tphere_accept_sender = plugin.getConfigManager().getMessage("tpa.tphere_accept_sender", (OfflinePlayer) sender);
                    String tphere_accept_receiver = plugin.getConfigManager().getMessage("tpa.tphere_accept_receiver", (OfflinePlayer) sender);
                    sender.sendMessage(tphere_accept_receiver);
                    player.sendMessage(tphere_accept_sender);
                    tpHere.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    tpHerePendingSender.remove(player.getUniqueId());
                    scheduleTeleport((Player) sender, player);
                    return true;
                } else {
                    sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_notreceived", player));
                    return false;
                }
            }
        }
        if (command.getName().equalsIgnoreCase("tphdeny")){
            if (args.length == 0){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_player", (OfflinePlayer) sender));
                return false;
            }
            Player player = Bukkit.getPlayer(args[0]);
            if (player == null){
                sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_online", (OfflinePlayer) sender));
                return false;
            } else {
                if (tpHere.containsKey(((Player) sender).getUniqueId()) && tpHere.get(((Player) sender).getUniqueId()).contains(player.getUniqueId())){
                    ((Player)sender).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
                    String tphere_deny_sender = plugin.getConfigManager().getMessage("tpa.tphere_deny_sender", (OfflinePlayer) sender);
                    String tphere_deny_receiver = plugin.getConfigManager().getMessage("tpa.tphere_deny_receiver", (OfflinePlayer) sender);
                    sender.sendMessage(tphere_deny_receiver);
                    player.sendMessage(tphere_deny_sender);
                    tpHere.get(((Player) sender).getUniqueId()).remove(player.getUniqueId());
                    tpHerePendingSender.remove(player.getUniqueId());
                    return true;
                } else {
                    sender.sendMessage(plugin.getConfigManager().getMessage("tpa.tphere_err_notreceived", player));
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.onlinePlayerNames(args[0]);
        }
        return List.of();
    }
}
