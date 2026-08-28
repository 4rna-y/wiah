package io.github.worldisalsohardcore;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jetbrains.annotations.NotNull;

/**
 * 予約されたワールド削除を実行するためだけのブートストラップ。
 *
 * <p>Minecraft 26.x のサーバーは、プラグインの {@code onLoad()} よりも前に level.dat を読み込んで
 * ワールド設定 (seed / hardcore / ワールドタイプ) をメモリへ確定させる。そのため onLoad で
 * フォルダを削除しても、再生成されるワールドは削除前の設定をそのまま引き継いでしまう。
 * {@link PluginBootstrap#bootstrap} はそれより前に呼ばれるので、ここで削除する必要がある。
 */
public final class WorldIsAlsoHardcoreBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        ComponentLogger logger = context.getLogger();
        ResetManager.consumePendingReset(context.getDataDirectory(), new ResetManager.Log() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void error(String message) {
                logger.error(message);
            }
        });
    }
}
