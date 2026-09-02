package io.github.worldisalsohardcore.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** e2e 用に使い捨ての Minecraft サーバーディレクトリを組み立てる。 */
final class TestServerDir {

    private final Path dir;

    private TestServerDir(Path dir) {
        this.dir = dir;
    }

    /**
     * 空のサーバーディレクトリを作り、プラグインと設定を置く。
     *
     * @param pluginJar {@code :plugin:jar} が作った WorldIsAlsoHardcore の jar
     */
    static TestServerDir create(Path dir, Path pluginJar) throws IOException {
        deleteRecursively(dir);
        Files.createDirectories(dir.resolve("plugins"));

        // ローカル検証専用の使い捨てサーバーなので EULA は自動同意する。
        // 同意したくない場合はこの行を消して、テストを実行しないこと。
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");

        Files.write(dir.resolve("server.properties"), List.of(
                "level-name=world",
                // プラグインの前提。false だと起動時に警告が出る。
                "hardcore=true",
                "level-seed=" + INITIAL_SEED,
                "online-mode=false",
                "server-port=" + freePort(),
                "max-players=4",
                // 生成量を減らして起動を短くする。
                "view-distance=4",
                "simulation-distance=4",
                "spawn-protection=0",
                "enable-status=false",
                "sync-chunk-writes=false"), StandardCharsets.UTF_8);

        Files.copy(pluginJar, dir.resolve("plugins").resolve("WorldIsAlsoHardcore.jar"),
                StandardCopyOption.REPLACE_EXISTING);

        // キックから停止までを短くしてテストを速くする。既定は 20 tick。
        Path pluginData = Files.createDirectories(dir.resolve("plugins/WorldIsAlsoHardcore"));
        Files.write(pluginData.resolve("config.yml"), List.of(
                "worlds: []",
                // 猶予は本番 10 秒。テストは待ちたくないので縮める。
                "reset-delay-seconds: " + RESET_DELAY_SECONDS,
                "title: \"<red>reset in <seconds>\"",
                "subtitle: \"\"",
                "broadcast-message: \"\"",
                "kick-message: \"<red>reset\"",
                "shutdown-delay-ticks: 1",
                "shutdown-mode: shutdown",
                "randomize-seed: true",
                // e2e は毎回リセットを1周させる。本物の Discord へ流さない。
                "discord:",
                "  enabled: false"), StandardCharsets.UTF_8);

        return new TestServerDir(dir);
    }

    /** 初期 seed。リセット後に変わることを確かめるので固定値にしておく。 */
    static final long INITIAL_SEED = 1234567890L;

    /** テスト中の猶予 (秒)。既定の 10 秒を待つ意味は無いので縮めてある。 */
    static final int RESET_DELAY_SECONDS = 2;

    Path path() {
        return dir;
    }

    Path marker() {
        return dir.resolve("plugins/WorldIsAlsoHardcore/pending-reset.txt");
    }

    /** server.properties の level-seed。 */
    String levelSeed() throws IOException {
        return property("level-seed").orElseThrow(
                () -> new AssertionError("server.properties に level-seed がありません"));
    }

    Optional<String> property(String key) throws IOException {
        for (String line : Files.readAllLines(dir.resolve("server.properties"), StandardCharsets.UTF_8)) {
            if (line.startsWith(key + "=")) {
                return Optional.of(line.substring(key.length() + 1));
            }
        }
        return Optional.empty();
    }

    /**
     * level.dat を持つディレクトリ = ワールドのルート。
     *
     * <p>Minecraft 26.x はディメンションを1つのフォルダ配下へまとめるので、
     * プラグインが削除するのもこのルート。数と場所を決め打ちせずに探す。
     */
    List<Path> levelRoots() throws IOException {
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("level.dat")))
                    .sorted()
                    .toList();
        }
    }

    /** 各ワールドのルートに目印を置く。削除されたかどうかはこれで分かる。 */
    List<Path> placeSentinels() throws IOException {
        List<Path> sentinels = new ArrayList<>();
        for (Path root : levelRoots()) {
            Path sentinel = root.resolve("wiah-e2e-sentinel.txt");
            Files.writeString(sentinel, "このファイルが消えていればフォルダごと作り直されている\n");
            sentinels.add(sentinel);
        }
        if (sentinels.isEmpty()) {
            throw new AssertionError("level.dat を持つワールドフォルダが " + dir + " に見つかりません");
        }
        return sentinels;
    }

    private static int freePort() throws IOException {
        // 開けてすぐ閉じ、その番号を使う。CI の並列実行までは想定しない。
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
