package io.github.worldisalsohardcore;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * ワールドリセットを Discord の Webhook へ Embed で流す。
 *
 * <p>送信は HTTP でブロックするので専用の1本のスレッドへ追い出す。main スレッドからは
 * 投げるだけで待たない。
 *
 * <p><b>送り損ねないための順番</b>: 死亡の数秒後にはサーバーが止まるので、送信の途中で
 * プロセスが消える可能性がある。そこで組み立てた JSON を先に {@value #PENDING_FILE} へ
 * 書き出してから送り、届いたら消す。プラグインのデータフォルダはリセットで消えないため、
 * 送れなかった通知は次回起動時の {@link #resendPending()} で送り直せる。
 *
 * <p>逆に「送信は成功したが消す前に落ちた」場合は次回に同じ通知をもう一度送ってしまう。
 * 取りこぼすよりは二重に出る方がましと判断している。
 */
public final class DiscordNotifier implements AutoCloseable {

    /** まだ送れていない通知を残しておくファイル。中身は送るはずだった JSON そのもの。 */
    static final String PENDING_FILE = "pending-webhook.json";

    /** 停止時に送信の終了を待つ既定の時間 (秒)。 */
    static final long DEFAULT_FLUSH_TIMEOUT_SECONDS = 8L;

    /** Embed の既定の題。 */
    static final String DEFAULT_EMBED_TITLE = "ワールドがリセットされました";

    /** Embed の既定の色 (0xE74C3C)。 */
    static final int DEFAULT_EMBED_COLOR = 15158332;

    /** 頭の画像の既定の取得元。{@code <uuid>} と {@code <player>} が置き換わる。 */
    static final String DEFAULT_HEAD_IMAGE_URL = "https://mc-heads.net/avatar/<uuid>/128";

    private final WebhookClient client;
    private final Path dataDirectory;
    private final Settings settings;
    private final ResetManager.Log log;
    private final ExecutorService worker;

    public DiscordNotifier(WebhookClient client, Path dataDirectory, Settings settings,
            ResetManager.Log log) {
        this.client = client;
        this.dataDirectory = dataDirectory;
        this.settings = settings;
        this.log = log;
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "WorldIsAlsoHardcore-discord");
            // 送信が残っていても JVM の終了を妨げない。取りこぼしは close() で待つ。
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Embed の見た目。config.yml から作る。 */
    public record Settings(String title, int color, String username, String headImageUrl,
                           String footer) {

        /** 題だけ差し替えた写し。 */
        Settings withTitle(String other) {
            return new Settings(other, color, username, headImageUrl, footer);
        }
    }

    // ------------------------------------------------------------------ 送る

    /**
     * リセットを通知する。
     *
     * @param elapsed ワールド生成からの経過時間。分からなければ null
     * @param at      通知に載せる時刻 (死亡した瞬間)
     */
    public CompletableFuture<Void> notifyReset(ResetCause cause, Duration elapsed, Instant at) {
        return notifyReset(cause, elapsed, at, null);
    }

    /**
     * 題を差し替えて通知する。
     *
     * @param title 差し替える Embed の題。null なら {@code discord.embed-title} のまま。
     *              ハードコアの終了はリセットではないので、別の文言で送るために要る
     */
    public CompletableFuture<Void> notifyReset(ResetCause cause, Duration elapsed, Instant at,
            String title) {
        Settings used = title == null || title.isBlank() ? settings : settings.withTitle(title);
        String payload = buildPayload(used, cause, elapsed, at);
        // 送る前に残す。ここから先はいつプロセスが消えてもよい。
        writePending(payload);
        return submit(payload);
    }

    /** 前回送れなかった通知を送り直す。無ければ何もしない。起動時に1回呼ぶ。 */
    public CompletableFuture<Void> resendPending() {
        Path pending = dataDirectory.resolve(PENDING_FILE);
        if (!Files.isRegularFile(pending)) {
            return CompletableFuture.completedFuture(null);
        }
        String payload;
        try {
            payload = Files.readString(pending, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("送れていない Discord 通知を読めませんでした: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
        if (payload.isBlank()) {
            deletePending();
            return CompletableFuture.completedFuture(null);
        }
        log.info("前回送れなかった Discord 通知を送り直します。");
        return submit(payload);
    }

    private CompletableFuture<Void> submit(String payload) {
        try {
            return CompletableFuture.runAsync(() -> postAndConsume(payload), worker);
        } catch (RejectedExecutionException e) {
            // close() の後。保留ファイルは残っているので次回起動時に送られる。
            log.error("Discord への送信を受け付けられませんでした (既に停止処理中)。");
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 送って、届いたら保留を消す。専用スレッドから呼ばれる。 */
    private void postAndConsume(String payload) {
        try {
            client.post(payload);
            deletePending();
            log.info("Discord へ通知しました。");
        } catch (WebhookClient.Rejected e) {
            // 何度送っても同じ結果なので抱え続けない。
            deletePending();
            log.error("Discord が通知を受け付けませんでした: " + e.getMessage() + " (この通知は破棄します)");
        } catch (IOException e) {
            log.error("Discord へ通知できませんでした: " + e.getMessage() + " (次回起動時に送り直します)");
        }
    }

    /**
     * 送信の終了を待ってから片付ける。
     *
     * <p>wiah は死亡の数秒後にサーバーを止めるので、ここで待たないと最後の通知を取りこぼす。
     */
    public void close(Duration timeout) {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                log.error("Discord への送信が " + timeout.toSeconds()
                        + "秒で終わりませんでした。次回起動時に送り直します。");
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(DEFAULT_FLUSH_TIMEOUT_SECONDS));
    }

    // ------------------------------------------------------------------ 保留ファイル

    private void writePending(String payload) {
        Path pending = dataDirectory.resolve(PENDING_FILE);
        try {
            Files.createDirectories(dataDirectory);
            Files.writeString(pending, payload, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 書けなくても送信自体は試す。落ちたときに送り直せなくなるだけ。
            log.error("Discord 通知を保留ファイルへ書けませんでした: " + e.getMessage());
        }
    }

    private void deletePending() {
        try {
            Files.deleteIfExists(dataDirectory.resolve(PENDING_FILE));
        } catch (IOException e) {
            // 消せないと次回起動時に同じ通知をもう一度送ってしまう。
            log.error("送信済みの Discord 通知を消せませんでした: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Embed の組み立て

    /** 送る JSON を作る。副作用が無いのでテストから直接叩ける。 */
    static String buildPayload(Settings settings, ResetCause cause, Duration elapsed, Instant at) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", cause.test() ? "[テスト] " + settings.title() : settings.title());
        embed.addProperty("color", settings.color());
        embed.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(at));

        JsonArray fields = new JsonArray();
        fields.add(field(cause.subjectLabel(), cause.subject(), true));
        fields.add(field("ワールド経過時間",
                elapsed == null ? "不明" : WorldClock.formatElapsed(elapsed), true));
        if (cause.deathMessage() != null && !cause.deathMessage().isBlank()) {
            fields.add(field("死因", cause.deathMessage(), false));
        }
        embed.add("fields", fields);

        String head = headImageUrl(settings.headImageUrl(), cause);
        if (head != null) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", head);
            embed.add("thumbnail", thumbnail);
        }

        if (settings.footer() != null && !settings.footer().isBlank()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", settings.footer());
            embed.add("footer", footer);
        }

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        if (settings.username() != null && !settings.username().isBlank()) {
            payload.addProperty("username", settings.username());
        }
        payload.add("embeds", embeds);
        return payload.toString();
    }

    /**
     * 頭の画像 (HeadFace) の URL。
     *
     * <p>スキンの描画は外部のアバターサービスに任せる。テンプレートの {@code <uuid>} と
     * {@code <player>} を差し替えるだけなので、mc-heads 以外へ向け直すこともできる。
     * UUID を要求するテンプレートなのに相手がプレイヤーでない (コンソール等) 場合は
     * 頭を出さない。
     */
    private static String headImageUrl(String template, ResetCause cause) {
        if (template == null || template.isBlank()) {
            return null;
        }
        UUID uuid = cause.playerUuid();
        if (template.contains("<uuid>") && uuid == null) {
            return null;
        }
        return template
                .replace("<uuid>", uuid == null ? "" : uuid.toString())
                .replace("<player>", URLEncoder.encode(cause.subject(), StandardCharsets.UTF_8));
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value);
        field.addProperty("inline", inline);
        return field;
    }
}
