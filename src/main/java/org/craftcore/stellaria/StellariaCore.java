package org.craftcore.stellaria;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.craftcore.stellaria.commands.*;
import org.craftcore.stellaria.commands.tpa.TpaCore;
import org.craftcore.stellaria.commands.HomeCommand;
import org.craftcore.stellaria.commands.WarpCommand;
import org.craftcore.stellaria.commands.KikoriCommand;
import org.craftcore.stellaria.commands.MineCommand;
import org.craftcore.stellaria.commands.LandCommand;
import org.craftcore.stellaria.managers.*;
import org.craftcore.stellaria.gui.GuiListener;
import org.craftcore.stellaria.features.Feature;
import org.craftcore.stellaria.features.KikoriFeature;
import org.craftcore.stellaria.features.MineFeature;
import org.craftcore.stellaria.managers.ActionBarManager;
import org.craftcore.stellaria.managers.AfkManager;
import org.craftcore.stellaria.managers.AutoBroadcastManager;
import org.craftcore.stellaria.managers.BelownameManager;
import org.craftcore.stellaria.managers.ConfigManager;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.managers.MentionService;
import org.craftcore.stellaria.managers.MuteManager;
import org.craftcore.stellaria.managers.PlaceholderManager;
import org.craftcore.stellaria.managers.PrivateMessageManager;
import org.craftcore.stellaria.managers.ScoreboardManager;
import org.craftcore.stellaria.managers.TabListManager;
import org.craftcore.stellaria.listeners.ChatListener;
import org.craftcore.stellaria.listeners.PlayerJoinListener;
import org.craftcore.stellaria.listeners.MentionTabCompleteListener;
import org.craftcore.stellaria.listeners.MuteCommandBlockListener;
import org.craftcore.stellaria.listeners.PlayerListener;
import org.craftcore.stellaria.listeners.PlayerQuitListener;
import org.craftcore.stellaria.listeners.KikoriListener;
import org.craftcore.stellaria.listeners.MineListener;
import org.craftcore.stellaria.listeners.LandProtectionListener;
import org.craftcore.stellaria.listeners.LandAreaStatusListener;
import org.craftcore.stellaria.listeners.VanishListener;
import org.craftcore.stellaria.listeners.WorldResetListener;
import org.craftcore.stellaria.listeners.ContainerLockListener;
import org.craftcore.stellaria.listeners.VoteListener;
import org.craftcore.stellaria.listeners.MenuItemListener;


import org.craftcore.stellaria.utils.ConsoleUtil;

import net.milkbowl.vault.economy.Economy;

import java.util.List;


public class StellariaCore extends JavaPlugin {

    private EconomyManager economyManager;
    private ConfigManager configManager;
    private PlaceholderManager placeholderManager;
    private ScoreboardManager scoreboardManager;
    private TabListManager tabListManager;
    private BelownameManager belownameManager;
    private MentionService mentionService;
    private ElevatorManager elevatorManager;
    private AfkManager afkManager;
    private AutoBroadcastManager autoBroadcastManager;
    private MuteManager muteManager;
    private PrivateMessageManager privateMessageManager;
    private ActionBarManager actionBarManager;
    private BossBarManager bossBarManager;
    private PlaytimeManager playtimeManager;
    private RankManager rankManager;
    private HomeManager homeManager;
    private WarpManager warpManager;
    private HeadshopManager headshopManager;
    private VanishManager vanishManager;
    private NametagManager nametagManager;
    private KikoriManager kikoriManager;
    private MineManager mineManager;
    private LandManager landManager;
    private ContainerLockManager containerLockManager;
    private DiscordBotManager discordBotManager;
    private LandBorderParticleManager landBorderParticleManager;
    private WorldResetManager worldResetManager;
    private List<Feature> features;

    @Override
    public void onEnable() {

        // 0. 設定ファイルの読み込み管理（config.yml はサーバー設定、messages.yml はメッセージ）
        this.configManager = new ConfigManager(this);
        this.configManager.register("config.yml");
        this.configManager.register("messages.yml");

        // 1. データベースの接続とテーブル作成
        DatabaseManager.connect(this, "database.db");
        DatabaseManager.createTableIfNotExists("players",
            "uuid TEXT PRIMARY KEY",
            "name TEXT",
            "coins INTEGER DEFAULT 0"
        );
        DatabaseManager.createTableIfNotExists("mutes",
            "uuid TEXT PRIMARY KEY",
            "level INTEGER",
            "expires_at INTEGER",
            "reason TEXT",
            "muted_by TEXT",
            "muted_at INTEGER"
        );
        DatabaseManager.createTableIfNotExists("player_stats",
            "uuid TEXT PRIMARY KEY",
            "last_logout INTEGER DEFAULT 0",
            "playtime_seconds INTEGER DEFAULT 0"
        );

        DatabaseManager.createTableIfNotExists("homes",
            "uuid TEXT",
            "name TEXT",
            "world TEXT",
            "x REAL", "y REAL", "z REAL",
            "yaw REAL", "pitch REAL",
            "PRIMARY KEY (uuid, name)"
        );

        DatabaseManager.createTableIfNotExists("warps",
            "name TEXT PRIMARY KEY",
            "owner_uuid TEXT",
            "world TEXT",
            "x REAL", "y REAL", "z REAL",
            "yaw REAL", "pitch REAL"
        );

        DatabaseManager.addColumnIfNotExists("players", "kikori_unlocked INTEGER NOT NULL DEFAULT 0");
        DatabaseManager.addColumnIfNotExists("players", "hide_balance INTEGER NOT NULL DEFAULT 0");
        DatabaseManager.addColumnIfNotExists("players", "mine_unlocked INTEGER NOT NULL DEFAULT 0");

        DatabaseManager.createTableIfNotExists("land_claims",
            "world TEXT NOT NULL",
            "chunk_x INTEGER NOT NULL",
            "chunk_z INTEGER NOT NULL",
            "owner_uuid TEXT NOT NULL",
            "territory_id TEXT NOT NULL",
            "claimed_at INTEGER NOT NULL",
            "PRIMARY KEY (world, chunk_x, chunk_z)"
        );
        DatabaseManager.createTableIfNotExists("land_unclaimable_chunks",
            "world TEXT NOT NULL",
            "chunk_x INTEGER NOT NULL",
            "chunk_z INTEGER NOT NULL",
            "PRIMARY KEY (world, chunk_x, chunk_z)"
        );
        // NOT NULL DEFAULTを付けない = 未設定(NULL)が「エリア設定に従う」を表す（/land rule参照）
        DatabaseManager.addColumnIfNotExists("land_claims", "pvp_override INTEGER");
        DatabaseManager.addColumnIfNotExists("land_claims", "explosions_override INTEGER");
        DatabaseManager.addColumnIfNotExists("land_claims", "doors_override INTEGER");
        DatabaseManager.addColumnIfNotExists("land_claims", "chests_override INTEGER");

        DatabaseManager.createTableIfNotExists("land_territories",
            "territory_id TEXT PRIMARY KEY",
            "pvp_enabled INTEGER NOT NULL DEFAULT 0"
        );
        DatabaseManager.addColumnIfNotExists("land_territories", "explosions_allowed INTEGER NOT NULL DEFAULT 0");
        DatabaseManager.addColumnIfNotExists("land_territories", "doors_open INTEGER NOT NULL DEFAULT 0");
        DatabaseManager.addColumnIfNotExists("land_territories", "chests_open INTEGER NOT NULL DEFAULT 0");

        DatabaseManager.createTableIfNotExists("land_trusts",
            "territory_id TEXT NOT NULL",
            "trusted_uuid TEXT NOT NULL",
            "PRIMARY KEY (territory_id, trusted_uuid)"
        );

        // 行が存在する = 表示ON。/land border と /chunkborder は排他なので1人1行で足りる。
        DatabaseManager.createTableIfNotExists("land_border_displays",
            "uuid TEXT PRIMARY KEY",
            "mode TEXT NOT NULL",
            "radius INTEGER NOT NULL"
        );

        DatabaseManager.createTableIfNotExists("headshop_pool",
            "id INTEGER PRIMARY KEY AUTOINCREMENT",
            "display_name TEXT NOT NULL",
            "texture TEXT NOT NULL",
            "added_by TEXT NOT NULL",
            "added_at INTEGER NOT NULL"
        );

        DatabaseManager.createTableIfNotExists("headshop_rotation",
            "date TEXT NOT NULL",
            "pool_id INTEGER NOT NULL",
            "PRIMARY KEY (date, pool_id)"
        );
        DatabaseManager.createTableIfNotExists("container_locks", "lock_id TEXT PRIMARY KEY", "owner_uuid TEXT NOT NULL", "created_at INTEGER NOT NULL");
        DatabaseManager.createTableIfNotExists("container_lock_blocks", "world TEXT NOT NULL", "x INTEGER NOT NULL", "y INTEGER NOT NULL", "z INTEGER NOT NULL", "lock_id TEXT NOT NULL", "PRIMARY KEY (world, x, y, z)");
        DatabaseManager.createTableIfNotExists("container_lock_members", "lock_id TEXT NOT NULL", "member_uuid TEXT NOT NULL", "PRIMARY KEY (lock_id, member_uuid)");

        this.afkManager = new AfkManager(this);
        this.playtimeManager = new PlaytimeManager(this);
        this.rankManager = new RankManager(this);
        this.homeManager = new HomeManager(this);
        this.warpManager = new WarpManager(this);
        this.worldResetManager = new WorldResetManager(this);
        this.headshopManager = new HeadshopManager(this);
        this.vanishManager = new VanishManager(this);
        this.kikoriManager = new KikoriManager(this);
        this.mineManager = new MineManager(this);
        this.features = List.of(new KikoriFeature(kikoriManager), new MineFeature(mineManager));

        this.discordBotManager = new DiscordBotManager(this);

        this.muteManager = new MuteManager(this);
        muteManager.loadAll();
        this.privateMessageManager = new PrivateMessageManager(this);

        this.actionBarManager = new ActionBarManager(this);
        if (configManager.getBoolean("action-bar.enabled", true)) {
            long actionBarInterval = configManager.getInt("action-bar.update-interval-ticks", 5);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> actionBarManager.tick(), actionBarInterval, actionBarInterval);

            if (configManager.getBoolean("action-bar.persistent.enabled", false)) {
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                    String template = configManager.getString("action-bar.persistent.template", "");
                    for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                        String resolved = placeholderManager.resolve(template, online);
                        actionBarManager.setChannel(online, "persistent", org.craftcore.stellaria.utils.ColorUtil.component(resolved));
                    }
                }, actionBarInterval, actionBarInterval);
            }
        }

        this.bossBarManager = new BossBarManager(this);
        if (configManager.getBoolean("boss-bar.enabled", true)) {
            long bossBarInterval = configManager.getInt("boss-bar.update-interval-ticks", 5);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> bossBarManager.tick(), bossBarInterval, bossBarInterval);
        }

        // 2. EconomyManager のインスタンス化
        this.economyManager = new EconomyManager(this);
        // LandManagerはEconomyManagerに依存しないが、将来の拡張に備えて構築後に置く
        this.landManager = new LandManager(this);
        this.containerLockManager = new ContainerLockManager(this);
        this.landBorderParticleManager = new LandBorderParticleManager(this);

        // 3. Vaultがサーバーにあるか確認し、登録する処理
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            // Vaultのマネージャー（ServicesManager）に「うちのお金システムを使ってね」と登録する
            getServer().getServicesManager().register(
                Economy.class,
                this.economyManager,
                this,
                ServicePriority.Normal
            );
            getLogger().info("Vault への経済システムの登録に成功");
        } else {
            getLogger().warning("Vault が見つかりませんでした。Vault連携機能は無効化されます。");
        }

        // 4. イベントリスナー登録
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this), this);
        if (configManager.getBoolean("vote.enabled", true)) {
            if (getServer().getPluginManager().getPlugin("NuVotifier") != null) {
                getServer().getPluginManager().registerEvents(new VoteListener(this), this);
            } else {
                getLogger().warning("NuVotifier が見つかりませんでした。投票報酬機能は無効化されます。");
            }
        }

        // 5. 起動ロゴ表示
        // Initialize managers
        // PluginManager.getInstance().initialize();

        // Register listeners
        // getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        this.elevatorManager = new ElevatorManager(this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, elevatorManager), this);
        getServer().getPluginManager().registerEvents(new KikoriListener(this), this);
        getServer().getPluginManager().registerEvents(new MineListener(this), this);
        getServer().getPluginManager().registerEvents(new LandProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new ContainerLockListener(this), this);
        getServer().getPluginManager().registerEvents(new LandAreaStatusListener(this), this);
        getServer().getPluginManager().registerEvents(new VanishListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldResetListener(this), this);

        // 6. Scoreboard/Tablist/Belowname のインスタンス化とtick開始
        this.placeholderManager = new PlaceholderManager(this);

        this.scoreboardManager = new ScoreboardManager(
            placeholderManager,
            configManager.getString("scoreboard.title", ""),
            configManager.getStringList("scoreboard.lines"),
            configManager.getBoolean("scoreboard.hide-numbers", true)
        );
        this.tabListManager = new TabListManager(
            placeholderManager,
            rankManager,
            configManager.getString("tablist.header", ""),
            configManager.getString("tablist.footer", ""),
            configManager.getString("tablist.value", ""),
            configManager.getString("tablist.rank-prefix", "|")
        );
        this.belownameManager = new BelownameManager(
            placeholderManager,
            configManager.getString("belowname.title", ""),
            configManager.getString("belowname.value", "%health%")
        );
        this.nametagManager = new NametagManager(
            rankManager,
            configManager.getBoolean("nametag.enabled", true),
            configManager.getString("nametag.dot-symbol", "●")
        );

        long scoreboardInterval = configManager.getInt("scoreboard.update-interval-ticks", 20);
        long tabListInterval = configManager.getInt("tablist.update-interval-ticks", 20);
        long belownameInterval = configManager.getInt("belowname.update-interval-ticks", 20);
        long nametagInterval = configManager.getInt("nametag.update-interval-ticks", 20);

        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> scoreboardManager.tick(), scoreboardInterval, scoreboardInterval);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> tabListManager.tick(), tabListInterval, tabListInterval);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> belownameManager.tick(), belownameInterval, belownameInterval);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> nametagManager.tick(), nametagInterval, nametagInterval);

        if (configManager.getBoolean("afk.enabled", true)) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> afkManager.tick(), 200L, 200L);
        }

        if (configManager.getBoolean("kikori.enabled", true)) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> kikoriManager.tick(), 200L, 200L);
        }

        if (configManager.getBoolean("mine.enabled", true)) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> mineManager.tick(), 200L, 200L);
        }

        if (configManager.getBoolean("discord.bot.enabled",true)){
            discordBotManager.startBot();
        }
        long landBorderInterval = Math.max(1L, configManager.getInt(
                "land.border-particle.toggle-interval-ticks", 20));
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this,
                task -> landBorderParticleManager.tick(), landBorderInterval, landBorderInterval);

        // 7. チャットフォーマット・メンション
        this.mentionService = new MentionService(this);
        if (configManager.getBoolean("chat.enabled", true)) {
            getServer().getPluginManager().registerEvents(new ChatListener(this, mentionService), this);
            getServer().getPluginManager().registerEvents(new MentionTabCompleteListener(), this);
        }

        TpaCore tpaCore = new TpaCore(this);
        getServer().getPluginManager().registerEvents(tpaCore, this);

        getCommand("tpa").setExecutor(tpaCore);
        getCommand("tpaccept").setExecutor(tpaCore);
        getCommand("tpdeny").setExecutor(tpaCore);
        getCommand("tphere").setExecutor(tpaCore);
        getCommand("tphaccept").setExecutor(tpaCore);
        getCommand("tphdeny").setExecutor(tpaCore);
        for (String tpaCommand : new String[]{"tpa", "tpaccept", "tpdeny", "tphere", "tphaccept", "tphdeny"}) {
            getCommand(tpaCommand).setTabCompleter(tpaCore);
        }

        getCommand("stellariareload").setExecutor(new ReloadCommand(this));

        getCommand("afk").setExecutor(new AfkCommand(this));

        HealCommand healCommand = new HealCommand(this);
        getCommand("heal").setExecutor(healCommand);
        getCommand("heal").setTabCompleter(healCommand);

        getCommand("broadcast").setExecutor(new BroadcastCommand(this));

        MuteCommand muteCommand = new MuteCommand(this);
        getCommand("mute").setExecutor(muteCommand);
        getCommand("unmute").setExecutor(muteCommand);
        getCommand("mute").setTabCompleter(muteCommand);
        getCommand("unmute").setTabCompleter(muteCommand);
        getServer().getPluginManager().registerEvents(new MuteCommandBlockListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(new MenuItemListener(this), this);

        MessageCommand messageCommand = new MessageCommand(this);
        getCommand("msg").setExecutor(messageCommand);
        getCommand("reply").setExecutor(messageCommand);
        getCommand("msg").setTabCompleter(messageCommand);
        getCommand("reply").setTabCompleter(messageCommand);

        PayCommand payCommand = new PayCommand(this);
        getCommand("pay").setExecutor(payCommand);
        getCommand("pay").setTabCompleter(payCommand);

        EcoCommand ecoCommand = new EcoCommand(this);
        getCommand("eco").setExecutor(ecoCommand);
        getCommand("eco").setTabCompleter(ecoCommand);

        BalanceCommand balanceCommand = new BalanceCommand(this);
        getCommand("balance").setExecutor(balanceCommand);
        getCommand("balance").setTabCompleter(balanceCommand);

        getCommand("colors").setExecutor(new ColorsCommand(this));

        getCommand("adminshop").setExecutor(new AdminShopCommand(this));

        getCommand("discord").setExecutor(new DiscordCommand(this));

        getCommand("map").setExecutor(new MapCommand(this));

        HomepageCommand homepageCommand = new HomepageCommand(this);
        getCommand("homepage").setExecutor(homepageCommand);
        getCommand("homepage").setTabCompleter(homepageCommand);

        getCommand("enderchest").setExecutor(new EnderChestCommand(this));

        getCommand("vote").setExecutor(new VoteCommand(this));

        PlaytimeCommand playtimeCommand = new PlaytimeCommand(this);
        getCommand("playtime").setExecutor(playtimeCommand);
        getCommand("playtime").setTabCompleter(playtimeCommand);

        SeenCommand seenCommand = new SeenCommand(this);
        getCommand("seen").setExecutor(seenCommand);
        getCommand("seen").setTabCompleter(seenCommand);

        WorldCommand worldCommand = new WorldCommand(this);
        getCommand("world").setExecutor(worldCommand);
        getCommand("world").setTabCompleter(worldCommand);

        ProfileCommand profileCommand = new ProfileCommand(this);
        getCommand("profile").setExecutor(profileCommand);
        getCommand("profile").setTabCompleter(profileCommand);

        getCommand("settings").setExecutor(new SettingsCommand(this));

        RankingCommand rankingCommand = new RankingCommand(this);
        getCommand("ranking").setExecutor(rankingCommand);
        getCommand("ranking").setTabCompleter(rankingCommand);

        ScoreboardCommand scoreboardCommand = new ScoreboardCommand(this);
        getCommand("scoreboard").setExecutor(scoreboardCommand);
        getCommand("scoreboard").setTabCompleter(scoreboardCommand);

        HomeCommand homeCommand = new HomeCommand(this);
        for (String homeCmd : new String[]{"sethome", "home", "delhome", "homes"}) {
            getCommand(homeCmd).setExecutor(homeCommand);
            getCommand(homeCmd).setTabCompleter(homeCommand);
        }

        WarpCommand warpCommand = new WarpCommand(this);
        for (String warpCmd : new String[]{"setwarp", "warp", "delwarp", "warps"}) {
            getCommand(warpCmd).setExecutor(warpCommand);
            getCommand(warpCmd).setTabCompleter(warpCommand);
        }

        KikoriCommand kikoriCommand = new KikoriCommand(this);
        getCommand("kikori").setExecutor(kikoriCommand);
        getCommand("kikori").setTabCompleter(kikoriCommand);

        MineCommand mineCommand = new MineCommand(this);
        getCommand("mine").setExecutor(mineCommand);
        getCommand("mine").setTabCompleter(mineCommand);

        FeaturesCommand featuresCommand = new FeaturesCommand(this, features);
        getCommand("features").setExecutor(featuresCommand);
        getCommand("features").setTabCompleter(featuresCommand);

        getCommand("menu").setExecutor(new MenuCommand(this));

        getCommand("menuitem").setExecutor(new MenuItemCommand(this));

        getCommand("tphelp").setExecutor(new TpHelpCommand(this));

        WeatherVoteCommand weatherVoteCommand = new WeatherVoteCommand(this);
        getCommand("weathervote").setExecutor(weatherVoteCommand);
        getCommand("weathervote").setTabCompleter(weatherVoteCommand);
        getCommand("wvaccept").setExecutor(weatherVoteCommand);
        getCommand("wvdeny").setExecutor(weatherVoteCommand);

        TimeVoteCommand timeVoteCommand = new TimeVoteCommand(this);
        getCommand("timevote").setExecutor(timeVoteCommand);
        getCommand("timevote").setTabCompleter(timeVoteCommand);
        getCommand("tvaccept").setExecutor(timeVoteCommand);
        getCommand("tvdeny").setExecutor(timeVoteCommand);
      
        LandCommand landCommand = new LandCommand(this);
        getCommand("land").setExecutor(landCommand);
        getCommand("land").setTabCompleter(landCommand);

        ChunkBorderCommand chunkBorderCommand = new ChunkBorderCommand(this);
        getCommand("chunkborder").setExecutor(chunkBorderCommand);
        getCommand("chunkborder").setTabCompleter(chunkBorderCommand);

        HeadshopCommand headshopCommand = new HeadshopCommand(this);
        getCommand("headshop").setExecutor(headshopCommand);
        getCommand("headshop").setTabCompleter(headshopCommand);

        getCommand("vanish").setExecutor(new VanishCommand(this));
        LockCommand lockCommand = new LockCommand(this);
        getCommand("lock").setExecutor(lockCommand);
        getCommand("lock").setTabCompleter(lockCommand);
        getCommand("unlock").setExecutor(lockCommand);
        WorldResetCommand worldResetCommand = new WorldResetCommand(this);
        getCommand("worldreset").setExecutor(worldResetCommand);
        getCommand("worldreset").setTabCompleter(worldResetCommand);

        SudoCommand sudoCommand = new SudoCommand(this);
        getCommand("sudo").setExecutor(sudoCommand);
        getCommand("sudo").setTabCompleter(sudoCommand);

        this.autoBroadcastManager = new AutoBroadcastManager(this);
        autoBroadcastManager.start();

        headshopManager.start();
        worldResetManager.start();

        ConsoleUtil.printLogo(getPluginMeta().getVersion());
    }

    @Override
    public void onDisable() {
        if (configManager.getBoolean("discord.bot.enabled",true)){
            discordBotManager.stop();
        }
        // プラグイン停止時は Vault から自動解除されるため、DB切断だけでOK
        DatabaseManager.disconnect();
        ConsoleUtil.printDisabledMessage();
    }
    
    public EconomyManager getEconomyManager() {
        return this.economyManager;
    }

    public ConfigManager getConfigManager() {
        return this.configManager;
    }

    public PlaceholderManager getPlaceholderManager() {
        return this.placeholderManager;
    }

    public ScoreboardManager getScoreboardManager() {
        return this.scoreboardManager;
    }

    public TabListManager getTabListManager() {
        return this.tabListManager;
    }

    public BelownameManager getBelownameManager() {
        return this.belownameManager;
    }

    public MentionService getMentionService() {
        return this.mentionService;
    }

    public AfkManager getAfkManager() {
        return this.afkManager;
    }

    public MuteManager getMuteManager() {
        return this.muteManager;
    }

    public PrivateMessageManager getPrivateMessageManager() {
        return this.privateMessageManager;
    }

    public ActionBarManager getActionBarManager() {
        return this.actionBarManager;
    }

    public BossBarManager getBossBarManager() {
        return this.bossBarManager;
    }

    public PlaytimeManager getPlaytimeManager() {
        return this.playtimeManager;
    }

    public RankManager getRankManager() {
        return this.rankManager;
    }

    public HomeManager getHomeManager() {
        return this.homeManager;
    }

    public WarpManager getWarpManager() {
        return this.warpManager;
    }

    public HeadshopManager getHeadshopManager() {
        return this.headshopManager;
    }

    public VanishManager getVanishManager() {
        return this.vanishManager;
    }

    public NametagManager getNametagManager() {
        return this.nametagManager;
    }

    public KikoriManager getKikoriManager() {
        return this.kikoriManager;
    }

    public MineManager getMineManager() {
        return this.mineManager;
    }

    public List<Feature> getFeatures() {
        return this.features;
    }

    public LandManager getLandManager() {
        return this.landManager;
    }

    public ContainerLockManager getContainerLockManager() {
        return this.containerLockManager;
    }

    public DiscordBotManager getDiscordBotManager() {
        return this.discordBotManager;
    }
  
    public LandBorderParticleManager getLandBorderParticleManager() {
        return this.landBorderParticleManager;
    }

    public WorldResetManager getWorldResetManager() {
        return this.worldResetManager;
    }

    /**
     * config.yml の scoreboard/tablist/belowname 設定を読み直して各Managerに反映する。
     * ConfigManager#reload() で config.yml 自体を読み直した後に呼ぶ想定（ReloadCommand参照）。
     */
    public void reloadFeatureManagers() {
        scoreboardManager.updateSettings(
            configManager.getString("scoreboard.title", ""),
            configManager.getStringList("scoreboard.lines"),
            configManager.getBoolean("scoreboard.hide-numbers", true)
        );
        tabListManager.updateSettings(
            configManager.getString("tablist.header", ""),
            configManager.getString("tablist.footer", ""),
            configManager.getString("tablist.value", ""),
            configManager.getString("tablist.rank-prefix", "|")
        );
        belownameManager.updateSettings(
            configManager.getString("belowname.title", ""),
            configManager.getString("belowname.value", "%health%")
        );
        nametagManager.updateSettings(
            configManager.getBoolean("nametag.enabled", true),
            configManager.getString("nametag.dot-symbol", "●")
        );
        autoBroadcastManager.restart();
        worldResetManager.restart();
        rankManager.reload();
    }
}
