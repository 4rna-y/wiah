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

    // 他プラグインによるキャンセルや復活処理を待ってから判断するため MONITOR で受ける。
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!resetManager.isManaged(player.getWorld())) {
            return;
        }
        resetManager.trigger(player.getName(), player.getName() + " の死亡");
    }
}
