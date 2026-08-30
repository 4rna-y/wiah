package io.github.worldisalsohardcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 同梱する config.yml の既定値を固定する。
 *
 * <p>「死亡したら10秒のタイトルを出してからキット」という決めごとは既定値そのものなので、
 * 文言や秒数が意図せず変わったらここで落とす。
 */
class DefaultConfigTest {

    private final YamlConfiguration config = loadBundledConfig();

    @Test
    @DisplayName("猶予は10秒")
    void resetDelayIsTenSeconds() {
        assertEquals(10L, config.getLong("reset-delay-seconds"));
        assertEquals(ResetManager.DEFAULT_RESET_DELAY_SECONDS, config.getLong("reset-delay-seconds"),
                "同梱の config.yml とコード側の既定値がずれている");
    }

    @Test
    @DisplayName("タイトルは「サーバーは10秒後に削除されます」と表示される")
    void titleRendersTheAnnouncedText() {
        long seconds = config.getLong("reset-delay-seconds");
        String raw = config.getString("title");
        assertNotNull(raw, "title が config.yml に無い");

        String rendered = PlainTextComponentSerializer.plainText().serialize(
                MiniMessage.miniMessage().deserialize(raw,
                        Placeholder.unparsed("seconds", String.valueOf(seconds)),
                        Placeholder.unparsed("player", "Steve")));

        assertEquals("サーバーは10秒後に削除されます", rendered);
    }

    @Test
    @DisplayName("コード側の既定タイトルも同じ文言になる")
    void codeDefaultMatchesTheBundledOne() {
        // config.yml を消して起動した場合でも同じ文言が出ること。
        String rendered = PlainTextComponentSerializer.plainText().serialize(
                MiniMessage.miniMessage().deserialize(ResetManager.DEFAULT_TITLE,
                        Placeholder.unparsed("seconds",
                                String.valueOf(ResetManager.DEFAULT_RESET_DELAY_SECONDS)),
                        Placeholder.unparsed("player", "Steve")));

        assertEquals("サーバーは10秒後に削除されます", rendered);
    }

    @Test
    @DisplayName("死亡メッセージの代わりなので、チャットへの通知は既定で出さない")
    void broadcastIsOffByDefault() {
        assertTrue(config.getString("broadcast-message", "").isBlank(),
                "タイトルに置き換えた意味が無くなるので、既定では空にしておく");
    }

    private static YamlConfiguration loadBundledConfig() {
        try (InputStream in = DefaultConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml がテストのクラスパスに無い");
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("config.yml を読めません", e);
        }
    }
}
