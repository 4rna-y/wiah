package io.github.worldisalsohardcore;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/** 対象ワールドでのプレイヤー死亡を検知してリセットを起動する。 */
public final class DeathListener implements Listener {

    private final ResetManager resetManager;

    public DeathListener(ResetManager resetManager) {
        this.resetManager = resetManager;
    }

    /**
     * チャットへ流れる死亡メッセージを打ち消す。代わりに全員へタイトルを出すため。
     *
     * <p>イベントを書き換えるので MONITOR では行えない (あちらは観測専用)。他プラグインの
     * 差し替えを上書きできるよう、MONITOR の1つ手前の HIGHEST で最後に手を入れる。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void suppressDeathMessage(PlayerDeathEvent event) {
        if (!resetManager.isManaged(event.getEntity().getWorld())) {
            return;
        }
        event.deathMessage(null);
    }

    // 他プラグインによる復活処理などを待ってから判断するため MONITOR で受ける。
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!resetManager.isManaged(player.getWorld())) {
            return;
        }
        resetManager.trigger(player.getName(), player.getName() + " の死亡");
    }
}
