package io.github.worldisalsohardcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Discord へ送る Embed の中身と、送り損ねた通知の扱い。
 *
 * <p>HTTP は張らない。組み立てた JSON は {@link DiscordNotifier#buildPayload} を直接叩いて見る。
 * 保留ファイルまわりは偽の {@link WebhookClient} を挿して確かめる。
 */
class DiscordNotifierTest {

    @TempDir
    Path tmp;

    private static final DiscordNotifier.Settings SETTINGS = new DiscordNotifier.Settings(
            DiscordNotifier.DEFAULT_EMBED_TITLE,
            DiscordNotifier.DEFAULT_EMBED_COLOR,
            "",
            DiscordNotifier.DEFAULT_HEAD_IMAGE_URL,
            "");

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-0000deadbeef");
    private static final Instant AT = Instant.parse("2026-09-03T12:00:00Z");

    // ------------------------------------------------------------------ Embed の中身

    @Test
    @DisplayName("死亡したプレイヤー・経過時間・死因を Embed に載せる")
    void embedCarriesTheEssentials() {
        JsonObject embed = embedOf(death(), Duration.ofDays(3).plusHours(4).plusMinutes(12));

        assertEquals(DiscordNotifier.DEFAULT_EMBED_TITLE, embed.get("title").getAsString());
        assertEquals("Steve", fieldValue(embed, "死亡したプレイヤー"));
        assertEquals("3日 4時間 12分", fieldValue(embed, "ワールド経過時間"));
        assertEquals("Steve was slain by Zombie", fieldValue(embed, "死因"));
        assertEquals("2026-09-03T12:00:00Z", embed.get("timestamp").getAsString());
    }

    @Test
    @DisplayName("サムネイルはそのプレイヤーの頭の画像")
    void embedShowsTheHeadFace() {
        JsonObject embed = embedOf(death(), Duration.ofMinutes(5));

        assertEquals("https://mc-heads.net/avatar/" + STEVE + "/128",
                embed.getAsJsonObject("thumbnail").get("url").getAsString());
    }

    @Test
    @DisplayName("UUID が要るテンプレートでプレイヤーでなければ頭を出さない")
    void noHeadForConsole() {
        // コンソールからの /wiahc reset。頭の代わりに何かを出すより、出さない方がまし。
        JsonObject embed = embedOf(ResetCause.ofCommand("CONSOLE", null), Duration.ofMinutes(5));

        assertFalse(embed.has("thumbnail"));
        assertEquals("CONSOLE", fieldValue(embed, "リセット実行者"));
    }

    @Test
    @DisplayName("頭の取得元は差し替えられる")
    void headImageUrlIsATemplate() {
        DiscordNotifier.Settings crafatar = new DiscordNotifier.Settings(
                SETTINGS.title(), SETTINGS.color(), "",
                "https://crafatar.com/avatars/<uuid>?size=128&overlay", "");

        JsonObject embed = embedOf(crafatar, death(), Duration.ofMinutes(5));

        assertEquals("https://crafatar.com/avatars/" + STEVE + "?size=128&overlay",
                embed.getAsJsonObject("thumbnail").get("url").getAsString());
    }

    @Test
    @DisplayName("死因が無ければその欄は出さない")
    void omitsCauseWhenUnknown() {
        JsonObject embed = embedOf(ResetCause.ofCommand("Steve", STEVE), Duration.ofMinutes(5));

        assertNull(fieldValue(embed, "死因"));
    }

    @Test
    @DisplayName("経過時間が分からなければ「不明」と出す")
    void unknownElapsed() {
        JsonObject embed = embedOf(death(), null);

        assertEquals("不明", fieldValue(embed, "ワールド経過時間"));
    }

    @Test
    @DisplayName("テスト送信は本物のリセットと見分けが付く")
    void testSendIsMarked() {
        JsonObject embed = embedOf(ResetCause.ofTest("Steve", STEVE), Duration.ofMinutes(5));

        assertTrue(embed.get("title").getAsString().startsWith("[テスト]"),
                embed.get("title").getAsString());
    }

    // ------------------------------------------------------------------ 保留ファイル

    @Test
    @DisplayName("送る前に保留へ書き出し、届いたら消す")
    void pendingIsWrittenThenConsumed() throws Exception {
        FakeWebhookClient client = new FakeWebhookClient();

        try (DiscordNotifier notifier = notifier(client)) {
            notifier.notifyReset(death(), Duration.ofMinutes(5), AT).join();
        }

        assertEquals(1, client.sent.size());
        assertFalse(Files.exists(pending()), "届いたのに保留が残っている");
    }

    @Test
    @DisplayName("送れなかった通知は保留に残る")
    void failedSendKeepsPending() throws Exception {
        FakeWebhookClient client = new FakeWebhookClient();
        client.failWith(new IOException("接続できません"));
        RecordingLog log = new RecordingLog();

        try (DiscordNotifier notifier = notifier(client, log)) {
            notifier.notifyReset(death(), Duration.ofMinutes(5), AT).join();
        }

        assertTrue(Files.exists(pending()), "送れていないのに保留が消えている");
        assertEquals(client.lastPayload(), Files.readString(pending(), StandardCharsets.UTF_8));
        assertTrue(log.anyErrorContains("次回起動時に送り直します"), log.error.toString());
    }

    @Test
    @DisplayName("受け付けられなかった通知は抱え込まずに捨てる")
    void rejectedSendDropsPending() throws Exception {
        // Webhook が消された場合など。何度送り直しても同じ結果になる。
        FakeWebhookClient client = new FakeWebhookClient();
        client.failWith(new WebhookClient.Rejected("Discord が 404 を返しました"));
        RecordingLog log = new RecordingLog();

        try (DiscordNotifier notifier = notifier(client, log)) {
            notifier.notifyReset(death(), Duration.ofMinutes(5), AT).join();
        }

        assertFalse(Files.exists(pending()), "送り直しても無駄な通知が残っている");
        assertTrue(log.anyErrorContains("破棄します"), log.error.toString());
    }

    @Test
    @DisplayName("次回起動時に保留を送り直す")
    void resendsPendingOnStartup() throws Exception {
        // リセットで停止したあと、次の起動でここが呼ばれる。
        String payload = DiscordNotifier.buildPayload(SETTINGS, death(), Duration.ofMinutes(5), AT);
        Files.writeString(pending(), payload, StandardCharsets.UTF_8);
        FakeWebhookClient client = new FakeWebhookClient();

        try (DiscordNotifier notifier = notifier(client)) {
            notifier.resendPending().join();
        }

        assertEquals(payload, client.lastPayload(), "保留していた通知がそのまま送られていない");
        assertFalse(Files.exists(pending()), "送り直したのに保留が残っている");
    }

    @Test
    @DisplayName("保留が無ければ起動時に何も送らない")
    void noPendingSendsNothing() throws Exception {
        FakeWebhookClient client = new FakeWebhookClient();

        try (DiscordNotifier notifier = notifier(client)) {
            notifier.resendPending().join();
        }

        assertTrue(client.sent.isEmpty(), "送るものが無いのに送っている: " + client.sent);
    }

    @Test
    @DisplayName("停止時は送信の終了まで待つ")
    void closeWaitsForTheSend() throws Exception {
        // 死亡の数秒後にサーバーが止まる。ここで待たないと通知そのものを取りこぼす。
        FakeWebhookClient client = new FakeWebhookClient();
        DiscordNotifier notifier = notifier(client);

        notifier.notifyReset(death(), Duration.ofMinutes(5), AT);
        notifier.close(Duration.ofSeconds(5));

        assertEquals(1, client.sent.size(), "停止が送信を追い越した");
    }

    // ------------------------------------------------------------------ 補助

    private DiscordNotifier notifier(WebhookClient client) {
        return notifier(client, new RecordingLog());
    }

    private DiscordNotifier notifier(WebhookClient client, RecordingLog log) {
        return new DiscordNotifier(client, tmp, SETTINGS, log);
    }

    private Path pending() {
        return tmp.resolve(DiscordNotifier.PENDING_FILE);
    }

    private static ResetCause death() {
        return new ResetCause(ResetCause.Kind.DEATH, "Steve", STEVE,
                "Steve was slain by Zombie", "Steve の死亡");
    }

    private static JsonObject embedOf(ResetCause cause, Duration elapsed) {
        return embedOf(SETTINGS, cause, elapsed);
    }

    private static JsonObject embedOf(DiscordNotifier.Settings settings, ResetCause cause,
            Duration elapsed) {
        JsonObject payload = JsonParser
                .parseString(DiscordNotifier.buildPayload(settings, cause, elapsed, AT))
                .getAsJsonObject();
        return payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    /** Embed の fields から名前で1つ引く。無ければ null。 */
    private static String fieldValue(JsonObject embed, String name) {
        for (var element : embed.getAsJsonArray("fields")) {
            JsonObject field = element.getAsJsonObject();
            if (name.equals(field.get("name").getAsString())) {
                return field.get("value").getAsString();
            }
        }
        return null;
    }
}
