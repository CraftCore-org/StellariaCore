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
import org.craftcore.stellaria.utils.GuiItemUtil;
import org.craftcore.stellaria.utils.WorldNameUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 設定されたワールド一覧から、スポーン地点への移動先を選ぶGUI。
 * リストの並び順通りにアイコンが配置され、収まらない場合は矢印でページを切り替える。
 */
public class WorldSelectGui extends Gui {

    private static final int DEFAULT_WORLDS_PER_PAGE = 45;
    private static final int DEFAULT_PREVIOUS_PAGE_SLOT = 45;
    private static final int DEFAULT_NEXT_PAGE_SLOT = 53;

    private final StellariaCore plugin;
    private final List<WorldEntry> entries;
    private final Layout layout;
    private final int page;
    private final int pageCount;
    private final @Nullable Gui parent;
    private final Map<Integer, WorldEntry> entriesBySlot = new HashMap<>();

    /** 先頭ページのワールド選択GUIを作成する。 */
    public WorldSelectGui(StellariaCore plugin) {
        this(plugin, null, 0);
    }

    /** メニュー画面から開く場合、戻るボタンを出すために親画面を渡す。 */
    public WorldSelectGui(StellariaCore plugin, @Nullable Gui parent) {
        this(plugin, parent, 0);
    }

    private WorldSelectGui(StellariaCore plugin, @Nullable Gui parent, int page) {
        this(plugin, parent, page, loadEntries(plugin));
    }

    private WorldSelectGui(StellariaCore plugin, @Nullable Gui parent, int page, List<WorldEntry> entries) {
        this(plugin, parent, page, entries, Layout.create(plugin, entries.size(), maxExplicitSlot(entries)));
    }

    private WorldSelectGui(StellariaCore plugin, @Nullable Gui parent, int page, List<WorldEntry> entries, Layout layout) {
        super(layout.inventorySize(),
                ColorUtil.component(plugin.getConfigManager().getString("world.gui-title", "&%9ワールドを選択")),
                parent, layout.backButtonSlot());
        this.plugin = plugin;
        this.parent = parent;
        this.layout = layout;
        this.entries = entries;
        this.pageCount = Math.max(1, (entries.size() + layout.worldsPerPage() - 1) / layout.worldsPerPage());
        this.page = Math.clamp(page, 0, pageCount - 1);
        populate();
    }

    private static int maxExplicitSlot(List<WorldEntry> entries) {
        int max = -1;
        for (WorldEntry entry : entries) {
            if (entry.slot() > max) {
                max = entry.slot();
            }
        }
        return max;
    }

    /**
     * config.yml の world.worlds (または world.items) からワールド項目リストを読み込む。
     * 未設定時はサーバー上の全ワールドを自動読み込みする。
     */
    public static List<WorldEntry> loadEntries(StellariaCore plugin) {
        List<WorldEntry> entries = new ArrayList<>();
        List<?> rawList = plugin.getConfigManager().get("config.yml").get().getList("world.worlds");
        if (rawList == null || rawList.isEmpty()) {
            rawList = plugin.getConfigManager().get("config.yml").get().getList("world.items");
        }

        if (rawList != null && !rawList.isEmpty()) {
            for (Object obj : rawList) {
                if (obj instanceof String worldName) {
                    if (!worldName.isBlank()) {
                        entries.add(new WorldEntry(worldName.trim(), null, null, null, null, -1));
                    }
                } else if (obj instanceof Map<?, ?> map) {
                    Object worldVal = map.get("world");
                    if (worldVal == null) {
                        continue;
                    }
                    String worldName = String.valueOf(worldVal).trim();
                    if (worldName.isEmpty()) {
                        continue;
                    }

                    Object nameVal = map.get("name");
                    if (nameVal == null) {
                        nameVal = map.get("display-name");
                    }
                    String displayName = nameVal != null && !String.valueOf(nameVal).isBlank() ? String.valueOf(nameVal) : null;

                    Object headVal = map.get("custom-head");
                    String customHeadId = headVal != null && !String.valueOf(headVal).isBlank() ? String.valueOf(headVal).trim() : null;

                    Object matVal = map.get("material");
                    if (matVal == null) {
                        matVal = map.get("icon");
                    }
                    Material material = matVal != null ? Material.matchMaterial(String.valueOf(matVal)) : null;

                    List<String> lore = null;
                    Object loreVal = map.get("lore");
                    if (loreVal instanceof List<?> loreList) {
                        lore = loreList.stream().map(String::valueOf).toList();
                    }

                    int slot = -1;
                    Object slotVal = map.get("slot");
                    if (slotVal instanceof Number num) {
                        slot = num.intValue();
                    }

                    entries.add(new WorldEntry(worldName, displayName, material, customHeadId, lore, slot));
                }
            }
        }

        if (entries.isEmpty()) {
            for (World w : Bukkit.getWorlds()) {
                entries.add(new WorldEntry(w.getName(), null, null, null, null, -1));
            }
        }
        return entries;
    }

    private boolean isReservedSlot(int slot) {
        if (parent != null && slot == layout.backButtonSlot()) {
            return true;
        }
        if (pageCount > 1) {
            if (slot == layout.previousPageSlot() || slot == layout.nextPageSlot()) {
                return true;
            }
        }
        return false;
    }

    private void populate() {
        int start = page * layout.worldsPerPage();
        int end = Math.min(start + layout.worldsPerPage(), entries.size());
        List<WorldEntry> pageEntries = entries.subList(start, end);

        // 1. スロット番号が明示的に指定されているアイテムを配置
        for (WorldEntry entry : pageEntries) {
            if (entry.slot() >= 0 && entry.slot() < layout.inventorySize() && !isReservedSlot(entry.slot())) {
                getInventory().setItem(entry.slot(), worldItem(entry));
                entriesBySlot.put(entry.slot(), entry);
            }
        }

        // 2. スロット未指定（-1 または範囲外・重複）のアイテムを空いているスロットに順番に配置
        int currentSlot = 0;
        for (WorldEntry entry : pageEntries) {
            if (entry.slot() < 0 || entry.slot() >= layout.inventorySize() || isReservedSlot(entry.slot()) || entriesBySlot.get(entry.slot()) != entry) {
                while (currentSlot < layout.inventorySize()
                        && (isReservedSlot(currentSlot) || entriesBySlot.containsKey(currentSlot))) {
                    currentSlot++;
                }
                if (currentSlot >= layout.inventorySize()) {
                    break;
                }
                getInventory().setItem(currentSlot, worldItem(entry));
                entriesBySlot.put(currentSlot, entry);
                currentSlot++;
            }
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

    private ItemStack worldItem(WorldEntry entry) {
        World world = Bukkit.getWorld(entry.worldName());
        String customHeadId = entry.customHeadId();
        if (customHeadId == null || customHeadId.isBlank()) {
            ConfigurationSection customHeads = plugin.getConfigManager().get("config.yml").get()
                    .getConfigurationSection("world.gui-custom-heads");
            customHeadId = customHeads == null ? null : customHeads.getString(entry.worldName());
        }

        ItemStack item = (customHeadId == null || customHeadId.isBlank())
                ? null
                : CustomHeadUtil.create(plugin, customHeadId.trim());

        if (item == null) {
            if (entry.material() != null) {
                item = new ItemStack(entry.material());
            } else if (world != null) {
                item = new ItemStack(iconFor(world.getEnvironment()));
            } else {
                item = new ItemStack(Material.STONE);
            }
        }

        GuiItemUtil.hideExtras(item);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = entry.displayName();
            if (name == null || name.isBlank()) {
                name = world != null ? WorldNameUtil.displayName(plugin.getConfigManager(), world) : entry.worldName();
            }
            meta.displayName(GuiItemUtil.text(name));

            if (entry.lore() != null && !entry.lore().isEmpty()) {
                meta.lore(entry.lore().stream().map(GuiItemUtil::text).toList());
            } else if (world != null) {
                meta.lore(List.of(GuiItemUtil.text(world.getEnvironment().name())));
            }

            item.setItemMeta(meta);
        }
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
        if (meta != null) {
            meta.displayName(GuiItemUtil.text(name));
            item.setItemMeta(meta);
        }
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
            new WorldSelectGui(plugin, parent, page - 1, entries, layout).open(player);
            return;
        }
        if (pageCount > 1 && slot == layout.nextPageSlot() && page < pageCount - 1) {
            new WorldSelectGui(plugin, parent, page + 1, entries, layout).open(player);
            return;
        }

        WorldEntry entry = entriesBySlot.get(slot);
        if (entry != null) {
            World world = Bukkit.getWorld(entry.worldName());
            if (world == null) {
                player.sendMessage(plugin.getConfigManager().getMessage("world.not_found", player));
                return;
            }
            player.teleportAsync(world.getSpawnLocation());
            player.closeInventory();
        }
    }

    public record WorldEntry(
            String worldName,
            @Nullable String displayName,
            @Nullable Material material,
            @Nullable String customHeadId,
            @Nullable List<String> lore,
            int slot
    ) {
    }

    private record Layout(int inventorySize, int worldsPerPage, int previousPageSlot, int nextPageSlot, int backButtonSlot) {

        private static Layout create(StellariaCore plugin, int worldCount, int maxExplicitSlot) {
            int configuredRows = plugin.getConfigManager().getInt("world.gui-rows", 0, true);
            if (configuredRows >= 1 && configuredRows <= 6) {
                return configuredLayout(configuredRows, worldCount);
            }

            int inventorySize = autoInventorySize(worldCount, maxExplicitSlot);
            if (worldCount > DEFAULT_WORLDS_PER_PAGE) {
                return new Layout(inventorySize, DEFAULT_WORLDS_PER_PAGE,
                        DEFAULT_PREVIOUS_PAGE_SLOT, DEFAULT_NEXT_PAGE_SLOT, 48);
            }
            return new Layout(inventorySize, DEFAULT_WORLDS_PER_PAGE, -1, -1, inventorySize - 1);
        }

        private static int autoInventorySize(int worldCount, int maxExplicitSlot) {
            int size;
            if (worldCount > DEFAULT_WORLDS_PER_PAGE) {
                size = 54;
            } else {
                size = Math.max(9, ((Math.max(1, worldCount) + 8) / 9) * 9);
            }
            if (maxExplicitSlot >= 0) {
                int explicitSize = Math.min(54, ((maxExplicitSlot + 9) / 9) * 9);
                size = Math.max(size, explicitSize);
            }
            return size;
        }

        private static Layout configuredLayout(int rows, int worldCount) {
            int inventorySize = rows * 9;
            if (worldCount <= inventorySize) {
                return new Layout(inventorySize, inventorySize, -1, -1, inventorySize - 1);
            }

            if (rows == 1) {
                return new Layout(inventorySize, 3, 6, 8, 3);
            }

            int footerStart = inventorySize - 9;
            return new Layout(inventorySize, footerStart, footerStart, inventorySize - 1, footerStart + 3);
        }
    }
}
