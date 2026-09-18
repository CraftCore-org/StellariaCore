package org.craftcore.stellaria.managers;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.listeners.DiscordListener;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.DiscordCommandCleanupUtil;
import org.craftcore.stellaria.utils.DiscordEmbedUtil;
import org.craftcore.stellaria.utils.DiscordTextUtil;
import org.craftcore.stellaria.utils.DurationParser;
import org.craftcore.stellaria.utils.YamlScalarPatcher;

import java.awt.Color;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Discord bot lifecycle, webhook relay, and guild-scoped Discord commands. */
public class DiscordBotManager {

    private static final String WEBHOOK_NAME = "StellariaCore";
    // Embedの左サイドカラー。原色だと主張が強すぎるため、パステル寄りの優しい色調にしている。
    private static final Color SOFT_BLUE = new Color(0x7E, 0xA6, 0xD8);
    private static final Color SOFT_CYAN = new Color(0x8F, 0xD1, 0xCE);
    private static final Color SOFT_GREEN = new Color(0x9B, 0xCF, 0x9B);
    private static final Color SOFT_RED = new Color(0xE0, 0x9A, 0x9A);
    private static final DateTimeFormatter LAST_LOGOUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final StellariaCore plugin;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, String> webhookUrls = new ConcurrentHashMap<>();
    private volatile JDA jda;
    private String token;
    private volatile ScheduledTask presenceTask;
    /** 起動途中の古いJDAが、再起動後にwebhookやpresenceを再登録しないための世代番号。 */
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    /** stop()が非同期起動シーケンス（awaitReady後）と競合しないためのガード。 */
    private volatile boolean shuttingDown = false;

    public DiscordBotManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public synchronized void startBot() {
        if (!plugin.getConfigManager().getBoolean("discord.bot.enabled", true)) {
            return;
        }
        if (jda != null) {
            return;
        }
        shuttingDown = false;
        long generation = lifecycleGeneration.incrementAndGet();
        token = plugin.getConfigManager().getString("discord.bot.token", "");
        if (token.isEmpty()) {
            plugin.getLogger().warning("botのtokenが指定されていません。");
            return;
        }
        try {
            jda = JDABuilder.createDefault(token)
                    .addEventListeners(new DiscordListener(plugin))
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .build();
        } catch (Exception e) {
            plugin.getLogger().warning("DiscordBotの起動に失敗しました: " + e.getMessage());
            jda = null;
            return;
        }

        JDA startingJda = jda;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                startingJda.awaitReady();
                synchronized (this) {
                    if (shuttingDown || lifecycleGeneration.get() != generation || jda != startingJda) {
                    // awaitReady()の完了を待っている間にstop()が呼ばれた場合、
                    // 既に無効化されたプラグイン/シャットダウン済みのJDAに対して
                    // 起動シーケンスを続行しない。
                        return;
                    }
                    initializeWebhooks();
                    registerPublicCommands();
                    startPresenceUpdates();
                    sendStartupLog();
                    plugin.getLogger().info("DiscordBotを起動しました。");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("DiscordBotの起動に失敗しました: " + e.getMessage());
                synchronized (this) {
                    if (lifecycleGeneration.get() == generation && jda == startingJda) {
                        stopInternal(false);
                    }
                }
            }
        });
    }

    public synchronized void stop() {
        stopInternal(true);
    }

    /** config.yml反映用。停止通知は送らず、enabledなら新設定で接続し直す。 */
    public synchronized void restartAfterConfigReload() {
        stopInternal(false);
        if (plugin.getConfigManager().getBoolean("discord.bot.enabled", true)) {
            startBot();
        }
    }

    private void stopInternal(boolean announceShutdown) {
        lifecycleGeneration.incrementAndGet();
        shuttingDown = true;
        try {
            if (announceShutdown && jda != null) {
                sendShutdownLog();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Discordへの停止通知に失敗しました: " + e.getMessage());
        } finally {
            if (presenceTask != null) {
                presenceTask.cancel();
                presenceTask = null;
            }
            webhookUrls.clear();
            if (jda != null) {
                jda.shutdown();
                jda = null;
            }
        }
    }

    public JDA getJda() {
        return jda;
    }

    private void initializeWebhooks() {
        if (jda == null) return;
        webhookUrls.clear();
        if (!plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id").isEmpty()) {
            plugin.getLogger().info("Discord Webhook中継を初期化します。Botには対象チャンネルのMANAGE_WEBHOOKS権限が必要です。");
        }
        for (String channelId : plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id")) {
            if (channelId.isBlank()) continue;
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                plugin.getLogger().warning("Discordのチャットチャンネルが見つかりません: " + channelId);
                continue;
            }
            try {
                Webhook webhook = channel.retrieveWebhooks().complete().stream()
                        .filter(existing -> WEBHOOK_NAME.equals(existing.getName()))
                        .findFirst()
                        .orElseGet(() -> channel.createWebhook(WEBHOOK_NAME).complete());
                webhookUrls.put(channelId, webhook.getUrl());
            } catch (Exception e) {
                plugin.getLogger().warning("Discord Webhookの取得/作成に失敗しました（BotにMANAGE_WEBHOOKS権限が必要です）: "
                        + channelId + " / " + e.getMessage());
            }
        }
    }

    public void mcChatToDiscord(AsyncChatEvent event) {
        if (!hasConfiguredGuild() || jda == null) return;
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        String content = plugin.getConfigManager().getString("discord.bot.serverchat-format", "")
                .replace("%player%", player.getName())
                .replace("%message%", message);
        String username = playerWebhookName(player);
        // crafatarはCloudflareのbot対策で画像取得に失敗しWebhookのアバターが崩れることがあるため、
        // Discord Webhook用途で実績のあるmc-heads.netを使う。
        String avatarUrl = "https://mc-heads.net/avatar/" + player.getUniqueId() + "/128";

        for (String channelId : plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id")) {
            String webhookUrl = webhookUrls.get(channelId);
            if (webhookUrl != null) postWebhook(webhookUrl, username, avatarUrl, content);
        }
    }

    private String playerWebhookName(Player player) {
        RankManager.RankInfo rank = plugin.getRankManager().getRank(player);
        String plainRankTag = PlainTextComponentSerializer.plainText().serialize(ColorUtil.component(rank.tablistTag()));
        return plugin.getConfigManager().getString("discord.bot.player-name-format", "[%rank_tag%] %player%")
                .replace("%rank_tag%", plainRankTag)
                .replace("%player%", player.getName());
    }

    private void postWebhook(String webhookUrl, String username, String avatarUrl, String content) {
        String json = "{\"username\":" + jsonString(username)
                + ",\"avatar_url\":" + jsonString(avatarUrl)
                + ",\"content\":" + jsonString(content)
                + ",\"allowed_mentions\":{\"parse\":[]}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Discord Webhook送信に失敗しました: " + error.getMessage());
            } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                plugin.getLogger().warning("Discord Webhook送信に失敗しました: HTTP " + response.statusCode());
            }
        });
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private void startPresenceUpdates() {
        if (jda == null) return;
        updatePresence();
        presenceTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> updatePresence(), 1200L, 1200L);
    }

    private void updatePresence() {
        if (jda == null) return;
        String status = plugin.getConfigManager().getString("discord.bot.status-format", "%online%/%max_online% online")
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max_online%", String.valueOf(Bukkit.getServer().getMaxPlayers()));
        jda.getPresence().setActivity(Activity.playing(status));
    }

    private void registerPublicCommands() {
        String serverGuildId = plugin.getConfigManager().getString("discord.bot.server-guild-id", "");
        if (serverGuildId.isBlank()) {
            plugin.getLogger().warning("discord.bot.server-guild-id が未設定のため /players と /profile と /settings は登録しません。");
            return;
        }
        Guild guild = guildById(serverGuildId, "一般");
        if (guild == null) return;
        removeRetiredCommands(guild);
        guild.upsertCommand(Commands.slash("players", "オンラインのMinecraftプレイヤー一覧"))
                .queue(null, error -> plugin.getLogger().warning("/players の登録に失敗しました: " + error.getMessage()));
        guild.upsertCommand(Commands.slash("profile", "Minecraftプレイヤー情報を確認します")
                        .addOption(OptionType.STRING, "player", "Minecraftプレイヤー名", true))
                .queue(null, error -> plugin.getLogger().warning("/profile の登録に失敗しました: " + error.getMessage()));
        guild.upsertCommand(Commands.slash("settings", "Discord連携の設定を行います")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                        .addSubcommands(new SubcommandData("chat-channel", "このチャンネルをMinecraftチャットの中継先に設定します")))
                .queue(null, error -> plugin.getLogger().warning("/settings の登録に失敗しました: " + error.getMessage()));
    }

    private void removeRetiredCommands(Guild guild) {
        guild.retrieveCommands().queue(commands -> commands.stream()
                        .filter(command -> DiscordCommandCleanupUtil.isRetiredCommand(command.getName()))
                        .forEach(command -> guild.deleteCommandById(command.getId()).queue(
                                ignored -> plugin.getLogger().info("旧Discordコマンドを削除しました: /" + command.getName()),
                                error -> plugin.getLogger().warning("旧Discordコマンドの削除に失敗しました: /"
                                        + command.getName() + " / " + error.getMessage()))),
                error -> plugin.getLogger().warning("Discordコマンド一覧の取得に失敗しました: " + error.getMessage()));
    }

    private Guild guildById(String guildId, String description) {
        try {
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) plugin.getLogger().warning(description + "Guildが見つかりません: " + guildId);
            return guild;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(description + "Guild IDが不正です: " + guildId);
            return null;
        }
    }

    /** DiscordListenerから委譲されるスラッシュコマンド処理。 */
    public void handleSlashCommand(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "players" -> handlePlayers(event);
            case "profile" -> handleProfile(event);
            case "settings" -> handleSettings(event);
            default -> { }
        }
    }

    private void handlePlayers(SlashCommandInteractionEvent event) {
        if (!isServerGuild(event)) {
            event.reply("このコマンドはこのGuildでは利用できません。").setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue(hook -> Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
            EmbedBuilder embed = new EmbedBuilder().setTitle("オンラインプレイヤー").setColor(SOFT_CYAN);
            embed.setDescription(players.isEmpty() ? "誰もいません" : String.join(", ", players));
            embed.setFooter(players.size() + "人オンライン");
            hook.editOriginalEmbeds(embed.build()).queue();
        }));
    }

    private void handleProfile(SlashCommandInteractionEvent event) {
        if (!isServerGuild(event)) {
            event.reply("このコマンドはこのGuildでは利用できません。").setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue(hook -> Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            String requestedName = event.getOption("player").getAsString();
            OfflinePlayer target = Bukkit.getOfflinePlayer(requestedName);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                hook.editOriginal("プレイヤーが見つかりません。").queue();
                return;
            }
            long playtime = plugin.getPlaytimeManager().getPlaytimeSeconds(target.getUniqueId());
            long lastLogout = plugin.getPlaytimeManager().getLastLogout(target.getUniqueId());
            String lastSeen = target.isOnline() ? "オンライン中"
                    : lastLogout > 0 ? LAST_LOGOUT_FORMAT.format(Instant.ofEpochMilli(lastLogout)) : "記録なし";
            String balance = plugin.getEconomyManager().isHideBalance(target)
                    ? plugin.getConfigManager().getMessage("profile.balance_hidden", null)
                    : plugin.getEconomyManager().formatExact(plugin.getEconomyManager().getBalance(target));
            CompletableFuture<RankManager.RankInfo> rankFuture = plugin.getRankManager().getRankAsync(target);
            rankFuture.whenComplete((rank, error) -> {
                String rankName = error == null && rank != null && !rank.displayName().isBlank() ? rank.displayName() : "なし";
                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle((target.getName() != null ? target.getName() : requestedName) + " の情報")
                        .setColor(SOFT_BLUE)
                        .addField("ランク", rankName, true)
                        .addField("最終ログアウト", lastSeen, true)
                        .addField("プレイ時間", DurationParser.formatDuration(playtime), true)
                        .addField("所持金", DiscordTextUtil.plainMinecraftText(balance), true);
                hook.editOriginalEmbeds(embed.build()).queue();
            });
        }));
    }

    private boolean isServerGuild(SlashCommandInteractionEvent event) {
        return event.getGuild() != null && event.getGuild().getId()
                .equals(plugin.getConfigManager().getString("discord.bot.server-guild-id", ""));
    }

    private void handleSettings(SlashCommandInteractionEvent event) {
        if (!isServerGuild(event)) {
            event.reply("このコマンドはこのGuildでは利用できません。").setEphemeral(true).queue();
            return;
        }
        if (!"chat-channel".equals(event.getSubcommandName())) {
            event.reply("不明なサブコマンドです。").setEphemeral(true).queue();
            return;
        }
        String channelId = event.getChannel().getId();
        event.deferReply(true).queue(hook -> Bukkit.getGlobalRegionScheduler().execute(plugin,
                () -> handleSettingsChatChannelOnServerThread(channelId, hook)));
    }

    private void handleSettingsChatChannelOnServerThread(String channelId, InteractionHook hook) {
        List<String> value = List.of(channelId);
        plugin.getConfigManager().get("config.yml").get().set("discord.bot.serverchat-channel-id", value);
        boolean saved = YamlScalarPatcher.patch(plugin.getConfigManager().get("config.yml").getFile(),
                "discord.bot.serverchat-channel-id", value);
        if (!saved) {
            plugin.getLogger().warning("チャットチャンネル設定の保存に失敗しました。");
            hook.editOriginal("設定はメモリ上で変更されましたが、config.yml への保存に失敗しました。").queue();
            return;
        }
        initializeWebhooks();
        hook.editOriginal("このチャンネルをMinecraftチャットの中継先に設定しました。").queue();
    }

    public void sendPlayerJoinLog(PlayerJoinEvent event) {
        if (!hasConfiguredGuild() || jda == null) return;
        Player player = event.getPlayer();
        var embed = DiscordEmbedUtil.playerActivity(player.getName(),
                plugin.getConfigManager().getString("discord.bot.joinlog-format", "").replace("%player%", player.getName()),
                player.getUniqueId(), SOFT_GREEN);
        sendEmbedToChatChannels(embed);
    }

    public void sendPlayerQuitLog(PlayerQuitEvent event) {
        if (!hasConfiguredGuild() || jda == null) return;
        Player player = event.getPlayer();
        var embed = DiscordEmbedUtil.playerActivity(player.getName(),
                plugin.getConfigManager().getString("discord.bot.quitlog-format", "").replace("%player%", player.getName()),
                player.getUniqueId(), SOFT_RED);
        sendEmbedToChatChannels(embed);
    }

    private void sendStartupLog() {
        if (!hasConfiguredGuild() || jda == null) return;
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(plugin.getConfigManager().getString("discord.bot.serverstartlog-format", ""));
        embed.setColor(SOFT_GREEN);
        sendEmbedToChatChannels(embed);
    }

    private void sendShutdownLog() {
        if (!hasConfiguredGuild() || jda == null) return;
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(plugin.getConfigManager().getString("discord.bot.serverstoplog-format", ""));
        embed.setColor(SOFT_RED);
        sendEmbedToChatChannels(embed);
    }

    private void sendEmbedToChatChannels(EmbedBuilder embed) {
        sendEmbedToChatChannels(embed.build());
    }

    private void sendEmbedToChatChannels(net.dv8tion.jda.api.entities.MessageEmbed embed) {
        for (String channelId : plugin.getConfigManager().getStringList("discord.bot.serverchat-channel-id")) {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel != null) channel.sendMessageEmbeds(embed).queue();
        }
    }

    private boolean hasConfiguredGuild() {
        return !plugin.getConfigManager().getString("discord.bot.server-guild-id", "").isEmpty();
    }
}
