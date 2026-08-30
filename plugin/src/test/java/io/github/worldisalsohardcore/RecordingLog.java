package io.github.worldisalsohardcore;

import java.util.ArrayList;
import java.util.List;

/** テスト用に {@link ResetManager.Log} の出力を溜め込む。 */
final class RecordingLog implements ResetManager.Log {

    final List<String> info = new ArrayList<>();
    final List<String> error = new ArrayList<>();

    @Override
    public void info(String message) {
        info.add(message);
    }

    @Override
    public void error(String message) {
        error.add(message);
    }

    /** いずれかの行に部分一致するか。 */
    boolean anyErrorContains(String fragment) {
        return error.stream().anyMatch(line -> line.contains(fragment));
    }

    boolean anyInfoContains(String fragment) {
        return info.stream().anyMatch(line -> line.contains(fragment));
    }
}
