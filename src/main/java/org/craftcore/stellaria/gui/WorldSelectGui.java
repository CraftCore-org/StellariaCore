package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 読み込み済みの全ワールドから、スポーン地点への移動先を選ぶGUI。
 * 45ワールドを超える場合は、矢印でページを切り替える。
 */
public class WorldSelectGui extends Gui {

    private static final int WORLDS_PER_PAGE = 45;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;

    private final StellariaCore plugin;
    private final List<World> worlds;
    private final int page;
    private final int pageCount;
    private final @Nullable Gui parent;

    /** 先頭ページのワールド選択GUIを作成する。 */
    public WorldSelectGui(StellariaCore plugin) {
        this(plugin, null, 0);
    }

    /** メニュー画面から開く場合、戻るボタンを出すために親画面を渡す。 */
    public WorldSelectGui(StellariaCore plugin, @Nullable Gui parent) {
        this(plugin, parent, 0);
    }

    private WorldSelectGui(StellariaCore plugin, @Nullable Gui parent, int page) {
        super(inventorySize(Bukkit.getWorlds().size()),
                ColorUtil.component(plugin.getConfigManager().getString("world.gui-title", "&%9ワールドを選択")),
                parent, backButtonSlot(inventorySize(Bukkit.getWorlds().size())));
        this.plugin = plugin;
        this.parent = parent;
        this.worlds = List.copyOf(Bukkit.getWorlds());
        this.pageCount = Math.max(1, (worlds.size() + WORLDS_PER_PAGE - 1) / WORLDS_PER_PAGE);
        this.page = Math.clamp(page, 0, pageCount - 1);
        populate();
    }

    private static int inventorySize(int worldCount) {
        if (worldCount > WORLDS_PER_PAGE) {
            return 54;
        }
        return Math.max(9, ((Math.max(1, worldCount) + 8) / 9) * 9);
    }

    /** 54枠（ページング矢印あり）の時だけ矢印と被らない48番、それ以外は末尾スロット。 */
    private static int backButtonSlot(int size) {
        return size == 54 ? 48 : size - 1;
    }

    private void populate() {
        int start = page * WORLDS_PER_PAGE;
        int end = Math.min(start + WORLDS_PER_PAGE, worlds.size());
        for (int index = start; index < end; index++) {
            getInventory().setItem(index - start, worldItem(worlds.get(index)));
        }

        if (pageCount > 1) {
            if (page > 0) {
                getInventory().setItem(PREVIOUS_PAGE_SLOT, navigationItem(Material.ARROW, "&%f← 前のページ"));
            }
            if (page < pageCount - 1) {
                getInventory().setItem(NEXT_PAGE_SLOT, navigationItem(Material.ARROW, "&%f次のページ →"));
            }
        }
    }

    private ItemStack worldItem(World world) {
        ItemStack item = new ItemStack(iconFor(world.getEnvironment()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(world.getName()));
        meta.lore(List.of(Component.text(world.getEnvironment().name())));
        item.setItemMeta(meta);
        return item;
    }

    private Material iconFor(World.Environment environment) {
        return switch (environment) {
            case NORMAL -> Material.GRASS_BLOCK;
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.STONE;
        };
    }

    private ItemStack navigationItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ColorUtil.component(name));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);

        if (event.getClickedInventory() != getInventory() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (handleBackButton(event, player)) {
            return;
        }

        int slot = event.getSlot();
        if (pageCount > 1 && slot == PREVIOUS_PAGE_SLOT && page > 0) {
            new WorldSelectGui(plugin, parent, page - 1).open(player);
            return;
        }
        if (pageCount > 1 && slot == NEXT_PAGE_SLOT && page < pageCount - 1) {
            new WorldSelectGui(plugin, parent, page + 1).open(player);
            return;
        }

        int worldIndex = page * WORLDS_PER_PAGE + slot;
        if (slot < WORLDS_PER_PAGE && worldIndex < worlds.size()) {
            player.teleportAsync(worlds.get(worldIndex).getSpawnLocation());
            player.closeInventory();
        }
    }
}
