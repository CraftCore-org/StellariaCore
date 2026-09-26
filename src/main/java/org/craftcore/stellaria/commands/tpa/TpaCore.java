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
import org.craftcore.stellaria.managers.ConfigManager;
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
    // mover UUID -> destination UUID（詠唱中のみ）。詠唱の開始・キャンセル・切断を
    // destination側にも通知するために、後から「誰が誰を待っているか」を引けるようにしている。
    private final static Map<UUID, UUID> pendingTeleportDestination = new HashMap<>();

    private final StellariaCore plugin;

    public TpaCore(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /**
     * 相手プレイヤーの名前を %player% に入れたメッセージを返す。
     * getMessage は渡したプレイヤーで %player% を先に埋めてしまうため、受け手を渡してから後で
     * 相手の名前に置き換えようとしても効かない（受け手自身の名前が表示される）。必ず相手を渡すこと。
     */
    static String messageAbout(ConfigManager config, String key, OfflinePlayer other) {
        return config.getMessage(key, other);
    }

    /**
     * プレイヤー退出時の後片付け。このプレイヤーが「詠唱中のmover」だった場合は待っているdestinationへ、
     * 「詠唱中に待たれていたdestination」だった場合は詠唱中のmoverへ、それぞれ切断を通知してからキャンセルする。
     */
    public static void resetPlayerTeleportRequests(Player player, StellariaCore plugin){
        UUID playerId = player.getUniqueId();

        // 自分がmover側だった場合: 待っているdestinationに切断を通知する
        UUID destinationId = pendingTeleportDestination.remove(playerId);
        if (destinationId != null) {
            Player waitingDestination = Bukkit.getPlayer(destinationId);
            if (waitingDestination != null) {
                waitingDestination.sendMessage(
                        messageAbout(plugin.getConfigManager(), "tpa.tpa_warmup_mover_disconnected", player));
            }
        }

        // 自分がdestination側だった場合: 詠唱中のmoverに即座に切断を通知してキャンセルする
        // （元々はmoverの詠唱が時間切れになるまで気付けなかった）
        Iterator<Map.Entry<UUID, UUID>> it = pendingTeleportDestination.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            if (!entry.getValue().equals(playerId)) continue;
            UUID moverId = entry.getKey();
            it.remove();
            ScheduledTask moverTask = pendingTeleport.remove(moverId);
            if (moverTask != null) moverTask.cancel();
            Player waitingMover = Bukkit.getPlayer(moverId);
            if (waitingMover != null) {
                stopCountdown(waitingMover, plugin);
                waitingMover.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_warmup_target_offline", waitingMover));
            }
        }

        tpRequest.remove(playerId);
        tpHere.remove(playerId);
        tpaPendingSender.remove(playerId);
        tpHerePendingSender.remove(playerId);
        ScheduledTask task = pendingTeleport.remove(playerId);
        if (task != null) task.cancel();
        ScheduledTask countdownTask = pendingCountdown.remove(playerId);
        if (countdownTask != null) countdownTask.cancel();

        // playerId宛て（destination）に送信中だった他プレイヤーの送信状態を解除する
        // （tpaPendingSender/tpHerePendingSenderは 送信者UUID -> 送信先UUID のマップなので、
        //   playerIdをキーで消すだけでは「playerId宛てに送っていた別の誰か」は消えない）
        tpaPendingSender.values().removeIf(destination -> destination.equals(playerId));
        tpHerePendingSender.values().removeIf(destination -> destination.equals(playerId));

        // playerIdが送信者として残っている、他プレイヤーの受信箱（inbox）エントリも除去する
        for (List<UUID> requesters : tpRequest.values()) {
            requesters.remove(playerId);
        }
        for (List<UUID> requesters : tpHere.values()) {
            requesters.remove(playerId);
        }
    }

    /**
     * {@code tpa.request-expire-seconds}（既定60秒）が経過しても相手が応答しなかった未処理のリクエストを
     * 自動で取り下げる。既にaccept/deny/退出等で処理済みなら{@code requesters.remove(senderId)}が失敗して
     * 何もしない（二重処理防止）ので、キャンセル漏れを気にせずfire-and-forgetで良い。
     */
    private void scheduleRequestExpiry(Map<UUID, List<UUID>> requestMap, Map<UUID, UUID> pendingSenderMap,
                                        UUID senderId, UUID targetId,
                                        String expiredSenderKey, String expiredReceiverKey) {
        int expireSeconds = plugin.getConfigManager().getInt("tpa.request-expire-seconds", 60);
        if (expireSeconds <= 0) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> {
            List<UUID> requesters = requestMap.get(targetId);
            if (requesters == null || !requesters.remove(senderId)) return;
            pendingSenderMap.remove(senderId);
            Player senderPlayer = Bukkit.getPlayer(senderId);
            Player targetPlayer = Bukkit.getPlayer(targetId);
            if (senderPlayer != null && targetPlayer != null) {
                senderPlayer.sendMessage(messageAbout(plugin.getConfigManager(), expiredSenderKey, targetPlayer));
                targetPlayer.sendMessage(messageAbout(plugin.getConfigManager(), expiredReceiverKey, senderPlayer));
            }
        }, expireSeconds * 20L);
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
            stopCountdown(player, plugin);
            player.sendMessage(plugin.getConfigManager().getMessage("tpa.tpa_warmup_cancelled", player));

            UUID destinationId = pendingTeleportDestination.remove(player.getUniqueId());
            if (destinationId != null) {
                Player waitingDestination = Bukkit.getPlayer(destinationId);
                if (waitingDestination != null) {
                    waitingDestination.sendMessage(
                            messageAbout(plugin.getConfigManager(), "tpa.tpa_warmup_cancelled_target", player));
                }
            }
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

        Particle particle = ParticleUtil.resolveParticle(plugin.getConfigManager().getString("teleport-effect.particle", "DUST"), plugin.getLogger()::warning);
        double radius = plugin.getConfigManager().getDouble("teleport-effect.radius", 1.0);
        int points = plugin.getConfigManager().getInt("teleport-effect.points", 30);

        if (particle == Particle.DUST) {
            Color color = ParticleUtil.parseColorOrFallback(
                    plugin.getConfigManager().getString("teleport-effect.color", "#FFFFFF"), Color.WHITE,
                    plugin.getLogger()::warning);
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
        destination.sendMessage(FormatUtil.replace(
                messageAbout(plugin.getConfigManager(), "tpa.tpa_warmup_target", mover),
                "%seconds%", String.valueOf(delaySeconds)));

        startCountdown(mover, delaySeconds);
        pendingTeleportDestination.put(moverId, destinationId);

        ScheduledTask task = mover.getScheduler().runDelayed(plugin, scheduledTask -> {
            pendingTeleport.remove(moverId);
            pendingTeleportDestination.remove(moverId);
            stopCountdown(mover, plugin);
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
            pendingTeleportDestination.remove(moverId);
            stopCountdown(mover, plugin);
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
        }, () -> pendingCountdown.remove(moverId), 1L, 20L);

        pendingCountdown.put(moverId, countdownTask);
    }

    /**
     * カウントダウンタスクを止めてアクションバー表示も消す。
     * {@link #resetPlayerTeleportRequests}（static、destination退出時にmover側を止める用途）からも
     * 呼べるようにstatic化し、pluginを引数で受け取る。
     */
    private static void stopCountdown(Player mover, StellariaCore plugin) {
        ScheduledTask task = pendingCountdown.remove(mover.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        plugin.getActionBarManager().clearChannel(mover, "tpa_countdown");
    }

    private void performTeleport(Player mover, Player destination) {
        if (!mover.teleport(destination.getLocation())) {
            return;
        }
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
                scheduleRequestExpiry(tpRequest, tpaPendingSender, ((Player) sender).getUniqueId(), player.getUniqueId(),
                        "tpa.tpa_expired_sender", "tpa.tpa_expired_receiver");
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
                scheduleRequestExpiry(tpHere, tpHerePendingSender, ((Player) sender).getUniqueId(), player.getUniqueId(),
                        "tpa.tphere_expired_sender", "tpa.tphere_expired_receiver");
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
