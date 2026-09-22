package org.craftcore.stellaria.rail;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * 調査用の一時クラス。トロッコ乗車中の「空気右クリック」がPlayerInteractEvent等の
 * Bukkit高レベルイベントに一切現れない現象について、パケットレベルではクライアントから
 * 何か送られてきているのかどうかを確認するために置く。原因が特定でき次第削除する。
 */
public final class RailDebugPacketListener extends PacketListenerAbstract {

    /** 右クリック系の候補になりそうなパケット種別だけに絞る（移動パケット等でチャットが埋まらないように）。 */
    private static final Set<PacketType.Play.Client> WATCHED = Set.of(
            PacketType.Play.Client.INTERACT_ENTITY,
            PacketType.Play.Client.USE_ITEM,
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            PacketType.Play.Client.ANIMATION
    );

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!WATCHED.contains(event.getPacketType())) {
            return;
        }
        Object rawPlayer = event.getPlayer();
        if (!(rawPlayer instanceof Player player) || !(player.getVehicle() instanceof Minecart)) {
            return;
        }
        player.sendMessage("§7[rail-debug/packet] " + event.getPacketType().getName());
    }
}
