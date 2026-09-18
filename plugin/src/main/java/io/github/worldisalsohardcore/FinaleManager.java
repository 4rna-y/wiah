package io.github.worldisalsohardcore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * ハードコアの終わり方。{@code on-death: finale} のときの応答。
 *
 * <p>ワールドを最後まで残すための道。誰かが死んでもフォルダは消さず、キックもせず、
 * サーバーも止めない。代わりに参加中の全員の持ち物を空にしてアドベンチャーへ移し、
 * 死亡回数の成績表を配って企画を締める。
 *
 * <p><b>一度きりであること</b>: 終わったことは {@value #MARKER_FILE} に残す。
 * 発火済みの目印をメモリにだけ持つと、再起動した後にもう一度誰かが死んだときに
 * 全員の持ち物をもう一度消してしまう。データフォルダはワールドと違って消えないので、
 * ここに置けば再起動をまたいで「もう終わっている」と分かる。
 *
 * <p><b>死んだ本人</b>: ハードコアのワールドでは、復活しようとすると観戦者にされる。
 * そのため先に {@link World#setHardcore(boolean)} で解除してから復活させる。
 * 解除しないと、企画を終わらせた当人だけが幽霊のまま取り残される。
 *
 * <p>成績表そのものは持っていない。死亡回数を持っているのは DeathCounter なので、
 * 本を配るのは向こうの {@code /result} に任せる ({@code finale.result-command})。
 */
public final class FinaleManager implements DeathResponse, Listener {

    /** ハードコアが終わったことを残すファイル。 */
    static final String MARKER_FILE = "finale-done.txt";

    /** 死亡から終了処理までの既定の猶予 (秒)。 */
    static final long DEFAULT_DELAY_SECONDS = 10L;

    /** 猶予中に出す既定のタイトル。 */
    static final String DEFAULT_TITLE = "<gold><bold>ハードコア終了";

    /** その下に出る既定の小さい方。 */
    static final String DEFAULT_SUBTITLE = "<gray><player> が死亡しました";

    /** 終了後に全員へ流す既定のチャット。 */
    static final String DEFAULT_MESSAGE =
            "<gold>ハードコアはここまでです。おつかれさまでした。"
            + "<newline><gray>ワールドはこのまま残します。アドベンチャーモードで見て回れます。";

    /** 成績表を配る既定のコマンド。DeathCounter の /result を叩く。 */
    static final String DEFAULT_RESULT_COMMAND =
            "result ハードコア終了|<player> が死亡|経過 <elapsed>";

    /** 終了後に来た人へ成績表を渡す既定のコマンド。 */
    static final String DEFAULT_JOIN_RESULT_COMMAND =
            "result --player <joiner> ハードコア終了|<player> が死亡|経過 <elapsed>";

    /** Embed の既定の題。 */
    static final String DEFAULT_EMBED_TITLE = "ハードコアが終了しました";

    /** 復活してから持ち物に手を入れるまでの待ち (tick)。入れ替わりの直後を触らないため。 */
    private static final long SETTLE_TICKS = 1L;

    /** 終了後に来た人へ本を渡すまでの待ち (tick)。画面が出来上がるのを待つ。 */
    private static final long JOIN_DELAY_TICKS = 20L;

    private final WorldIsAlsoHardcorePlugin plugin;
    /** 監視対象ワールドの一覧。reset と finale で共通なので {@link ResetManager} のものを使う。 */
    private final ResetManager worlds;
    /** 締めが進行中か。この起動のうちに二重で発火させないための錠でもある。 */
    private final AtomicBoolean finishing = new AtomicBoolean(false);
    /** 終了の処理を済ませた人の目印。プレイヤーのデータに残るので再ログインしても残る。 */
    private final NamespacedKey handled;

    /** 終了済みならその様子。まだなら null。 */
    private volatile Ending ending;

    public FinaleManager(WorldIsAlsoHardcorePlugin plugin, ResetManager worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.handled = new NamespacedKey(plugin, "finale-handled");
    }

    /**
     * ハードコアが終わったときの様子。
     *
     * @param player  最後に死亡した人 (コマンドで終わらせたならその実行者)
     * @param at      終わった時刻
     * @param elapsed ワールド生成からの経過時間。分からなければ null
     */
    record Ending(String player, Instant at, Duration elapsed) {
    }

    // ------------------------------------------------------------------ 終了済みかどうか

    /** 起動時に1回呼ぶ。前回までに終わっていればそれを読み込む。 */
    void load() {
        this.ending = readMarker();
        if (ending != null) {
            plugin.getLogger().info("ハードコアは既に終了しています ("
                    + ending.player() + " の死亡)。死亡してもワールドはリセットしません。");
        }
    }

    /** ハードコアは終わっているか。 */
    public boolean done() {
        return ending != null;
    }

    Ending ending() {
        return ending;
    }

    // ------------------------------------------------------------------ DeathResponse

    @Override
    public boolean handlesDeaths() {
        // 締めの最中は、2人目以降の死亡メッセージも流さない (画面はタイトル中)。
        // 終わってしまえば普通の死亡に戻す — アドベンチャーでも落ちれば死ぬので、
        // そこで黙られると何が起きたか分からなくなる。
        return !done() || finishing.get();
    }

    @Override
    public boolean clearsDeathDrops() {
        // どうせ全員の持ち物を空にするので、最後の1人の分だけ地面に残っても仕方がない。
        return true;
    }

    @Override
    public boolean trigger(ResetCause cause) {
        // 締めの最中かどうかを先に見る。記録は締めの前に書くので、順番を逆にすると
        // 進行中の2人目にも「既に終了している」と答えてしまう。
        if (!finishing.compareAndSet(false, true)) {
            plugin.getLogger().info("終了処理が既に進行中のため、" + cause.reason() + " は無視しました。");
            return false;
        }
        if (done()) {
            plugin.getLogger().info("ハードコアは既に終了しているため、" + cause.reason() + " は無視しました。");
            finishing.set(false);
            return false;
        }

        plugin.getLogger().warning(cause.reason() + " によりハードコアを終了します。");

        Ending record = new Ending(cause.subject(), Instant.now(),
                plugin.elapsedWorldTime().orElse(null));
        // キックも停止もしないが、猶予の途中で落ちても終了だったと分かるよう先に残す。
        writeMarker(record);
        this.ending = record;

        if (plugin.getConfig().getBoolean("finale.disable-hardcore", true)) {
            disableHardcore();
        }

        plugin.notifyDiscord(cause);

        // 死亡イベントの処理中に画面や持ち物を触ると不整合が起きうるので次 tick へ回す。
        Bukkit.getScheduler().runTask(plugin, () -> announce(record));
        return true;
    }

    // ------------------------------------------------------------------ 猶予とタイトル

    /** 猶予の始まり。全員へタイトルを出し、締めを予約する。 */
    private void announce(Ending record) {
        long seconds = Math.max(0L,
                plugin.getConfig().getLong("finale.delay-seconds", DEFAULT_DELAY_SECONDS));
        TagResolver tags = tags(record, seconds);

        showTitle(tags, seconds);

        String broadcastRaw = plugin.getConfig().getString("finale.broadcast-message", "");
        if (broadcastRaw != null && !broadcastRaw.isBlank()) {
            Bukkit.broadcast(MiniMessage.miniMessage().deserialize(broadcastRaw, tags));
        }

        plugin.getLogger().warning(seconds + "秒後に参加中の全員をアドベンチャーへ移します。");
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(record), seconds * 20L);
    }

    private void showTitle(TagResolver tags, long seconds) {
        String titleRaw = plugin.getConfig().getString("finale.title", DEFAULT_TITLE);
        if (titleRaw == null || titleRaw.isBlank()) {
            return;
        }
        MiniMessage mm = MiniMessage.miniMessage();
        String subtitleRaw = plugin.getConfig().getString("finale.subtitle", DEFAULT_SUBTITLE);
        Title title = Title.title(
                mm.deserialize(titleRaw, tags),
                subtitleRaw == null || subtitleRaw.isBlank()
                        ? Component.empty()
                        : mm.deserialize(subtitleRaw, tags),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(seconds),
                        Duration.ofMillis(500)));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
        }
    }

    // ------------------------------------------------------------------ 締め

    /**
     * 猶予の終わり。まず死んでいる人を立たせる。
     *
     * <p>持ち物と操作モードに手を入れるのはその次の tick。復活はプレイヤーの実体を
     * 入れ替えるので、同じ tick のうちに触ると入れ替わる前の方を触りかねない。
     */
    private void finish(Ending record) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) {
                revive(player);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> handOver(record), SETTLE_TICKS);
    }

    @SuppressWarnings("deprecation") // 強制的に復活させる API は Spigot 側にしかない
    private void revive(Player player) {
        player.spigot().respawn();
    }

    /** 持ち物を空にしてアドベンチャーへ移し、成績表を配る。 */
    private void handOver(Ending record) {
        int moved = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            convert(player);
            moved++;
        }
        plugin.getLogger().warning(moved + "人をアドベンチャーへ移しました。");

        String messageRaw = plugin.getConfig().getString("finale.message", DEFAULT_MESSAGE);
        if (messageRaw != null && !messageRaw.isBlank()) {
            Bukkit.broadcast(MiniMessage.miniMessage().deserialize(messageRaw, tags(record, 0L)));
        }

        dispatch(render(plugin.getConfig().getString("finale.result-command", DEFAULT_RESULT_COMMAND),
                record, null));

        // 締めはここまで。以後の死亡は普通の死亡に戻る。
        finishing.set(false);
    }

    /** 1人分の締め。持ち物を空にしてアドベンチャーへ移し、済んだ印を付ける。 */
    private void convert(Player player) {
        // 画面を開いたままだと、掴んでいる1個が手元に残る。
        player.closeInventory();
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.setGameMode(GameMode.ADVENTURE);
        player.getPersistentDataContainer().set(handled, PersistentDataType.BYTE, (byte) 1);
    }

    // ------------------------------------------------------------------ 後から来た人

    /**
     * 終了の瞬間にいなかった人がログインしたとき。
     *
     * <p>アドベンチャーへ移して成績表を渡すだけで、持ち物には触らない。その場にいなかった
     * 人の物まで消すのは行き過ぎだし、アドベンチャーでは元々ほとんど使えない。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Ending record = ending;
        if (record == null || !plugin.getConfig().getBoolean("finale.apply-on-join", true)) {
            return;
        }
        if (finishing.get()) {
            // 締めの最中に入ってきた人。この後の全員分の処理で一緒に面倒を見るので、
            // ここで本を渡すと直後の持ち物クリアで消える。
            return;
        }
        Player player = event.getPlayer();
        if (player.getPersistentDataContainer().has(handled, PersistentDataType.BYTE)) {
            return;
        }

        player.setGameMode(GameMode.ADVENTURE);
        player.getPersistentDataContainer().set(handled, PersistentDataType.BYTE, (byte) 1);
        plugin.getLogger().info(player.getName() + " は終了後の参加なのでアドベンチャーへ移しました。");

        String command = render(plugin.getConfig().getString("finale.join-result-command",
                DEFAULT_JOIN_RESULT_COMMAND), record, player.getName());
        if (command.isBlank()) {
            return;
        }
        // 入った直後は画面が出来上がっていないので少し待つ。
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                dispatch(command);
            }
        }, JOIN_DELAY_TICKS);
    }

    // ------------------------------------------------------------------ 成績表

    /**
     * 成績表を配るコマンドを叩く。
     *
     * <p>死亡回数を持っているのは DeathCounter なので、本を組むのも配るのも向こうに任せる。
     * 繋ぎがコマンド1本なので、DeathCounter が居なくてもこちらは止まらない。
     */
    private void dispatch(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        try {
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                plugin.getLogger().warning("成績表のコマンドが見つかりませんでした: " + command
                        + " (DeathCounter は入っていますか)");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().severe("成績表のコマンドが失敗しました: " + command
                    + " (" + e.getMessage() + ")");
        }
    }

    /**
     * コマンドの雛形を埋める。
     *
     * @param joiner 後から来た人の名前。全員へ配るときは null
     */
    static String render(String template, Ending record, String joiner) {
        if (template == null) {
            return "";
        }
        return template
                .replace("<player>", record == null || record.player() == null ? "" : record.player())
                .replace("<elapsed>", record == null || record.elapsed() == null
                        ? "不明" : WorldClock.formatElapsed(record.elapsed()))
                .replace("<joiner>", joiner == null ? "" : joiner)
                .strip();
    }

    private TagResolver tags(Ending record, long seconds) {
        return TagResolver.resolver(
                Placeholder.unparsed("player", record == null ? "" : record.player()),
                Placeholder.unparsed("seconds", String.valueOf(seconds)),
                Placeholder.unparsed("elapsed", record == null || record.elapsed() == null
                        ? "不明" : WorldClock.formatElapsed(record.elapsed())));
    }

    // ------------------------------------------------------------------ ハードコアの解除

    /**
     * 対象ワールドのハードコアを解く。
     *
     * <p>解かないと、死んだ本人が復活しようとしたときに観戦者にされてしまう。
     * {@code server.properties} も揃えておかないと、ワールドを作り直したときだけ
     * ハードコアに戻る。
     */
    private void disableHardcore() {
        for (World world : Bukkit.getWorlds()) {
            if (worlds.isManaged(world) && world.isHardcore()) {
                world.setHardcore(false);
                plugin.getLogger().info("ハードコアを解除しました: " + world.getName());
            }
        }
        ResetManager.setServerProperty("hardcore", "false", plugin.log());
    }

    // ------------------------------------------------------------------ 記録ファイル

    private void writeMarker(Ending record) {
        writeMarker(plugin.getDataFolder().toPath(), record, plugin.log());
    }

    /** 記録ファイルを書く。テストから直接叩けるよう、プラグインには触らせない。 */
    static void writeMarker(Path dataDirectory, Ending record, ResetManager.Log log) {
        Path marker = dataDirectory.resolve(MARKER_FILE);
        List<String> lines = new ArrayList<>();
        lines.add("# WorldIsAlsoHardcore: ハードコアはここで終了した。");
        lines.add("# このファイルがある間、対象ワールドで死亡してもリセットも終了処理もしない。");
        lines.add("player=" + record.player());
        lines.add("at=" + record.at().toEpochMilli());
        if (record.elapsed() != null) {
            lines.add("elapsed-seconds=" + record.elapsed().toSeconds());
        }
        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, lines, StandardCharsets.UTF_8);
            log.info("ハードコアの終了を記録しました: " + marker);
        } catch (IOException e) {
            // 書けなくても今回の終了処理は続ける。次回起動時に終了済みと分からなくなるだけ。
            log.error("ハードコアの終了を記録できませんでした: " + e.getMessage()
                    + " (再起動すると終了済みと分からなくなります)");
        }
    }

    private Ending readMarker() {
        return readMarker(plugin.getDataFolder().toPath(), plugin.log());
    }

    /** 記録ファイルを読む。無ければ null。テストから直接叩けるよう副作用を持たせない。 */
    static Ending readMarker(Path dataDirectory, ResetManager.Log log) {
        Path marker = dataDirectory.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        String player = "";
        Instant at = Instant.EPOCH;
        Duration elapsed = null;
        try {
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                String value = line.strip();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                int eq = value.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = value.substring(0, eq).strip();
                String body = value.substring(eq + 1).strip();
                switch (key) {
                    case "player" -> player = body;
                    case "at" -> at = Instant.ofEpochMilli(Long.parseLong(body));
                    case "elapsed-seconds" -> elapsed = Duration.ofSeconds(Long.parseLong(body));
                    default -> { }
                }
            }
        } catch (IOException | NumberFormatException e) {
            // 中身が読めなくても、ファイルがある以上は終了済み。そこだけは譲らない。
            log.error("ハードコアの終了の記録を読めませんでした: " + marker + " (" + e.getMessage()
                    + ") — 終了済みとして扱います。");
        }
        return new Ending(player, at, elapsed);
    }
}
