package org.craftcore.stellaria.listeners;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.DatabaseManager;
import org.craftcore.stellaria.utils.MenuItemUtil;
import org.craftcore.stellaria.utils.ParticleUtil;

import java.util.List;
import java.util.Map;

public class PlayerJoinListener implements Listener {

    private final StellariaCore plugin;

    public PlayerJoinListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        Player player = event.getPlayer();

        // messages.yml からフォーマット済みのメッセージを取得
        String joinMsg = plugin.getConfigManager().getMessage("join", player);

        if (!joinMsg.isEmpty()) {
            event.setJoinMessage(joinMsg);
        } else {
            // 空メッセージの場合はメッセージ自体を非表示にする
            event.setJoinMessage(null);
        }

        playJoinEffect(player);
        plugin.getPlaytimeManager().onJoin(player);

        // すでにレコードがあるかチェック
        boolean exists = DatabaseManager.exists("players", "uuid = ?", uuid);

        if (!exists) {
            int defaultBalance = plugin.getConfigManager().getInt("economy.default-balance", 1000);
            DatabaseManager.insertAsync("players", Map.of(
                "uuid", uuid,
                "name", event.getPlayer().getName(),
                "coins", defaultBalance
            ));
        }

        // 投票報酬によりログイン前からレコードがある場合でも、初回キットは配布する。
        if (!player.hasPlayedBefore()) {
            giveFirstJoinKit(player);
            player.getInventory().addItem(MenuItemUtil.create(plugin));
        }
    }

    /**
     * 初回ログイン時、{@code config.yml}の{@code first-join-kit.items}（"素材名:個数"形式の
     * 文字列リスト）に従ってアイテムを配布する。インベントリに入りきらなかった分は足元にドロップする。
     */
    private void giveFirstJoinKit(Player player) {
        if (!plugin.getConfigManager().getBoolean("first-join-kit.enabled", true)) return;

        List<String> items = plugin.getConfigManager().getStringList("first-join-kit.items");
        for (String entry : items) {
            String[] parts = entry.split(":", 2);
            Material material = Material.matchMaterial(parts[0].trim());
            if (material == null) {
                plugin.getLogger().warning("first-join-kit.items の \"" + entry + "\" が有効な org.bukkit.Material ではないため、スキップします。");
                continue;
            }
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("first-join-kit.items の \"" + entry + "\" の個数指定が不正なため、1個として扱います。");
                }
            }

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, amount));
            leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
    }

    /**
     * ログイン時、足元に{@code config.yml}の{@code join-effect.*}で指定した円パーティクルを出す。
     * {@code particle}が{@code DUST}の時だけ{@code color}/{@code size}を読んで色付きで描画する
     * （{@code TpaCore#playTeleportEffect}と同じパターン）。
     */
    private void playJoinEffect(Player player) {
        if (!plugin.getConfigManager().getBoolean("join-effect.enabled", true)) return;

        Particle particle = Particle.valueOf(plugin.getConfigManager().getString("join-effect.particle", "DUST"));
        double radius = plugin.getConfigManager().getDouble("join-effect.radius", 1.0);
        int points = plugin.getConfigManager().getInt("join-effect.points", 30);

        if (particle == Particle.DUST) {
            Color color = ParticleUtil.parseColor(plugin.getConfigManager().getString("join-effect.color", "#FFFFFF"));
            float size = (float) plugin.getConfigManager().getDouble("join-effect.size", 1.0);
            ParticleUtil.spawnCircle(player.getLocation(), radius, points, color, size);
        } else {
            ParticleUtil.spawnCircle(player.getLocation(), radius, points, particle);
        }
    }
}
