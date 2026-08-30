package io.github.worldisalsohardcore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * リセットの3段階のうち「次回起動時にワールドフォルダを削除する」部分の検証。
 *
 * <p>{@link ResetManager#consumePendingReset} は Bukkit に触らないので、サーバーを起動せずに
 * 一時ディレクトリだけで確かめられる。実際にワールドが再生成されるところまでの確認は
 * {@code :e2e} モジュールが担当する。
 */
class ResetManagerTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ 正常系

    @Test
    @DisplayName("予約ファイルが無ければ何もしない")
    void noMarkerIsNoOp() throws IOException {
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Path world = givenWorldFolder("world");

        RecordingLog log = new RecordingLog();
        ResetManager.consumePendingReset(dataDir, log);

        assertTrue(Files.isDirectory(world), "予約が無いのにワールドが消えている");
        assertTrue(log.error.isEmpty(), "予期しないエラー: " + log.error);
        assertTrue(log.info.isEmpty(), "予期しない出力: " + log.info);
    }

    @Test
    @DisplayName("予約されたワールドを削除し、予約ファイル自身も消す")
    void deletesReservedWorldAndConsumesMarker() throws IOException {
        Path world = givenWorldFolder("world");
        Files.writeString(world.resolve("nested/deep/sentinel.txt"), "x");
        Path marker = givenMarker(world);

        RecordingLog log = new RecordingLog();
        ResetManager.consumePendingReset(marker.getParent(), log);

        assertFalse(Files.exists(world), "ワールドフォルダが残っている");
        assertFalse(Files.exists(marker), "予約ファイルが消費されていない");
        assertTrue(log.anyInfoContains("ワールドを削除しました"), log.info.toString());
        assertTrue(log.error.isEmpty(), "予期しないエラー: " + log.error);
    }

    @Test
    @DisplayName("複数のワールドをまとめて削除する")
    void deletesEveryReservedWorld() throws IOException {
        Path overworld = givenWorldFolder("world");
        Path nether = givenWorldFolder("world_nether");
        Path theEnd = givenWorldFolder("world_the_end");
        Path marker = givenMarker(overworld, nether, theEnd);

        ResetManager.consumePendingReset(marker.getParent(), new RecordingLog());

        assertFalse(Files.exists(overworld), "world が残っている");
        assertFalse(Files.exists(nether), "world_nether が残っている");
        assertFalse(Files.exists(theEnd), "world_the_end が残っている");
    }

    @Test
    @DisplayName("コメント行と空行は読み飛ばす")
    void ignoresCommentsAndBlankLines() throws IOException {
        Path world = givenWorldFolder("world");
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        Files.write(dataDir.resolve("pending-reset.txt"), List.of(
                "# WorldIsAlsoHardcore: 次回起動時に削除するワールドフォルダ",
                "",
                "   ",
                world.toString()), StandardCharsets.UTF_8);

        RecordingLog log = new RecordingLog();
        ResetManager.consumePendingReset(dataDir, log);

        assertFalse(Files.exists(world), "ワールドフォルダが残っている");
        assertTrue(log.error.isEmpty(), "コメント行をパスとして扱っている: " + log.error);
    }

    // ------------------------------------------------------------------ 安全弁

    @Test
    @DisplayName("level.dat が無いフォルダは削除しない")
    void refusesToDeleteFolderWithoutLevelDat() throws IOException {
        // 予約ファイルが壊れていたり書き換えられていたときに、
        // 無関係なディレクトリを消してしまわないための安全弁。
        Path notAWorld = Files.createDirectories(tmp.resolve("important"));
        Files.writeString(notAWorld.resolve("keep-me.txt"), "大事なファイル");
        Path marker = givenMarker(notAWorld);

        RecordingLog log = new RecordingLog();
        ResetManager.consumePendingReset(marker.getParent(), log);

        assertTrue(Files.exists(notAWorld.resolve("keep-me.txt")), "level.dat が無いのに削除された");
        assertTrue(log.anyErrorContains("level.dat が無い"), log.error.toString());
        assertFalse(Files.exists(marker), "予約ファイルは消費されるべき");
    }

    @Test
    @DisplayName("存在しないパスは飛ばし、残りの削除は続ける")
    void skipsMissingPathsButKeepsGoing() throws IOException {
        Path missing = tmp.resolve("gone");
        Path world = givenWorldFolder("world");
        Path marker = givenMarker(missing, world);

        RecordingLog log = new RecordingLog();
        ResetManager.consumePendingReset(marker.getParent(), log);

        assertTrue(log.anyErrorContains("削除対象が存在しない"), log.error.toString());
        assertFalse(Files.exists(world), "後続のワールドが削除されていない");
        assertFalse(Files.exists(marker), "予約ファイルが消費されていない");
    }

    @Test
    @DisplayName("ファイルを指す予約は削除しない")
    void refusesToDeletePlainFile() throws IOException {
        Path file = Files.writeString(tmp.resolve("server.properties"), "level-seed=1");
        Path marker = givenMarker(file);

        RecordingLog log = new RecordingLog();
        ResetManager.consumePendingReset(marker.getParent(), log);

        assertTrue(Files.exists(file), "ディレクトリでないものが削除された");
        assertTrue(log.anyErrorContains("削除対象が存在しない"), log.error.toString());
    }

    // ------------------------------------------------------------------ 冪等性

    @Test
    @DisplayName("2回目の起動では予約が消えているので何もしない")
    void secondBootDoesNothing() throws IOException {
        Path world = givenWorldFolder("world");
        Path marker = givenMarker(world);
        ResetManager.consumePendingReset(marker.getParent(), new RecordingLog());

        // サーバーが再生成したワールドに見立てて作り直す
        Path regenerated = givenWorldFolder("world");

        RecordingLog second = new RecordingLog();
        ResetManager.consumePendingReset(marker.getParent(), second);

        assertTrue(Files.isDirectory(regenerated), "再生成したワールドが2回目で消された");
        assertTrue(second.info.isEmpty() && second.error.isEmpty(),
                "予約が無いのに動いている: " + second.info + second.error);
    }

    // ------------------------------------------------------------------ 補助

    /** level.dat を持つ、ワールドとして扱われるフォルダを作る。 */
    private Path givenWorldFolder(String name) throws IOException {
        Path world = Files.createDirectories(tmp.resolve("server").resolve(name));
        Files.writeString(world.resolve("level.dat"), "dummy");
        Files.createDirectories(world.resolve("nested/deep"));
        return world;
    }

    /** プラグインが書くのと同じ形式で予約ファイルを作り、そのパスを返す。 */
    private Path givenMarker(Path... targets) throws IOException {
        Path dataDir = Files.createDirectories(tmp.resolve("data"));
        List<String> lines = new java.util.ArrayList<>();
        lines.add("# WorldIsAlsoHardcore: 次回起動時に削除するワールドフォルダ");
        for (Path target : targets) {
            lines.add(target.toString());
        }
        Path marker = dataDir.resolve(ResetManager.MARKER_FILE);
        Files.write(marker, lines, StandardCharsets.UTF_8);
        return marker;
    }
}
