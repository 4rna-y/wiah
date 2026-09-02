package io.github.worldisalsohardcore;

import java.time.Instant;

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
 *
 * <p>ここは「ワールドが作り直される瞬間」が分かる唯一の場所でもあるので、
 * {@link WorldClock} の開始時刻もここで打ち直す。
 */
public final class WorldIsAlsoHardcoreBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        ComponentLogger logger = context.getLogger();
        ResetManager.Log log = new ResetManager.Log() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void error(String message) {
                logger.error(message);
            }
        };

        if (ResetManager.consumePendingReset(context.getDataDirectory(), log)) {
            // 削除したワールドはこの後サーバーが作り直す。経過時間はここから数え直し。
            WorldClock.markNewWorld(context.getDataDirectory(), Instant.now(), log);
        } else {
            // 普通の再起動。初回起動やデータフォルダを消した後だけ起点を置く。
            WorldClock.ensureStarted(context.getDataDirectory(), Instant.now(), log);
        }
    }
}
