package io.github.worldisalsohardcore;

import java.util.Objects;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** 対象ワールドでのプレイヤー死亡を検知してリセットを起動する。 */
public final class DeathListener implements Listener {

    private final ResetManager resetManager;

    /** 打ち消す直前に控えておいた死亡メッセージ。誰のものかも一緒に持つ。 */
    private UUID suppressedFor;
    private Component suppressedMessage;

    public DeathListener(ResetManager resetManager) {
        this.resetManager = resetManager;
    }

    /**
     * チャットへ流れる死亡メッセージを打ち消す。代わりに全員へタイトルを出すため。
     *
     * <p>イベントを書き換えるので MONITOR では行えない (あちらは観測専用)。他プラグインの
     * 差し替えを上書きできるよう、MONITOR の1つ手前の HIGHEST で最後に手を入れる。
     *
     * <p>打ち消す前に文面を控える。Discord には死因として載せたいが、MONITOR で受ける
     * {@link #onPlayerDeath} はこの後に呼ばれるので、そこで読んでも null しか取れない。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressDeathMessage(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!resetManager.isManaged(player.getWorld())) {
            return;
        }
        this.suppressedFor = player.getUniqueId();
        this.suppressedMessage = event.deathMessage();
        event.deathMessage(null);
    }

    // 他プラグインによる復活処理などを待ってから判断するため MONITOR で受ける。
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!resetManager.isManaged(player.getWorld())) {
            return;
        }
        resetManager.trigger(ResetCause.ofDeath(player, takeSuppressedMessage(player)));
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
