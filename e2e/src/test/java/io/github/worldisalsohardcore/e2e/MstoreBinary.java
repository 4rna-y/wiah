package io.github.worldisalsohardcore.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * mstore の実行ファイルを見つける。
 *
 * <p>mstore は別リポジトリなので Gradle の依存としては引けない。{@code :e2e:e2eTest} が
 * 隣の checkout に対して {@code installDist} を回し、その結果をここで受け取る。
 */
final class MstoreBinary {

    private MstoreBinary() {
    }

    static Optional<Path> resolve() {
        String configured = System.getProperty("wiah.mstoreBin", "");
        if (!configured.isBlank()) {
            Path bin = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isExecutable(bin)) {
                return Optional.of(bin);
            }
        }
        return Optional.empty();
    }

    static String hint() {
        return "mstore の実行ファイルがありません。\n"
                + "  隣に clone する: git clone https://github.com/4rna-y/mstore ../mstore\n"
                + "  別の場所にあるなら MSTORE_DIR=<path> を指定してください。";
    }
}
