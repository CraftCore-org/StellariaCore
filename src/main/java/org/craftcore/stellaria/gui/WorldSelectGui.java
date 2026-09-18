package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.CustomHeadUtil;
import org.craftcore.stellaria.utils.WorldNameUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 読み込み済みの全ワールドから、スポーン地点への移動先を選ぶGUI。
 * 45ワールドを超える場合は、矢印でページを切り替える。
 */
public class WorldSelectGui extends Gui {

    private static final int DEFAULT_WORLDS_PER_PAGE = 45;
    private static final int DEFAULT_PREVIOUS_PAGE_SLOT = 45;
    private static final int DEFAULT_NEXT_PAGE_SLOT = 53;

    private final StellariaCore plugin;
    private final List<World> worlds;
    private final Layout layout;
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
        this(plugin, parent, page, Layout.create(plugin, Bukkit.getWorlds().size(), parent != null));
    }

    private WorldSelectGui(StellariaCore plugin, @Nullable Gui parent, int page, Layout layout) {
        super(layout.inventorySize(),
                ColorUtil.component(plugin.getConfigManager().getString("world.gui-title", "&%9ワールドを選択")),
                parent, layout.backButtonSlot());
        this.plugin = plugin;
        this.parent = parent;
        this.layout = layout;
        this.worlds = List.copyOf(Bukkit.getWorlds());
        this.pageCount = Math.max(1, (worlds.size() + layout.worldsPerPage() - 1) / layout.worldsPerPage());
        this.page = Math.clamp(page, 0, pageCount - 1);
        populate();
    }

    private static int autoInventorySize(int worldCount) {
        if (worldCount > DEFAULT_WORLDS_PER_PAGE) {
            return 54;
        }
        return Math.max(9, ((Math.max(1, worldCount) + 8) / 9) * 9);
    }

    private void populate() {
        int start = page * layout.worldsPerPage();
        int end = Math.min(start + layout.worldsPerPage(), worlds.size());
        for (int index = start; index < end; index++) {
            getInventory().setItem(index - start, worldItem(worlds.get(index)));
        }

        if (pageCount > 1) {
            if (page > 0) {
                getInventory().setItem(layout.previousPageSlot(), navigationItem(Material.ARROW, "&%f← 前のページ"));
            }
            if (page < pageCount - 1) {
                getInventory().setItem(layout.nextPageSlot(), navigationItem(Material.ARROW, "&%f次のページ →"));
            }
        }
    }

    private ItemStack worldItem(World world) {
        ConfigurationSection customHeads = plugin.getConfigManager().get("config.yml").get()
                .getConfigurationSection("world.gui-custom-heads");
        String customHeadId = customHeads == null ? null : customHeads.getString(world.getName());
        ItemStack item = customHeadId == null || customHeadId.isBlank()
                ? null
                : CustomHeadUtil.create(plugin, customHeadId.trim());
        if (item == null) {
            item = new ItemStack(iconFor(world.getEnvironment()));
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(WorldNameUtil.displayName(plugin.getConfigManager(), world)));
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
        if (pageCount > 1 && slot == layout.previousPageSlot() && page > 0) {
            new WorldSelectGui(plugin, parent, page - 1).open(player);
            return;
        }
        if (pageCount > 1 && slot == layout.nextPageSlot() && page < pageCount - 1) {
            new WorldSelectGui(plugin, parent, page + 1).open(player);
            return;
        }

        int worldIndex = page * layout.worldsPerPage() + slot;
        if (slot < layout.worldsPerPage() && worldIndex < worlds.size()) {
            player.teleportAsync(worlds.get(worldIndex).getSpawnLocation());
            player.closeInventory();
        }
    }

    static record Layout(int inventorySize, int worldsPerPage, int previousPageSlot, int nextPageSlot, int backButtonSlot) {

        private static Layout create(StellariaCore plugin, int worldCount, boolean hasParent) {
            int configuredRows = plugin.getConfigManager().getInt("world.gui-rows", 0, true);
            if (configuredRows >= 1 && configuredRows <= 6) {
                return configuredLayout(configuredRows, worldCount, hasParent);
            }
            return autoLayout(worldCount, hasParent);
        }

        static Layout autoLayout(int worldCount, boolean hasParent) {
            int inventorySize = autoInventorySize(worldCount + (hasParent ? 1 : 0));
            if (worldCount > DEFAULT_WORLDS_PER_PAGE) {
                return new Layout(inventorySize, DEFAULT_WORLDS_PER_PAGE,
                        DEFAULT_PREVIOUS_PAGE_SLOT, DEFAULT_NEXT_PAGE_SLOT, 48);
            }
            int worldsPerPage = inventorySize - (hasParent ? 1 : 0);
            return new Layout(inventorySize, worldsPerPage, -1, -1, inventorySize - 1);
        }

        private static Layout configuredLayout(int rows, int worldCount, boolean hasParent) {
            int inventorySize = rows * 9;
            int worldsWithoutNavigation = inventorySize - (hasParent ? 1 : 0);
            if (worldCount <= worldsWithoutNavigation) {
                return new Layout(inventorySize, worldsWithoutNavigation, -1, -1, inventorySize - 1);
            }

            if (rows == 1) {
                return new Layout(inventorySize, 3, 6, 8, 3);
            }

            int footerStart = inventorySize - 9;
            return new Layout(inventorySize, footerStart, footerStart, inventorySize - 1, footerStart + 3);
        }
    }
}
