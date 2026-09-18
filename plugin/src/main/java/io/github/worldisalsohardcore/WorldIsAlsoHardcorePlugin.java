package io.github.worldisalsohardcore;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ハードコアワールドで誰か1人でも死亡したら、参加中の全員をキックしてワールドをリセットする。
 *
 * <p>稼働中のサーバーからは主ワールドを削除できないため、リセットは2段階で行う。
 * <ol>
 *   <li>死亡検知時: 全員をキックし、削除対象を予約ファイルへ書き出してサーバーを停止する。</li>
 *   <li>次回起動時 ({@link WorldIsAlsoHardcoreBootstrap}, ワールド設定が読まれる前):
 *       予約されたフォルダを削除する。削除されたワールドはサーバーが自動的に再生成する。</li>
 * </ol>
 */
public final class WorldIsAlsoHardcorePlugin extends JavaPlugin {

    static final long DEFAULT_TIMEOUT_SECONDS = 5L;

    /** {@code on-death} の値。ワールドを作り直す。 */
    static final String MODE_RESET = "reset";

    /** {@code on-death} の値。ワールドを残したままハードコアを終える。 */
    static final String MODE_FINALE = "finale";

    private ResetManager resetManager;
    private FinaleManager finaleManager;
    private DiscordNotifier notifier;
    private Duration flushTimeout = Duration.ofSeconds(DiscordNotifier.DEFAULT_FLUSH_TIMEOUT_SECONDS);
    private final ResetManager.Log log = new PluginLog();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.resetManager = new ResetManager(this);
        this.finaleManager = new FinaleManager(this, resetManager);
        finaleManager.load();

        getServer().getPluginManager().registerEvents(
                new DeathListener(resetManager, this::deathResponse), this);
        // 終了済みなら、後から来た人もアドベンチャーへ移す。on-death の値には依らない
        // (一度終わったハードコアは、設定を reset に戻しても終わったまま)。
        getServer().getPluginManager().registerEvents(finaleManager, this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("wiahc", "WorldIsAlsoHardcore の管理コマンド",
                        new WiahcCommand(this, resetManager, finaleManager)));

        startNotifier();
        resetManager.warnAboutConfiguration();
    }

    @Override
    public void onDisable() {
        stopNotifier();
    }

    public ResetManager resetManager() {
        return resetManager;
    }

    public FinaleManager finaleManager() {
        return finaleManager;
    }

    /**
     * 今の設定で死亡に応答するのはどちらか。
     *
     * <p>{@code reload} で切り替わるので、掴んだまま持たずに毎回引くこと。
     */
    DeathResponse deathResponse() {
        return finaleMode() ? finaleManager : resetManager;
    }

    /** {@code on-death: finale} か。ワールドを消さずにハードコアを終える方。 */
    boolean finaleMode() {
        String mode = getConfig().getString("on-death", MODE_RESET);
        return MODE_FINALE.equalsIgnoreCase(mode == null ? "" : mode.strip());
    }

    /** ハードコアは既に終わっているか。 */
    boolean finaleDone() {
        return finaleManager != null && finaleManager.done();
    }

    /** {@link DiscordNotifier} や {@link WorldClock} へ渡すログの口。 */
    ResetManager.Log log() {
        return log;
    }

    /** Discord への通知口。{@code discord.enabled: false} や設定の誤りで動いていない間は空。 */
    public Optional<DiscordNotifier> notifier() {
        return Optional.ofNullable(notifier);
    }

    /** config.yml を読み直し、Discord への接続を作り直す。 */
    public void reload() {
        reloadConfig();
        stopNotifier();
        startNotifier();
    }

    /** リセット (または終了) を Discord へ流す。通知が無効なら何もしない。 */
    void notifyDiscord(ResetCause cause) {
        if (notifier == null) {
            return;
        }
        notifier.notifyReset(cause, elapsedWorldTime().orElse(null), Instant.now(),
                embedTitleOverride());
    }

    /**
     * Embed の題の差し替え。差し替えないなら null。
     *
     * <p>finale モードで起きるのはリセットではないので、「ワールドがリセットされました」
     * のまま送ると嘘になる。
     */
    private String embedTitleOverride() {
        if (!finaleMode()) {
            return null;
        }
        String title = getConfig().getString("finale.embed-title", FinaleManager.DEFAULT_EMBED_TITLE);
        return title == null || title.isBlank() ? null : title;
    }

    /** ワールド生成からの経過時間。記録が無ければ空。 */
    Optional<Duration> elapsedWorldTime() {
        return WorldClock.elapsed(getDataFolder().toPath(), Instant.now());
    }

    // ------------------------------------------------------------------ 組み立て

    private void startNotifier() {
        if (!getConfig().getBoolean("discord.enabled", true)) {
            getLogger().info("discord.enabled: false のため Discord へは通知しません。");
            return;
        }
        String url = getConfig().getString("discord.webhook-url", "");
        if (url == null || url.isBlank()) {
            getLogger().warning("discord.webhook-url が空です。Discord へは通知しません。");
            return;
        }

        URI webhook;
        try {
            webhook = new URI(url);
        } catch (URISyntaxException e) {
            getLogger().severe("discord.webhook-url が URL として読めません — Discord へは通知しません。");
            return;
        }

        this.flushTimeout = Duration.ofSeconds(getConfig().getLong("discord.flush-timeout-seconds",
                DiscordNotifier.DEFAULT_FLUSH_TIMEOUT_SECONDS));

        DiscordWebhookClient client = new DiscordWebhookClient(webhook,
                Duration.ofSeconds(getConfig().getLong("discord.timeout-seconds", DEFAULT_TIMEOUT_SECONDS)));
        this.notifier = new DiscordNotifier(client, getDataFolder().toPath(), notifierSettings(), log);

        getLogger().info("Discord への通知先: " + client.describe());
        // 前回の停止までに送り切れなかった通知をここで片付ける。
        notifier.resendPending();
    }

    private DiscordNotifier.Settings notifierSettings() {
        return new DiscordNotifier.Settings(
                orDefault(getConfig().getString("discord.embed-title"), DiscordNotifier.DEFAULT_EMBED_TITLE),
                getConfig().getInt("discord.embed-color", DiscordNotifier.DEFAULT_EMBED_COLOR),
                orDefault(getConfig().getString("discord.username"), ""),
                orDefault(getConfig().getString("discord.head-image-url"),
                        DiscordNotifier.DEFAULT_HEAD_IMAGE_URL),
                orDefault(getConfig().getString("discord.footer"), ""));
    }

    private void stopNotifier() {
        if (notifier == null) {
            return;
        }
        // 停止までに送信を終わらせる。死亡の数秒後にサーバーが止まるので、
        // ここで待たないとリセットの通知そのものを取りこぼす。
        notifier.close(flushTimeout);
        notifier = null;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** {@link DiscordNotifier} や {@link WorldClock} のログをサーバーのログへ流す。 */
    private final class PluginLog implements ResetManager.Log {

        @Override
        public void info(String message) {
            getLogger().info(message);
        }

        @Override
        public void error(String message) {
            getLogger().severe(message);
        }
    }
}
