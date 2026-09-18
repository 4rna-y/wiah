package io.github.worldisalsohardcore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** リセットの予約・実行を担当する。 */
public final class ResetManager implements DeathResponse {

    /** 次回起動時に削除するフォルダを記録する予約ファイル。 */
    static final String MARKER_FILE = "pending-reset.txt";

    private static final String SERVER_PROPERTIES = "server.properties";

    /** 死亡から全員キックまでの既定の猶予 (秒)。 */
    static final long DEFAULT_RESET_DELAY_SECONDS = 10L;

    /** 猶予中に出す既定のタイトル。<seconds> は上の既定値に置き換わる。 */
    static final String DEFAULT_TITLE = "<red><bold>サーバーは<seconds>秒後に削除されます";

    private final WorldIsAlsoHardcorePlugin plugin;
    /** 同一 tick に複数人が死亡しても1回しか発火させない。 */
    private final AtomicBoolean triggered = new AtomicBoolean(false);

    public ResetManager(WorldIsAlsoHardcorePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ 監視対象

    /** 対象ワールド名。config が空なら主ワールドとその nether / the_end。 */
    public Set<String> managedWorldNames() {
        List<String> configured = plugin.getConfig().getStringList("worlds");
        if (!configured.isEmpty()) {
            return new LinkedHashSet<>(configured);
        }
        List<World> loaded = Bukkit.getWorlds();
        if (loaded.isEmpty()) {
            return Set.of();
        }
        String main = loaded.get(0).getName();
        return new LinkedHashSet<>(List.of(main, main + "_nether", main + "_the_end"));
    }

    public boolean isManaged(World world) {
        return managedWorldNames().contains(world.getName());
    }

    // ------------------------------------------------------------------ 発火

    @Override
    public boolean handlesDeaths() {
        // 発火した後もキックまでの数秒は死亡しうる。その分の死亡メッセージも
        // 流さない (画面にはタイトルが出ている)。
        return true;
    }

    @Override
    public boolean clearsDeathDrops() {
        // ワールドごと消えるので、地面に何が残っていても関係ない。
        return false;
    }

    /**
     * リセットを予約して全員をキックし、サーバーを停止する。
     *
     * @param cause 発火の理由。文面と Discord への通知はここから作る
     * @return 実際に発火したら true。すでに発火済みなら false。
     */
    @Override
    public boolean trigger(ResetCause cause) {
        String subject = cause.subject();
        String reason = cause.reason();
        if (!triggered.compareAndSet(false, true)) {
            plugin.getLogger().info("リセットは既に進行中のため、" + reason + " は無視しました。");
            return false;
        }

        plugin.getLogger().warning(reason + " によりワールドリセットを開始します。");


        // Minecraft 26.x ではディメンションが 1 つのワールドフォルダ配下に統合され、
        // getWorldFolder() は dimensions/<namespace>/<dimension> を返す。
        // level.dat を持つ祖先まで遡って「ワールドのルート」を求め、重複を取り除く。
        Set<Path> targets = new LinkedHashSet<>();
        Set<String> names = managedWorldNames();
        for (World world : Bukkit.getWorlds()) {
            if (!names.contains(world.getName())) {
                continue;
            }
            Path root = resolveLevelRoot(world.getWorldFolder().toPath());
            if (root == null) {
                plugin.getLogger().severe("ワールドのルートを特定できませんでした: " + world.getName());
                continue;
            }
            targets.add(root);
        }

        if (targets.isEmpty()) {
            plugin.getLogger().severe("削除対象のワールドが見つかりませんでした。リセットを中止します。");
            triggered.set(false);
            return false;
        }

        // 全員キックより先に予約を書く。書けなければリセットせずに続行した方が安全。
        if (!writeMarker(targets)) {
            triggered.set(false);
            return false;
        }

        if (plugin.getConfig().getBoolean("randomize-seed", true)) {
            randomizeSeed();
        }

        // キックの前に投げておく。送信は別スレッドで進み、停止までの猶予の間に終わる。
        // 間に合わなくても保留ファイルに残るので、次回起動時に送り直される。
        plugin.notifyDiscord(cause);

        // 死亡イベントの処理中に画面や接続を触ると不整合が起きうるので次 tick へ回す。
        Bukkit.getScheduler().runTask(plugin, () -> announce(subject));
        return true;
    }

    /** 猶予の始まり。全員へタイトルを出し、キックを予約する。 */
    private void announce(String subject) {
        long seconds = Math.max(0L,
                plugin.getConfig().getLong("reset-delay-seconds", DEFAULT_RESET_DELAY_SECONDS));
        TagResolver tags = TagResolver.resolver(
                Placeholder.unparsed("player", subject),
                Placeholder.unparsed("seconds", String.valueOf(seconds)));

        showResetTitle(tags, seconds);

        String broadcastRaw = plugin.getConfig().getString("broadcast-message", "");
        if (broadcastRaw != null && !broadcastRaw.isBlank()) {
            Bukkit.broadcast(MiniMessage.miniMessage().deserialize(broadcastRaw, tags));
        }

        plugin.getLogger().warning(seconds + "秒後に全員をキックしてワールドをリセットします。");
        Bukkit.getScheduler().runTaskLater(plugin, () -> kickEveryone(subject), seconds * 20L);
    }

    /**
     * 猶予のあいだ全員へ出すタイトル。
     *
     * <p>チャットへ流れる死亡メッセージは {@link DeathListener} が打ち消すので、
     * 死亡とリセットを知らせるのはこのタイトルだけになる。
     */
    private void showResetTitle(TagResolver tags, long seconds) {
        String titleRaw = plugin.getConfig().getString("title", DEFAULT_TITLE);
        if (titleRaw == null || titleRaw.isBlank()) {
            return;
        }
        MiniMessage mm = MiniMessage.miniMessage();
        String subtitleRaw = plugin.getConfig().getString("subtitle", "");
        Title title = Title.title(
                mm.deserialize(titleRaw, tags),
                subtitleRaw == null || subtitleRaw.isBlank()
                        ? Component.empty()
                        : mm.deserialize(subtitleRaw, tags),
                // キックの瞬間まで出しっぱなしにする。
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(seconds),
                        Duration.ofMillis(500)));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
        }
    }

    private void kickEveryone(String subject) {
        String kickRaw = plugin.getConfig().getString("kick-message",
                "<red>誰かが死亡しました。ワールドをリセットします。");
        Component kick = MiniMessage.miniMessage().deserialize(kickRaw == null ? "" : kickRaw,
                Placeholder.unparsed("player", subject));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.kick(kick);
        }
        plugin.getLogger().warning("参加中の全プレイヤーをキックしました。");

        long delay = Math.max(1L, plugin.getConfig().getLong("shutdown-delay-ticks", 20L));
        Bukkit.getScheduler().runTaskLater(plugin, this::stopServer, delay);
    }

    @SuppressWarnings("removal") // Bukkit.spigot().restart() は代替 API が無いまま deprecated
    private void stopServer() {
        String mode = String.valueOf(plugin.getConfig().getString("shutdown-mode", "shutdown"))
                .toLowerCase(Locale.ROOT);
        if ("restart".equals(mode)) {
            plugin.getLogger().warning("restart-script でサーバーを再起動します。");
            Bukkit.spigot().restart();
        } else {
            plugin.getLogger().warning("サーバーを停止します。次回起動時にワールドが再生成されます。");
            Bukkit.shutdown();
        }
    }

    // ------------------------------------------------------------------ 予約ファイル

    private boolean writeMarker(Collection<Path> targets) {
        Path marker = plugin.getDataFolder().toPath().resolve(MARKER_FILE);
        List<String> lines = new ArrayList<>();
        lines.add("# WorldIsAlsoHardcore: 次回起動時に削除するワールドフォルダ");
        for (Path target : targets) {
            lines.add(target.toString());
        }
        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, lines, StandardCharsets.UTF_8);
            plugin.getLogger().info("リセットを予約しました: " + targets);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("リセット予約ファイルを書き込めませんでした: " + e.getMessage());
            return false;
        }
    }

    /** サーバーのロガーに届かない場所 (ブートストラップ) や、テストと共有する最小限のログ出力口。 */
    public interface Log {
        void info(String message);

        void error(String message);
    }

    /**
     * 予約されたワールドフォルダを削除する。
     * サーバーが level.dat を読む前 ({@link WorldIsAlsoHardcoreBootstrap}) から呼ぶこと。
     *
     * @return 予約があったら true。呼び出し側はこれを「新しいワールドが始まる」の合図に使う
     */
    static boolean consumePendingReset(Path dataDirectory, Log log) {
        Path marker = dataDirectory.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker)) {
            return false;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("リセット予約ファイルを読めませんでした: " + e.getMessage());
            return false;
        }

        for (String line : lines) {
            String path = line.trim();
            if (path.isEmpty() || path.startsWith("#")) {
                continue;
            }
            deleteWorldFolder(Path.of(path), log);
        }

        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            // 消せないと毎回削除を試みてしまうため、明示的に警告する。
            log.error("リセット予約ファイルを削除できませんでした: " + marker + " (" + e.getMessage() + ")");
        }
        return true;
    }

    /**
     * ディメンションフォルダから、level.dat を持つワールドのルートまで遡る。
     * 旧レイアウト (フォルダ直下に level.dat) でもそのまま機能する。
     */
    private static Path resolveLevelRoot(Path worldFolder) {
        Path current = worldFolder.toAbsolutePath().normalize();
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (Files.isRegularFile(current.resolve("level.dat"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static void deleteWorldFolder(Path folder, Log log) {
        if (!Files.isDirectory(folder)) {
            log.error("削除対象が存在しないためスキップします: " + folder);
            return;
        }
        // ワールドフォルダ以外を誤って消さないための最低限の安全弁。
        if (!Files.isRegularFile(folder.resolve("level.dat"))) {
            log.error("level.dat が無いためワールドフォルダとみなせません。スキップします: " + folder);
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            log.info("ワールドを削除しました: " + folder);
        } catch (IOException | UncheckedIOException e) {
            log.error("ワールドの削除に失敗しました: " + folder + " (" + e.getMessage() + ")");
        }
    }
    // ------------------------------------------------------------------ seed

    /** server.properties の level-seed を新しい乱数へ書き換える。 */
    private void randomizeSeed() {
        long seed = new Random().nextLong();
        if (setServerProperty("level-seed", Long.toString(seed), plugin.log())) {
            plugin.getLogger().info("次回のワールド seed を " + seed + " に設定しました。");
        }
    }

    /**
     * server.properties の1項目を書き換える。その行が無ければ足す。
     *
     * <p>seed のほか、{@link FinaleManager} がハードコアを解くときにも使う。
     *
     * @return 書けたら true
     */
    static boolean setServerProperty(String key, String value, Log log) {
        Path properties = Path.of(SERVER_PROPERTIES).toAbsolutePath();
        if (!Files.isRegularFile(properties)) {
            log.error(SERVER_PROPERTIES + " が見つからないため " + key + " を変更できません: " + properties);
            return false;
        }
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(properties, StandardCharsets.UTF_8));
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(key + "=")) {
                    lines.set(i, key + "=" + value);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(key + "=" + value);
            }
            Files.write(properties, lines, StandardCharsets.UTF_8);
            log.info(SERVER_PROPERTIES + " の " + key + " を " + value + " にしました。");
            return true;
        } catch (IOException e) {
            log.error(SERVER_PROPERTIES + " を更新できませんでした: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ 起動時チェック

    public void warnAboutConfiguration() {
        List<World> worlds = Bukkit.getWorlds();
        boolean finaleMode = plugin.finaleMode();
        boolean finished = plugin.finaleDone();

        // 終了済みならハードコアが解けているのが正しい姿なので、そこは責めない。
        if (!worlds.isEmpty() && !worlds.get(0).isHardcore() && !finished) {
            plugin.getLogger().warning(
                    "主ワールドがハードコアではありません。server.properties の hardcore=true を確認してください。");
        }
        if (finaleMode) {
            plugin.getLogger().warning(
                    "on-death: finale — 死亡してもワールドは削除しません。"
                    + (finished ? " ハードコアは既に終了しています。" : " 次の死亡でハードコアを終了します。"));
        } else if (!plugin.getConfig().getBoolean("randomize-seed", true)) {
            plugin.getLogger().warning(
                    "randomize-seed が false です。level-seed が固定されていると毎回同じ地形が再生成されます。");
        }
        plugin.getLogger().info("監視対象ワールド: " + managedWorldNames());
    }
}
