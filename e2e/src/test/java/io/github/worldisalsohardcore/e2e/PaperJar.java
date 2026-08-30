package io.github.worldisalsohardcore.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * e2e で起動する Paper の jar を用意する。
 *
 * <p>探す順番は次のとおり。見つからなければ空を返し、テストはスキップされる。
 * <ol>
 *   <li>システムプロパティ {@code wiah.paperJar} / 環境変数 {@code PAPER_JAR}</li>
 *   <li>キャッシュディレクトリ内で最も新しい {@code paper-*.jar}</li>
 *   <li>PATH 上の {@code paper-jar} コマンド (nix develop が用意する)</li>
 * </ol>
 *
 * <p>ダウンロード処理を自前で持たないのは、flake.nix の {@code paper-jar} が
 * SHA256 の検証まで含めて既にやっているため。
 */
final class PaperJar {

    private PaperJar() {
    }

    static Optional<Path> resolve(Path cacheDir) {
        Optional<Path> explicit = fromProperty();
        if (explicit.isPresent()) {
            return explicit;
        }
        Optional<Path> cached = newestIn(cacheDir);
        if (cached.isPresent()) {
            return cached;
        }
        return download(cacheDir);
    }

    /** 見つからなかった理由をテストのスキップ文言に使う。 */
    static String hint(Path cacheDir) {
        return "Paper の jar が見つかりません。次のいずれかで用意してください:\n"
                + "  - nix develop に入って `paper-jar` を通す\n"
                + "  - " + cacheDir + " に paper-*.jar を置く\n"
                + "  - -Dwiah.paperJar=<path> か PAPER_JAR=<path> を指定する";
    }

    private static Optional<Path> fromProperty() {
        String value = System.getProperty("wiah.paperJar", System.getenv("PAPER_JAR"));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Path jar = Path.of(value).toAbsolutePath().normalize();
        return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }

    private static Optional<Path> newestIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("paper-") && name.endsWith(".jar");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .map(p -> p.toAbsolutePath().normalize());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** nix develop が用意する paper-jar コマンドに任せる。 */
    private static Optional<Path> download(Path cacheDir) {
        try {
            Files.createDirectories(cacheDir);
            ProcessBuilder builder = new ProcessBuilder("paper-jar")
                    .directory(cacheDir.getParent().toFile());
            // paper-jar は PAPER_RUN_DIR に置いてパスを stdout へ出す。
            builder.environment().put("PAPER_RUN_DIR", cacheDir.toAbsolutePath().toString());
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.getErrorStream().transferTo(System.err);
            if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0) {
                process.destroyForcibly();
                return Optional.empty();
            }
            List<String> out = stdout.lines().filter(line -> !line.isBlank()).toList();
            if (out.isEmpty()) {
                return Optional.empty();
            }
            Path jar = cacheDir.getParent().resolve(out.get(out.size() - 1)).normalize();
            return Files.isRegularFile(jar) ? Optional.of(jar.toAbsolutePath()) : Optional.empty();
        } catch (IOException e) {
            // PATH に paper-jar が無い場合もここに来る。スキップさせたいので握る。
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
