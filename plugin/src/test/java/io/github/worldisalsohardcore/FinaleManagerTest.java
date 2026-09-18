package io.github.worldisalsohardcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ハードコアの終わり方のうち、Minecraft が要らない部分。
 *
 * <p>見るのは2つ。「一度終わったら終わったまま」を支える記録ファイルの読み書きと、
 * 成績表を配るコマンドの組み立て。全員をアドベンチャーへ移すところは本物のサーバーが
 * 要るので、ここでは見ない。
 */
class FinaleManagerTest {

    @TempDir
    Path dataDirectory;

    private final RecordingLog log = new RecordingLog();

    // ------------------------------------------------------------------ 記録ファイル

    @Test
    @DisplayName("記録が無ければ終了していない")
    void noMarkerMeansNotFinished() {
        assertNull(FinaleManager.readMarker(dataDirectory, log));
    }

    @Test
    @DisplayName("書いた記録をそのまま読み戻せる")
    void marketRoundTrips() {
        Instant at = Instant.ofEpochMilli(1_700_000_000_000L);
        FinaleManager.Ending written =
                new FinaleManager.Ending("Steve", at, Duration.ofHours(76).plusMinutes(12));

        FinaleManager.writeMarker(dataDirectory, written, log);
        FinaleManager.Ending read = FinaleManager.readMarker(dataDirectory, log);

        assertNotNull(read);
        assertEquals("Steve", read.player());
        assertEquals(at, read.at());
        assertEquals(Duration.ofHours(76).plusMinutes(12), read.elapsed());
    }

    @Test
    @DisplayName("経過時間が分からなくても記録できる")
    void elapsedMayBeUnknown() {
        FinaleManager.writeMarker(dataDirectory,
                new FinaleManager.Ending("Steve", Instant.EPOCH, null), log);

        FinaleManager.Ending read = FinaleManager.readMarker(dataDirectory, log);

        assertNotNull(read);
        assertNull(read.elapsed());
    }

    @Test
    @DisplayName("中身が壊れていても、ファイルがある以上は終了済みとして扱う")
    void brokenMarkerStillCountsAsFinished() throws IOException {
        // ここで「終了していない」と答えると、次の死亡で全員の持ち物をもう一度消してしまう。
        Files.writeString(dataDirectory.resolve(FinaleManager.MARKER_FILE),
                "player=Steve\nat=ここは数字ではない\n", StandardCharsets.UTF_8);

        assertNotNull(FinaleManager.readMarker(dataDirectory, log));
        assertTrue(log.anyErrorContains("終了済みとして扱います"),
                "読めなかったことがログに残っていない: " + log.error);
    }

    // ------------------------------------------------------------------ コマンドの組み立て

    @Test
    @DisplayName("既定のコマンドに死亡者と経過時間が埋まる")
    void defaultCommandCarriesPlayerAndElapsed() {
        FinaleManager.Ending record = new FinaleManager.Ending("Steve", Instant.EPOCH,
                Duration.ofDays(3).plusHours(4).plusMinutes(12));

        assertEquals("result ハードコア終了|Steve が死亡|経過 3日 4時間 12分",
                FinaleManager.render(FinaleManager.DEFAULT_RESULT_COMMAND, record, null));
    }

    @Test
    @DisplayName("後から来た人向けのコマンドはその人を名指しする")
    void joinCommandNamesTheJoiner() {
        FinaleManager.Ending record =
                new FinaleManager.Ending("Steve", Instant.EPOCH, Duration.ofMinutes(5));

        assertEquals("result --player Alex ハードコア終了|Steve が死亡|経過 5分",
                FinaleManager.render(FinaleManager.DEFAULT_JOIN_RESULT_COMMAND, record, "Alex"));
    }

    @Test
    @DisplayName("経過時間が分からなければ「不明」と書く")
    void unknownElapsedIsSpelledOut() {
        FinaleManager.Ending record = new FinaleManager.Ending("Steve", Instant.EPOCH, null);

        assertEquals("result ハードコア終了|Steve が死亡|経過 不明",
                FinaleManager.render(FinaleManager.DEFAULT_RESULT_COMMAND, record, null));
    }

    @Test
    @DisplayName("コマンドを空にすれば何も組み立てない")
    void blankCommandStaysBlank() {
        FinaleManager.Ending record =
                new FinaleManager.Ending("Steve", Instant.EPOCH, Duration.ZERO);

        assertEquals("", FinaleManager.render("", record, null));
        assertEquals("", FinaleManager.render(null, record, null));
    }
}
