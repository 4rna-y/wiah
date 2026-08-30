package io.github.worldisalsohardcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * プラグインと mstore の唯一の接点である予約ファイルのパスを固定する。
 *
 * <p>プラグイン名や予約ファイル名を変えると、mstore は既定値のままなので予約に気付けなくなり、
 * 「リセットしたのに再起動しない」という分かりにくい壊れ方をする。片方だけ変わったことを
 * ここで落とす。mstore 側の既定値は {@code Options.DEFAULT_MARKER}。
 */
class MarkerContractTest {

    /** mstore の --marker が既定で見に行くパス。変えるなら両方のリポジトリを直すこと。 */
    private static final String MSTORE_DEFAULT_MARKER =
            "plugins/WorldIsAlsoHardcore/pending-reset.txt";

    @Test
    @DisplayName("プラグインが書く予約ファイルのパスが mstore の既定値と一致する")
    void markerPathMatchesMstoreDefault() throws IOException {
        // データフォルダは plugins/<paper-plugin.yml の name>。
        String actual = "plugins/" + pluginName() + "/" + ResetManager.MARKER_FILE;

        assertEquals(MSTORE_DEFAULT_MARKER, actual,
                "予約ファイルのパスが mstore の既定値とずれている。"
                        + " mstore 側の Options.DEFAULT_MARKER も更新すること");
    }

    private static String pluginName() throws IOException {
        try (InputStream in = MarkerContractTest.class.getResourceAsStream("/paper-plugin.yml")) {
            assertNotNull(in, "paper-plugin.yml がテストのクラスパスに無い");
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("^name:\\s*(\\S+)\\s*$", Pattern.MULTILINE).matcher(yml);
            assertNotNull(matcher.find() ? matcher.group(1) : null, "paper-plugin.yml に name が無い");
            return matcher.group(1);
        }
    }
}
