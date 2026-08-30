package io.github.worldisalsohardcore.e2e;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 子プロセスの標準出力を溜めつつ、標準入力へコマンドを流し込む。
 *
 * <p>mstore は {@code inheritIO()} で Paper を起こすので、mstore の stdin に書いたものは
 * そのままサーバーコンソールに届く。テストが {@code wiahc reset} を打てるのはこのため。
 */
final class ProcessConsole implements AutoCloseable {

    private final Process process;
    private final BufferedWriter stdin;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final Thread reader;
    private final Path transcript;

    ProcessConsole(ProcessBuilder builder, Path transcript) throws IOException {
        this.transcript = transcript;
        Files.createDirectories(transcript.getParent());
        builder.redirectErrorStream(true);
        this.process = builder.start();
        this.stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = Thread.ofVirtual().name("console-reader").start(this::drain);
    }

    private void drain() {
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter file = Files.newBufferedWriter(transcript, StandardCharsets.UTF_8)) {
            String line;
            while ((line = out.readLine()) != null) {
                lines.add(line);
                file.write(line);
                file.newLine();
                file.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** サーバーコンソールへ1行送る。 */
    void send(String command) {
        try {
            stdin.write(command);
            stdin.newLine();
            stdin.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("コンソールへ書けません (プロセスが死んでいる?): " + command, e);
        }
    }

    /**
     * {@code fromIndex} 以降に fragment を含む行が出るまで待ち、その行の位置を返す。
     *
     * @throws AssertionError 時間内に出なかった場合。直近のログを添えて投げる。
     */
    int awaitLine(String fragment, int fromIndex, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        int cursor = fromIndex;
        while (Instant.now().isBefore(deadline)) {
            int size = lines.size();
            while (cursor < size) {
                if (lines.get(cursor).contains(fragment)) {
                    return cursor;
                }
                cursor++;
            }
            if (!process.isAlive() && cursor >= lines.size()) {
                throw new AssertionError("プロセスが終了しても \"" + fragment + "\" が出ませんでした。"
                        + tail());
            }
            sleep(100);
        }
        throw new AssertionError("\"" + fragment + "\" が " + timeout.toSeconds()
                + "秒以内に出ませんでした。" + tail());
    }

    /** これまでの出力に fragment を含む行があるか。 */
    boolean sawLine(String fragment) {
        synchronized (lines) {
            return lines.stream().anyMatch(line -> line.contains(fragment));
        }
    }

    /** fromIndex 以降に fragment を含む行があるか。 */
    boolean hasLineAfter(String fragment, int fromIndex) {
        synchronized (lines) {
            for (int i = Math.max(0, fromIndex); i < lines.size(); i++) {
                if (lines.get(i).contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 現在までに受け取った行数。await の開始位置に使う。 */
    int mark() {
        return lines.size();
    }

    /** 終了するまで待って終了コードを返す。 */
    int awaitExit(Duration timeout) throws InterruptedException {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError("mstore が " + timeout.toSeconds() + "秒以内に終了しませんでした。"
                    + tail());
        }
        reader.join(TimeUnit.SECONDS.toMillis(5));
        return process.exitValue();
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /** 失敗時に何が起きていたか分かるよう、直近の出力を添える。 */
    String tail() {
        List<String> snapshot;
        synchronized (lines) {
            snapshot = new ArrayList<>(lines);
        }
        List<String> last = snapshot.subList(Math.max(0, snapshot.size() - 40), snapshot.size());
        return "\n--- 直近の出力 (全文: " + transcript + ") ---\n" + String.join("\n", last) + "\n";
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 既に閉じている
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
