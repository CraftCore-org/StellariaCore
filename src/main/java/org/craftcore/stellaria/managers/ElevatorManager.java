package org.craftcore.stellaria.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentBuilder;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.List;

public class ElevatorManager {

    private final StellariaCore plugin;
    public ElevatorManager(StellariaCore plugin) { this.plugin = plugin; }

    private boolean isMoveable(Block block){
        Block testBlock = block.getLocation().add(0,1,0).getBlock();
        if (!checkIgnoreBlock(testBlock)) { return false; }
        testBlock = block.getLocation().add(0,2,0).getBlock();
        if (!checkIgnoreBlock(testBlock)) { return false; }
        return true;
    }

    private boolean checkIgnoreBlock(Block block){
        List<String> ignoreBlocks = plugin.getConfigManager().getStringList("elevator.ignoreblocks");
        if (block.getType() == Material.AIR) { return true; }
        for (String blockId : ignoreBlocks){
            if (blockId.contains("&")){
                if (block.getType().toString().contains(blockId.replace("&",""))){
                    return true;
                }
            } else {
                if (block.getType().toString().equalsIgnoreCase(blockId)){
                    return true;
                }
            }
        }
        return false;
    }

    public void PlayerElevatorMoveUp(Player player){
        Block elevatorBlock = player.getLocation().subtract(0,1,0).getBlock();
        Material elevatorBlockType = elevatorBlock.getType();
        int max = plugin.getConfigManager().getInt("elevator.blocks." + elevatorBlockType.toString() + ".max", 0, true);
        if (max == 0) return;
        Block checkBlock = elevatorBlock;
        for (int i = 0; i < max; i++){
            checkBlock = checkBlock.getLocation().add(0,1,0).getBlock();
            if (checkBlock.getType() == elevatorBlockType){
                if (!isMoveable(checkBlock)) {
                    Component message = FormatUtil.component(plugin.getConfigManager().getString("elevator.message.moveup_fail",""));
                    player.sendActionBar(message);
                    return;
                }
                if (!isMoveable(elevatorBlock)) {
                    Component message = FormatUtil.component(plugin.getConfigManager().getString("elevator.message.move_fail",""));
                    player.sendActionBar(message);
                    return;
                }
                Location location = new Location(player.getWorld(),player.getX(),checkBlock.getY() + 1,player.getZ(),player.getYaw(),player.getPitch());
                player.teleport(location);
                Component message = FormatUtil.component(plugin.getConfigManager().getString("elevator.message.moveup_success",""));
                player.sendActionBar(message);
                String soundName = plugin.getConfigManager().getString("elevator.sound.moveup.name", "BLOCK_PISTON_EXTEND");
                Sound sound;
                try {
                    sound = Sound.valueOf(soundName.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("elevator.sound.moveup.name (\"" + soundName + "\") が有効な org.bukkit.Sound ではないため、通知音をスキップします。");
                    return;
                }
                float volume = (float) plugin.getConfigManager().getDouble("elevator.sound.moveup.volume", 1.0);
                float pitch = (float) plugin.getConfigManager().getDouble("elevator.sound.moveup.pitch", 1.0);
                player.playSound(player.getLocation(),sound,volume,pitch);
                return;
            }
        }
    }

    public void PlayerElevatorMoveDown(Player player){
        Block elevatorBlock = player.getLocation().subtract(0,1,0).getBlock();
        Material elevatorBlockType = elevatorBlock.getType();
        int max = plugin.getConfigManager().getInt("elevator.blocks." + elevatorBlockType.toString() + ".max", 0, true);
        if (max == 0) return;
        Block checkBlock = elevatorBlock;
        for (int i = 0; i < max; i++){
            checkBlock = checkBlock.getLocation().subtract(0,1,0).getBlock();
            if (checkBlock.getType() == elevatorBlockType){
                if (!isMoveable(checkBlock)) {
                    Component message = FormatUtil.component(plugin.getConfigManager().getString("elevator.message.movedown_fail",""));
                    player.sendActionBar(message);
                    return;
                }
                if (!isMoveable(elevatorBlock)) {
                    Component message = FormatUtil.component(plugin.getConfigManager().getString("elevator.message.move_fail",""));
                    player.sendActionBar(message);
                    return;
                }
                Location location = new Location(player.getWorld(),player.getX(),checkBlock.getY() + 1,player.getZ(),player.getYaw(),player.getPitch());
                player.teleport(location);
                Component message = FormatUtil.component(plugin.getConfigManager().getString("elevator.message.movedown_success",""));
                player.sendActionBar(message);
                String soundName = plugin.getConfigManager().getString("elevator.sound.movedown.name", "BLOCK_PISTON_EXTEND");
                Sound sound;
                try {
                    sound = Sound.valueOf(soundName.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("elevator.sound.movedown.name (\"" + soundName + "\") が有効な org.bukkit.Sound ではないため、通知音をスキップします。");
                    return;
                }
                float volume = (float) plugin.getConfigManager().getDouble("elevator.sound.movedown.volume", 1.0);
                float pitch = (float) plugin.getConfigManager().getDouble("elevator.sound.movedown.pitch", 1.0);
                player.playSound(player.getLocation(),sound,volume,pitch);
                return;
            }
        }
    }
}
