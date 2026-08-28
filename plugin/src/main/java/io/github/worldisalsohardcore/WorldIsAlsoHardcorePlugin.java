package io.github.worldisalsohardcore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ハードコアワールドで誰か1人でも死亡したら、参加中の全員をキックしてワールドをリセットする。
 *
 * <p>稼働中のサーバーからは主ワールドを削除できないため、リセットは2段階で行う。
 * <ol>
 *   <li>死亡検知時: 全員をキックし、削除対象を予約ファイルへ書き出してサーバーを停止する。</li>
 *   <li>次回起動時 ({@link WorldIsAlsoHardcoreBootstrap}, ワールド設定が読まれる前):
 *       予約されたフォルダを削除する。削除されたワールドはサーバーが自動的に再生成する。</li>
 * </ol>
 */
public final class WorldIsAlsoHardcorePlugin extends JavaPlugin {

    private ResetManager resetManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.resetManager = new ResetManager(this);

        getServer().getPluginManager().registerEvents(new DeathListener(resetManager), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("wiahc", "WorldIsAlsoHardcore の管理コマンド",
                        new WiahcCommand(this, resetManager)));

        resetManager.warnAboutConfiguration();
    }

    public ResetManager resetManager() {
        return resetManager;
    }
}
