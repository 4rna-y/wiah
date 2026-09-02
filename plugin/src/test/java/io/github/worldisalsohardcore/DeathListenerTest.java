package io.github.worldisalsohardcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 死亡を受けたときの振る舞い。
 *
 * <p>本物のクライアントを繋がずに済ませるため、イベントとプレイヤーはモックで作る。
 * ここで見るのは自前の分岐だけ (対象ワールドか / 死亡メッセージを打ち消すか) で、
 * その先のリセット一周は {@code :e2e} が担当する。
 */
class DeathListenerTest {

    private final ResetManager resetManager = mock(ResetManager.class);
    private final DeathListener listener = new DeathListener(resetManager);

    @Test
    @DisplayName("対象ワールドでの死亡はチャットの死亡メッセージを打ち消す")
    void suppressesDeathMessageInManagedWorld() {
        PlayerDeathEvent event = deathOf("Steve", managed(true));

        listener.suppressDeathMessage(event);

        // 代わりに全員へタイトルを出すので、チャットへは流さない。
        verify(event).deathMessage((Component) null);
    }

    @Test
    @DisplayName("対象ワールドでの死亡はリセットを起動する")
    void triggersResetInManagedWorld() {
        PlayerDeathEvent event = deathOf("Steve", managed(true));

        listener.onPlayerDeath(event);

        ResetCause cause = triggeredCause();
        assertEquals(ResetCause.Kind.DEATH, cause.kind());
        assertEquals("Steve", cause.subject());
    }

    @Test
    @DisplayName("打ち消した死亡メッセージを死因としてリセットへ渡す")
    void carriesTheSuppressedDeathMessage() {
        // 打ち消し (HIGHEST) は起動 (MONITOR) より先に呼ばれる。後から event を読んでも
        // null しか返らないので、打ち消す前に控えておけているかを見る。
        PlayerDeathEvent event = deathOf("Steve", managed(true));
        when(event.deathMessage()).thenReturn(Component.text("Steve was slain by Zombie"));

        listener.suppressDeathMessage(event);
        when(event.deathMessage()).thenReturn(null);
        listener.onPlayerDeath(event);

        assertEquals("Steve was slain by Zombie", triggeredCause().deathMessage());
    }

    @Test
    @DisplayName("死亡メッセージが無ければ死因も持たせない")
    void toleratesMissingDeathMessage() {
        PlayerDeathEvent event = deathOf("Steve", managed(true));
        when(event.deathMessage()).thenReturn(null);

        listener.suppressDeathMessage(event);
        listener.onPlayerDeath(event);

        assertNull(triggeredCause().deathMessage());
    }

    @Test
    @DisplayName("対象外のワールドでの死亡には何もしない")
    void ignoresDeathInUnmanagedWorld() {
        PlayerDeathEvent event = deathOf("Steve", managed(false));

        listener.suppressDeathMessage(event);
        listener.onPlayerDeath(event);

        verify(event, never()).deathMessage(any());
        verify(resetManager, never()).trigger(any());
    }

    @Test
    @DisplayName("死亡メッセージの打ち消しはリセットの起動とは独立している")
    void suppressionDoesNotTriggerReset() {
        // 打ち消しは HIGHEST、起動は MONITOR で別々に呼ばれる。
        // 片方だけが走ってももう片方を巻き込まないこと。
        PlayerDeathEvent event = deathOf("Steve", managed(true));

        listener.suppressDeathMessage(event);

        verify(resetManager, never()).trigger(any());
    }

    // ------------------------------------------------------------------ 補助

    private ResetCause triggeredCause() {
        ArgumentCaptor<ResetCause> captor = ArgumentCaptor.forClass(ResetCause.class);
        verify(resetManager).trigger(captor.capture());
        return captor.getValue();
    }

    private World managed(boolean isManaged) {
        World world = mock(World.class);
        when(resetManager.isManaged(world)).thenReturn(isManaged);
        return world;
    }

    private static PlayerDeathEvent deathOf(String name, World world) {
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));

        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(player);
        return event;
    }
}
