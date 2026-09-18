package io.github.worldisalsohardcore;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * 対象ワールドでのプレイヤー死亡を検知して、応答 ({@link DeathResponse}) を起動する。
 *
 * <p>応答がリセットなのか終了なのかはここでは決めない。config の {@code on-death} を見る
 * のはプラグイン側で、こちらは毎回そのときの応答を引き直す (reload で切り替わるため)。
 */
public final class DeathListener implements Listener {

    /** 監視対象ワールドの判定。reset と finale で共通。 */
    private final ResetManager worlds;
    /** 今の設定での応答。reload をまたいでも古い方を掴まないよう、その都度引く。 */
    private final Supplier<DeathResponse> response;

    /** 打ち消す直前に控えておいた死亡メッセージ。誰のものかも一緒に持つ。 */
    private UUID suppressedFor;
    private Component suppressedMessage;

    public DeathListener(ResetManager worlds, Supplier<DeathResponse> response) {
        this.worlds = worlds;
        this.response = response;
    }

    /**
     * チャットへ流れる死亡メッセージを打ち消す。代わりに全員へタイトルを出すため。
     *
     * <p>イベントを書き換えるので MONITOR では行えない (あちらは観測専用)。他プラグインの
     * 差し替えを上書きできるよう、MONITOR の1つ手前の HIGHEST で最後に手を入れる。
     *
     * <p>打ち消す前に文面を控える。Discord には死因として載せたいが、MONITOR で受ける
     * {@link #onPlayerDeath} はこの後に呼ばれるので、そこで読んでも null しか取れない。
     *
     * <p>落ちた持ち物を消すのもここ。{@link #onPlayerDeath} は MONITOR なので、
     * イベントに手を入れるならこちらでやる。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressDeathMessage(PlayerDeathEvent event) {
        Player player = event.getEntity();
        DeathResponse response = responseFor(player);
        if (response == null) {
            return;
        }
        this.suppressedFor = player.getUniqueId();
        this.suppressedMessage = event.deathMessage();
        event.deathMessage(null);

        if (response.clearsDeathDrops()) {
            // この後どのみち全員の持ち物を空にするので、地面に山を作らない。
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    // 他プラグインによる復活処理などを待ってから判断するため MONITOR で受ける。
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        DeathResponse response = responseFor(player);
        if (response == null) {
            return;
        }
        response.trigger(ResetCause.ofDeath(player, takeSuppressedMessage(player)));
    }

    /**
     * この死亡に応答するのは誰か。応答しないなら null。
     *
     * <p>対象ワールドの外での死亡と、もう応答が済んでいる場合 (リセット待ち・終了済み) は
     * 普通の死亡として扱う — 死亡メッセージもそのまま流す。
     */
    private DeathResponse responseFor(Player player) {
        if (!worlds.isManaged(player.getWorld())) {
            return null;
        }
        DeathResponse current = response.get();
        return current != null && current.handlesDeaths() ? current : null;
    }

    /** 控えてある死亡メッセージを、同じプレイヤーのものであれば取り出す。 */
    private Component takeSuppressedMessage(Player player) {
        if (!Objects.equals(suppressedFor, player.getUniqueId())) {
            return null;
        }
        Component message = suppressedMessage;
        this.suppressedFor = null;
        this.suppressedMessage = null;
        return message;
    }
}
