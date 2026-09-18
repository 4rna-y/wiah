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

    @Test
    @DisplayName("同梱の config.yml に Webhook URL を載せない")
    void bundledWebhookUrlIsEmpty() {
        // Webhook URL は事実上の認証情報。ここが埋まったままリリースすると、
        // 公開リポジトリと release jar の両方にそのチャンネルへの投稿権が載る。
        assertTrue(config.getBoolean("discord.enabled"), "URL さえ入れれば通知できる状態にしておく");
        assertTrue(config.getString("discord.webhook-url", "").isBlank(),
                "同梱の config.yml に Webhook URL が書かれている");
    }

    @Test
    @DisplayName("Discord まわりも同梱の config.yml とコード側の既定値が揃っている")
    void discordDefaultsMatchTheCode() {
        assertEquals(DiscordNotifier.DEFAULT_EMBED_TITLE, config.getString("discord.embed-title"));
        assertEquals(DiscordNotifier.DEFAULT_EMBED_COLOR, config.getInt("discord.embed-color"));
        assertEquals(DiscordNotifier.DEFAULT_HEAD_IMAGE_URL,
                config.getString("discord.head-image-url"));
        assertEquals(DiscordNotifier.DEFAULT_FLUSH_TIMEOUT_SECONDS,
                config.getLong("discord.flush-timeout-seconds"));
    }

    @Test
    @DisplayName("頭の画像はプレイヤーごとに変わる")
    void headImageUrlIsPerPlayer() {
        // <uuid> を置き忘れると全員同じ顔が出る。
        assertTrue(config.getString("discord.head-image-url", "").contains("<uuid>"),
                "head-image-url に <uuid> が無い");
    }

    @Test
    @DisplayName("既定ではワールドを作り直す (finale は明示的に選ぶもの)")
    void onDeathDefaultsToReset() {
        // 既定が finale だと、普段のハードコアのつもりで入れた人が一度きりの終了を踏む。
        assertEquals(WorldIsAlsoHardcorePlugin.MODE_RESET, config.getString("on-death"));
    }

    @Test
    @DisplayName("終了まわりも同梱の config.yml とコード側の既定値が揃っている")
    void finaleDefaultsMatchTheCode() {
        assertEquals(FinaleManager.DEFAULT_DELAY_SECONDS, config.getLong("finale.delay-seconds"));
        assertEquals(FinaleManager.DEFAULT_TITLE, config.getString("finale.title"));
        assertEquals(FinaleManager.DEFAULT_SUBTITLE, config.getString("finale.subtitle"));
        assertEquals(FinaleManager.DEFAULT_MESSAGE, config.getString("finale.message"));
        assertEquals(FinaleManager.DEFAULT_RESULT_COMMAND, config.getString("finale.result-command"));
        assertEquals(FinaleManager.DEFAULT_JOIN_RESULT_COMMAND,
                config.getString("finale.join-result-command"));
        assertEquals(FinaleManager.DEFAULT_EMBED_TITLE, config.getString("finale.embed-title"));
        assertTrue(config.getBoolean("finale.apply-on-join"));
    }

    @Test
    @DisplayName("既定でハードコアを解除する")
    void finaleDisablesHardcore() {
        // 解除しないと、最後に死亡した本人が観戦者のまま取り残される。
        assertTrue(config.getBoolean("finale.disable-hardcore"));
    }

    @Test
    @DisplayName("成績表のコマンドは DeathCounter の /result を叩く")
    void finaleHandsTheBookToDeathCounter() {
        // ここが変わると本が配られなくなる。置換の目印も一緒に固定しておく。
        String command = config.getString("finale.result-command", "");
        assertTrue(command.startsWith("result "), "/result を叩いていない: " + command);
        assertTrue(command.contains("<player>"), "死亡者が入らない: " + command);
        assertTrue(command.contains("<elapsed>"), "経過時間が入らない: " + command);
        assertTrue(config.getString("finale.join-result-command", "").contains("<joiner>"),
                "後から来た人を名指ししていない");
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
