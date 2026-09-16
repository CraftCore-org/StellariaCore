package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ContainerLock;
import org.craftcore.stellaria.managers.ContainerLockManager;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** /lock と /unlock を扱う。対象は実際に視線が当たっている保護可能コンテナだけ。 */
public class LockCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("trust", "untrust");

    private final StellariaCore plugin;

    public LockCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    static boolean isActionableTarget(Material material) {
        return ContainerLockManager.isLockable(material);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("lock.must_be_player", null));
            return true;
        }
        if (!player.hasPermission("stellaria.lock")) {
            return true;
        }

        Block target = player.getTargetBlockExact(5);
        if (target == null || !isActionableTarget(target.getType())) {
            message(player, "lock.look_at_container");
            return true;
        }

        if (command.getName().equalsIgnoreCase("unlock")) {
            unlock(player, target);
            return true;
        }

        if (args.length == 0) {
            create(player, target);
            return true;
        }
        if (args.length != 2) {
            message(player, "lock.usage");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "trust" -> updateMember(player, target, args[1], true);
            case "untrust" -> updateMember(player, target, args[1], false);
            default -> message(player, "lock.usage");
        }
        return true;
    }

    private void create(Player player, Block target) {
        ContainerLockManager manager = plugin.getContainerLockManager();
        Set<ContainerLock.BlockKey> keys = targetKeys(target);
        for (ContainerLock.BlockKey key : keys) {
            Optional<ContainerLock> existing = manager.find(key);
            if (existing.isPresent()) {
                message(player, "lock.already_locked", "%owner%", ownerName(existing.get().owner()));
                return;
            }
        }
        ContainerLockManager.CreateResult result = manager.create(player, keys);
        if (result == ContainerLockManager.CreateResult.SUCCESS) {
            message(player, "lock.created");
        } else {
            message(player, "lock.already_locked");
        }
    }

    private void unlock(Player player, Block target) {
        ContainerLockManager manager = plugin.getContainerLockManager();
        ContainerLock lock = manager.find(target).orElse(null);
        if (lock == null) {
            message(player, "lock.not_locked");
            return;
        }
        if (!lock.canManage(player.getUniqueId(), player.hasPermission("stellaria.lock.admin"))) {
            message(player, "lock.not_owner");
            return;
        }
        if (manager.unlock(lock) == ContainerLockManager.RemoveResult.SUCCESS) {
            message(player, "lock.unlocked");
        }
    }

    private void updateMember(Player player, Block target, String playerName, boolean add) {
        ContainerLockManager manager = plugin.getContainerLockManager();
        ContainerLock lock = manager.find(target).orElse(null);
        if (lock == null) {
            message(player, "lock.not_locked");
            return;
        }
        if (!lock.canManage(player.getUniqueId(), player.hasPermission("stellaria.lock.admin"))) {
            message(player, "lock.not_owner");
            return;
        }
        Player targetPlayer = Bukkit.getPlayerExact(playerName);
        if (targetPlayer == null) {
            message(player, "lock.look_at_container");
            return;
        }

        ContainerLockManager.MemberResult result = add
                ? manager.trust(lock, targetPlayer.getUniqueId())
                : manager.untrust(lock, targetPlayer.getUniqueId());
        if (result == ContainerLockManager.MemberResult.SUCCESS) {
            message(player, add ? "lock.trusted" : "lock.untrusted", "%player%", targetPlayer.getName());
        } else if (result == ContainerLockManager.MemberResult.OWNER) {
            message(player, "lock.cannot_trust_self");
        } else if (result == ContainerLockManager.MemberResult.ALREADY_MEMBER) {
            message(player, "lock.already_trusted");
        } else if (result == ContainerLockManager.MemberResult.NOT_MEMBER) {
            message(player, "lock.not_trusted");
        }
    }

    private static Set<ContainerLock.BlockKey> targetKeys(Block target) {
        Set<ContainerLock.BlockKey> keys = new LinkedHashSet<>();
        keys.add(ContainerLock.BlockKey.of(target));
        if (!(target.getState() instanceof Chest chest)) {
            return keys;
        }
        InventoryHolder holder = chest.getInventory().getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            addChestKey(keys, doubleChest.getLeftSide());
            addChestKey(keys, doubleChest.getRightSide());
        }
        return keys;
    }

    private static void addChestKey(Set<ContainerLock.BlockKey> keys, InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            keys.add(ContainerLock.BlockKey.of(chest.getBlock()));
        }
    }

    private String ownerName(UUID owner) {
        String name = Bukkit.getOfflinePlayer(owner).getName();
        return name == null ? owner.toString() : name;
    }

    private void message(Player player, String key, String... replacements) {
        String message = plugin.getConfigManager().getMessage(key, player);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            message = FormatUtil.replace(message, replacements[index], replacements[index + 1]);
        }
        player.sendMessage(message);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("unlock")) return List.of();
        if (args.length == 1) return TabCompleteUtil.filterStartsWith(SUBCOMMANDS, args[0]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            return TabCompleteUtil.filterStartsWith(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }
}
