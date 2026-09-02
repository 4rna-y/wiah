package io.github.worldisalsohardcore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** HTTP を張らない {@link WebhookClient}。送られた JSON を溜め、失敗も演じる。 */
final class FakeWebhookClient implements WebhookClient {

    /** 送信は専用スレッドから来るので、読み書きは同期して行う。 */
    final List<String> sent = java.util.Collections.synchronizedList(new ArrayList<>());

    /** null なら成功。入っていればそれを投げる。 */
    private volatile IOException failure;

    void failWith(IOException failure) {
        this.failure = failure;
    }

    @Override
    public void post(String payloadJson) throws IOException {
        sent.add(payloadJson);
        if (failure != null) {
            throw failure;
        }
    }

    String lastPayload() {
        synchronized (sent) {
            return sent.isEmpty() ? null : sent.get(sent.size() - 1);
        }
    }
}
