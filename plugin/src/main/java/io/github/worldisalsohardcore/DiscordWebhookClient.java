package io.github.worldisalsohardcore;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Discord の Webhook を HTTP で叩く {@link WebhookClient}。
 *
 * <pre>
 *   POST &lt;webhook-url&gt;   204 送信完了 / 429 混みすぎ / 4xx 受け付けない / 5xx Discord 側の不調
 * </pre>
 */
public final class DiscordWebhookClient implements WebhookClient {

    private final HttpClient http;
    private final URI webhookUrl;
    private final Duration timeout;

    public DiscordWebhookClient(URI webhookUrl, Duration timeout) {
        this.webhookUrl = webhookUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public void post(String payloadJson) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(webhookUrl)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            // 停止処理で割り込まれた場合。フラグを戻して呼び出し側に判断を返す。
            Thread.currentThread().interrupt();
            throw new IOException("中断されました", e);
        }

        int status = response.statusCode();
        if (status / 100 == 2) {
            return;
        }
        String detail = "Discord が " + status + " を返しました" + body(response);
        // 429 は「今は混んでいる」なので送り直せば通る。それ以外の 4xx は URL や中身の問題。
        if (status == 429 || status / 100 == 5) {
            throw new IOException(detail);
        }
        throw new Rejected(detail);
    }

    /** 待ち受け先。設定の確認に表示する。トークンは伏せる。 */
    public String describe() {
        String path = webhookUrl.getPath();
        int lastSlash = path.lastIndexOf('/');
        String masked = lastSlash < 0 ? path : path.substring(0, lastSlash + 1) + "***";
        return webhookUrl.getScheme() + "://" + webhookUrl.getHost() + masked;
    }

    private static String body(HttpResponse<String> response) {
        String detail = response.body() == null ? "" : response.body().strip();
        return detail.isEmpty() ? "" : ": " + detail;
    }
}
