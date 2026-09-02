package io.github.worldisalsohardcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ワールドの開始時刻と、そこからの経過時間。
 *
 * <p>{@link WorldClock} はファイルしか触らないので、サーバーを起動せずに確かめられる。
 */
class WorldClockTest {

    @TempDir
    Path tmp;

    private final Instant now = Instant.parse("2026-09-03T12:00:00Z");

    // ------------------------------------------------------------------ 記録

    @Test
    @DisplayName("新しいワールドの開始時刻を記録し、読み戻せる")
    void marksAndReadsBack() {
        WorldClock.markNewWorld(tmp, now, new RecordingLog());

        assertEquals(now, WorldClock.startedAt(tmp).orElseThrow());
    }

    @Test
    @DisplayName("記録が無ければ現在時刻を起点にする")
    void ensureStartedWritesWhenMissing() {
        RecordingLog log = new RecordingLog();

        WorldClock.ensureStarted(tmp, now, log);

        assertEquals(now, WorldClock.startedAt(tmp).orElseThrow());
        assertTrue(log.anyInfoContains("記録されていない"), log.info.toString());
    }

    @Test
    @DisplayName("記録があれば普通の再起動では上書きしない")
    void ensureStartedKeepsExisting() {
        WorldClock.markNewWorld(tmp, now, new RecordingLog());

        // 再起動しただけ。経過時間は積み上がってほしいので起点は動かさない。
        WorldClock.ensureStarted(tmp, now.plus(Duration.ofDays(3)), new RecordingLog());

        assertEquals(now, WorldClock.startedAt(tmp).orElseThrow());
    }

    @Test
    @DisplayName("リセットのたびに起点を打ち直す")
    void markNewWorldOverwrites() {
        WorldClock.markNewWorld(tmp, now, new RecordingLog());
        Instant later = now.plus(Duration.ofDays(3));

        WorldClock.markNewWorld(tmp, later, new RecordingLog());

        assertEquals(later, WorldClock.startedAt(tmp).orElseThrow());
    }

    @Test
    @DisplayName("記録が無ければ経過時間は空")
    void elapsedIsEmptyWithoutRecord() {
        assertTrue(WorldClock.elapsed(tmp, now).isEmpty());
    }

    @Test
    @DisplayName("壊れた記録は無視して空を返す")
    void brokenRecordIsIgnored() throws IOException {
        Files.writeString(tmp.resolve(WorldClock.START_FILE), "きのう\n", StandardCharsets.UTF_8);

        assertTrue(WorldClock.startedAt(tmp).isEmpty());
    }

    @Test
    @DisplayName("コメント行は読み飛ばす")
    void ignoresComments() throws IOException {
        Files.write(tmp.resolve(WorldClock.START_FILE),
                List.of("# なにかの説明", "", String.valueOf(now.toEpochMilli())),
                StandardCharsets.UTF_8);

        assertEquals(now, WorldClock.startedAt(tmp).orElseThrow());
    }

    @Test
    @DisplayName("サーバーが止まっていた間も経過時間に含める")
    void elapsedIsWallClock() {
        WorldClock.markNewWorld(tmp, now, new RecordingLog());

        // 途中でサーバーが何度落ちていようと、起点は動かないので実時間で出る。
        Duration elapsed = WorldClock.elapsed(tmp, now.plus(Duration.ofHours(50))).orElseThrow();

        assertEquals(Duration.ofHours(50), elapsed);
    }

    @Test
    @DisplayName("時計が巻き戻っていたら0に丸める")
    void clampsNegativeElapsed() {
        WorldClock.markNewWorld(tmp, now, new RecordingLog());

        assertEquals(Duration.ZERO,
                WorldClock.elapsed(tmp, now.minus(Duration.ofHours(1))).orElseThrow());
    }

    // ------------------------------------------------------------------ 書式

    @Test
    @DisplayName("日・時間・分で表す")
    void formatsDaysHoursMinutes() {
        assertEquals("3日 4時間 12分",
                WorldClock.formatElapsed(Duration.ofDays(3).plusHours(4).plusMinutes(12)));
    }

    @Test
    @DisplayName("0の単位は省く")
    void skipsZeroUnits() {
        assertEquals("3日 12分", WorldClock.formatElapsed(Duration.ofDays(3).plusMinutes(12)));
        assertEquals("2時間", WorldClock.formatElapsed(Duration.ofHours(2)));
        assertEquals("5日", WorldClock.formatElapsed(Duration.ofDays(5)));
    }

    @Test
    @DisplayName("1時間未満は秒まで出す")
    void showsSecondsForShortLives() {
        // すぐ死んだときに「0分」とだけ出ても何も分からない。
        assertEquals("5分 32秒", WorldClock.formatElapsed(Duration.ofSeconds(332)));
        assertEquals("45秒", WorldClock.formatElapsed(Duration.ofSeconds(45)));
        assertEquals("0秒", WorldClock.formatElapsed(Duration.ZERO));
    }

    @Test
    @DisplayName("1時間を超えたら秒は出さない")
    void hidesSecondsOnceItIsLong() {
        assertEquals("1時間 1分", WorldClock.formatElapsed(Duration.ofSeconds(3661)));
    }

    @Test
    @DisplayName("不明な経過時間 (null) も落ちない")
    void formatsNull() {
        assertEquals("0秒", WorldClock.formatElapsed(null));
    }
}
