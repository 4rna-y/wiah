package io.github.worldisalsohardcore.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * mstore と組み合わせたワールド初期化の通し検証。
 *
 * <p>実際に Paper を起動し、コンソールから {@code wiahc reset} を打って
 * 「予約 → 停止 → mstore が再起動 → bootstrap が削除 → 再生成」の一周を確かめる。
 * 死亡イベントの検知は本物のクライアントが要るので対象外。プラグイン側は死亡でも
 * {@code /wiahc reset} でも同じ {@code ResetManager#trigger} を通る。
 *
 * <p>1つのサーバーの一生を順番に検証するので、テストは順序付きで状態を共有する。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@DisplayName("wiah + mstore のワールド初期化")
class WorldResetE2eTest {

    /** 最初の起動はワールド生成を含むので長めに待つ。 */
    private static final Duration BOOT = Duration.ofMinutes(5);
    private static final Duration SHORT = Duration.ofMinutes(2);

    private TestServerDir server;
    private ProcessConsole console;

    /** 検証中に持ち回る状態。 */
    private String seedBeforeReset;
    private List<Path> sentinels;
    private int firstBootAt;
    private int resetDetectedAt;
    private int secondBootAt;

    // ------------------------------------------------------------------ 起動

    @BeforeAll
    void bootServerUnderMstore() throws Exception {
        Path buildDir = Path.of(System.getProperty("wiah.buildDir", "build")).toAbsolutePath();
        Path cacheDir = Path.of(System.getProperty("wiah.paperCache", "../run")).toAbsolutePath()
                .normalize();

        Optional<Path> mstore = MstoreBinary.resolve();
        assumeTrue(mstore.isPresent(), MstoreBinary.hint());

        Optional<Path> paper = PaperJar.resolve(cacheDir);
        assumeTrue(paper.isPresent(), PaperJar.hint(cacheDir));

        Path pluginJar = Path.of(System.getProperty("wiah.pluginJar", ""));
        assumeTrue(Files.isRegularFile(pluginJar),
                "プラグインの jar がありません: " + pluginJar + " (gradle :plugin:jar を先に)");

        server = TestServerDir.create(buildDir.resolve("e2e-run"), pluginJar);

        ProcessBuilder builder = new ProcessBuilder(
                mstore.get().toString(),
                "--server-dir", server.path().toString(),
                "--jar", paper.get().toString(),
                // 起動に時間がかかるので「短命」の閾値は小さく、待ちは無しにする。
                "--min-healthy-seconds", "5",
                "--restart-delay-seconds", "0",
                // KV は今回の検証対象ではないが、起動経路は通したいので空きポートで上げる。
                "--kv-port", "0");
        console = new ProcessConsole(builder, buildDir.resolve("e2e-run-console.log"));

        firstBootAt = console.awaitLine("Done (", 0, BOOT);
    }

    @AfterAll
    void shutdown() {
        if (console != null) {
            console.close();
        }
    }

    // ------------------------------------------------------------------ 検証

    @Test
    @Order(1)
    @DisplayName("起動時にプラグインが監視対象ワールドを認識する")
    void pluginPicksUpManagedWorlds() throws IOException {
        assertTrue(console.sawLine("監視対象ワールド:"),
                "プラグインが有効になっていない" + console.tail());
        assertFalse(console.sawLine("主ワールドがハードコアではありません"),
                "hardcore=true のはずが警告が出ている" + console.tail());

        // このあとの削除判定に使う目印を置く。
        seedBeforeReset = server.levelSeed();
        sentinels = server.placeSentinels();
        assertEquals(String.valueOf(TestServerDir.INITIAL_SEED), seedBeforeReset);
    }

    @Test
    @Order(2)
    @DisplayName("wiahc reset で予約が書かれ、猶予を待ってから全員がキックされる")
    void resetAnnouncesThenKicks() throws Exception {
        int mark = console.mark();
        console.send("wiahc reset");

        // 予約と seed の更新はキックより先。途中で落ちてもリセットが残るように。
        console.awaitLine("リセットを予約しました:", mark, SHORT);
        console.awaitLine("次回のワールド seed を", mark, SHORT);
        assertNotEquals(seedBeforeReset, server.levelSeed(),
                "level-seed が書き換わっていない。同じ地形が再生成されてしまう");

        int announcedAt = console.awaitLine(
                TestServerDir.RESET_DELAY_SECONDS + "秒後に全員をキックしてワールドをリセットします",
                mark, SHORT);
        long announcedNanos = System.nanoTime();

        int kickedAt = console.awaitLine("参加中の全プレイヤーをキックしました", announcedAt, SHORT);
        Duration waited = Duration.ofNanos(System.nanoTime() - announcedNanos);

        assertTrue(kickedAt > announcedAt, "通知よりキックが先に出ている");
        // 猶予を守らずに即キックすると「N秒後に削除されます」が嘘になる。
        assertTrue(waited.toMillis() >= 1_500,
                "猶予を待たずにキックしている (" + waited.toMillis() + "ms)");

        int stoppedAt = console.awaitLine("サーバーを停止します", kickedAt, SHORT);
        assertTrue(stoppedAt > kickedAt, "キックより先に停止している");
    }

    @Test
    @Order(3)
    @DisplayName("mstore が予約を検出してサーバーを起動し直す")
    void mstoreRestartsBecauseOfTheMarker() {
        resetDetectedAt = console.awaitLine("リセット予約を検出しました", firstBootAt, SHORT);
        secondBootAt = console.awaitLine("Done (", resetDetectedAt, BOOT);

        assertTrue(secondBootAt > firstBootAt, "2回目の起動が確認できない");
        assertTrue(console.isAlive(), "mstore が終了してしまっている" + console.tail());
    }

    @Test
    @Order(4)
    @DisplayName("bootstrap が予約されたワールドを削除する")
    void bootstrapDeletesTheReservedWorld() {
        // 削除は再起動後の bootstrap で行われるので、2回目の起動より前に出ているはず。
        int deletedAt = console.awaitLine("ワールドを削除しました:", resetDetectedAt, SHORT);
        assertTrue(deletedAt < secondBootAt,
                "ワールドの削除が起動完了より後になっている (bootstrap で消せていない)");
    }

    @Test
    @Order(5)
    @DisplayName("ワールドフォルダが作り直されている")
    void worldFolderIsRegenerated() throws IOException {
        for (Path sentinel : sentinels) {
            assertFalse(Files.exists(sentinel),
                    "目印が残っている = フォルダが消されていない: " + sentinel);
        }
        assertFalse(server.levelRoots().isEmpty(), "ワールドが再生成されていない");
        for (Path root : server.levelRoots()) {
            assertTrue(Files.isRegularFile(root.resolve("level.dat")),
                    "level.dat が無い: " + root);
        }
    }

    @Test
    @Order(6)
    @DisplayName("予約ファイルは消費されている")
    void markerIsConsumed() {
        assertFalse(Files.exists(server.marker()),
                "予約が残っている。次の停止でも再起動し続けてしまう: " + server.marker());
    }

    // ------------------------------------------------------------------ 通常の停止

    @Test
    @Order(7)
    @DisplayName("stop なら再起動せず mstore も一緒に終了する")
    void normalStopEndsMstoreToo() throws Exception {
        int mark = console.mark();
        console.send("stop");

        console.awaitLine("リセット予約がない通常終了のため", mark, SHORT);
        assertEquals(0, console.awaitExit(SHORT), "通常終了なら 0 で終わるべき");
        assertFalse(console.hasLineAfter("Done (", secondBootAt + 1),
                "stop したのに再起動している" + console.tail());
    }

    // ------------------------------------------------------------------ 補助
}
