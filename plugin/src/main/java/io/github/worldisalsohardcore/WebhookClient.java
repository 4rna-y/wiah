package io.github.worldisalsohardcore;

import java.io.IOException;

/**
 * Discord の Webhook へ JSON を1件送るだけの口。
 *
 * <p>呼び出しは同期でブロックするので、サーバーの main スレッドから直接呼んではならない
 * ({@link DiscordNotifier} が専用スレッドへ追い出している)。
 *
 * <p>実装を差し替えられるようインターフェースにしてある。テストは HTTP を張らない偽物を挿す。
 */
public interface WebhookClient {

    /**
     * 送る。届いたら正常に戻る。
     *
     * @throws Rejected     送り直しても結果が変わらない拒否 (URL が無効、Webhook が消された等)
     * @throws IOException  一時的な失敗。送り直せば通るかもしれない
     */
    void post(String payloadJson) throws IOException;

    /** 送り直しても無駄な失敗。保留してある通知は捨ててよい。 */
    final class Rejected extends IOException {

        private static final long serialVersionUID = 1L;

        public Rejected(String message) {
            super(message);
        }
    }
}
