package io.github.worldisalsohardcore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ワールドが作られた実時刻を覚えておき、そこからの経過時間を測る。
 *
 * <p>プラグインのデータフォルダはリセットで消えないので、開始時刻はそこへ置く
 * ({@value #START_FILE})。書き換えるのは {@link WorldIsAlsoHardcoreBootstrap} が実際に
 * ワールドを削除した直後だけで、予約が無い普通の再起動では触らない。つまりここで測るのは
 * <b>ワールド生成からの実時間</b>で、サーバーが止まっていた時間も含む。
 *
 * <p>ワールドの内部時刻 ({@code World#getFullTime()}) は使えない。あれは tick の数なので
 * サーバーが止まっている間は進まず、「このワールドは何日もった」の答えにならない。
 */
public final class WorldClock {

    /** ワールドの開始時刻 (epoch millis) を書いておくファイル。 */
    static final String START_FILE = "world-started-at.txt";

    private static final String HEADER = "# WorldIsAlsoHardcore: このワールドが作られた時刻 (epoch millis)";

    private WorldClock() {
    }

    /** 新しいワールドが始まったことにする。開始時刻を {@code now} で上書きする。 */
    static void markNewWorld(Path dataDirectory, Instant now, ResetManager.Log log) {
        write(dataDirectory, now, log);
    }

    /** 記録が無ければ {@code now} を開始時刻にする。既にあれば触らない。 */
    static void ensureStarted(Path dataDirectory, Instant now, ResetManager.Log log) {
        if (Files.isRegularFile(dataDirectory.resolve(START_FILE))) {
            return;
        }
        // 途中から導入した場合、本当の生成時刻は分からない。ここを起点にするしかない。
        log.info("ワールドの開始時刻が記録されていないため、現在時刻を起点にします。");
        write(dataDirectory, now, log);
    }

    /** 記録されている開始時刻。読めなければ空。 */
    static Optional<Instant> startedAt(Path dataDirectory) {
        Path file = dataDirectory.resolve(START_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String value = line.strip();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                return Optional.of(Instant.ofEpochMilli(Long.parseLong(value)));
            }
        } catch (IOException | NumberFormatException e) {
            // 経過時間が出ないだけで、リセット自体は続けたい。呼び出し側は空として扱う。
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** 開始からの経過。記録が無ければ空。時計が巻き戻っていたら 0 に丸める。 */
    static Optional<Duration> elapsed(Path dataDirectory, Instant now) {
        return startedAt(dataDirectory).map(start -> {
            Duration elapsed = Duration.between(start, now);
            return elapsed.isNegative() ? Duration.ZERO : elapsed;
        });
    }

    /**
     * 経過時間を「3日 4時間 12分」の形にする。
     *
     * <p>0 の単位は省く。1時間に満たない場合だけ秒まで出す (すぐ死んだときに
     * 「0分」とだけ出ても何も分からないため)。
     */
    public static String formatElapsed(Duration duration) {
        Duration d = duration == null || duration.isNegative() ? Duration.ZERO : duration;
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();

        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + "日");
        }
        if (hours > 0) {
            parts.add(hours + "時間");
        }
        if (minutes > 0) {
            parts.add(minutes + "分");
        }
        if (days == 0 && hours == 0 && (seconds > 0 || parts.isEmpty())) {
            parts.add(seconds + "秒");
        }
        return String.join(" ", parts);
    }

    private static void write(Path dataDirectory, Instant now, ResetManager.Log log) {
        Path file = dataDirectory.resolve(START_FILE);
        try {
            Files.createDirectories(dataDirectory);
            Files.write(file, List.of(HEADER, Long.toString(now.toEpochMilli())),
                    StandardCharsets.UTF_8);
            log.info("ワールドの開始時刻を記録しました: " + now);
        } catch (IOException e) {
            // 次の通知で経過時間が「不明」になるだけ。起動やリセットは止めない。
            log.error("ワールドの開始時刻を記録できませんでした: " + file + " (" + e.getMessage() + ")");
        }
    }
}
