package io.github.worldisalsohardcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 死亡を受けたときの振る舞い。
 *
 * <p>本物のクライアントを繋がずに済ませるため、イベントとプレイヤーはモックで作る。
 * ここで見るのは自前の分岐だけ (対象ワールドか / まだ応答するか / 死亡メッセージを
 * 打ち消すか) で、その先のリセット一周は {@code :e2e} が担当する。
 */
class DeathListenerTest {

    private final ResetManager resetManager = mock(ResetManager.class);
    private final FakeResponse response = new FakeResponse();
    private final DeathListener listener = new DeathListener(resetManager, () -> response);

    @Test
    @DisplayName("対象ワールドでの死亡はチャットの死亡メッセージを打ち消す")
    void suppressesDeathMessageInManagedWorld() {
        PlayerDeathEvent event = deathOf("Steve", managed(true));

        listener.suppressDeathMessage(event);

        // 代わりに全員へタイトルを出すので、チャットへは流さない。
        verify(event).deathMessage((Component) null);
    }

    @Test
    @DisplayName("対象ワールドでの死亡は応答を起動する")
    void triggersResponseInManagedWorld() {
        PlayerDeathEvent event = deathOf("Steve", managed(true));

        listener.onPlayerDeath(event);

        ResetCause cause = response.triggered;
        assertEquals(ResetCause.Kind.DEATH, cause.kind());
        assertEquals("Steve", cause.subject());
    }

    @Test
    @DisplayName("打ち消した死亡メッセージを死因として応答へ渡す")
    void carriesTheSuppressedDeathMessage() {
        // 打ち消し (HIGHEST) は起動 (MONITOR) より先に呼ばれる。後から event を読んでも
        // null しか返らないので、打ち消す前に控えておけているかを見る。
        PlayerDeathEvent event = deathOf("Steve", managed(true));
        when(event.deathMessage()).thenReturn(Component.text("Steve was slain by Zombie"));

        listener.suppressDeathMessage(event);
        when(event.deathMessage()).thenReturn(null);
        listener.onPlayerDeath(event);

        assertEquals("Steve was slain by Zombie", response.triggered.deathMessage());
    }

    @Test
    @DisplayName("死亡メッセージが無ければ死因も持たせない")
    void toleratesMissingDeathMessage() {
        PlayerDeathEvent event = deathOf("Steve", managed(true));
        when(event.deathMessage()).thenReturn(null);

        listener.suppressDeathMessage(event);
        listener.onPlayerDeath(event);

        assertNull(response.triggered.deathMessage());
    }

    @Test
    @DisplayName("対象外のワールドでの死亡には何もしない")
    void ignoresDeathInUnmanagedWorld() {
        PlayerDeathEvent event = deathOf("Steve", managed(false));

        listener.suppressDeathMessage(event);
        listener.onPlayerDeath(event);

        verify(event, never()).deathMessage(any());
        assertNull(response.triggered);
    }

    @Test
    @DisplayName("死亡メッセージの打ち消しは応答の起動とは独立している")
    void suppressionDoesNotTriggerResponse() {
        // 打ち消しは HIGHEST、起動は MONITOR で別々に呼ばれる。
        // 片方だけが走ってももう片方を巻き込まないこと。
        PlayerDeathEvent event = deathOf("Steve", managed(true));

        listener.suppressDeathMessage(event);

        assertNull(response.triggered);
    }

    @Test
    @DisplayName("もう応答しないなら、対象ワールドでも普通の死亡として扱う")
    void leavesDeathsAloneOnceTheResponseIsDone() {
        // ハードコアが終わった後の死亡。黙って消されると、アドベンチャーで落ちて死んだ人が
        // 何が起きたのか分からなくなる。
        response.handlesDeaths = false;
        PlayerDeathEvent event = deathOf("Steve", managed(true));

        listener.suppressDeathMessage(event);
        listener.onPlayerDeath(event);

        verify(event, never()).deathMessage(any());
        assertNull(response.triggered);
    }

    @Test
    @DisplayName("終了する応答なら、落ちた持ち物と経験値も消す")
    void clearsDropsWhenTheResponseAsksForIt() {
        response.clearsDeathDrops = true;
        PlayerDeathEvent event = deathOf("Steve", managed(true));

        listener.suppressDeathMessage(event);

        assertTrue(event.getDrops().isEmpty(), "地面に持ち物が残っている");
        verify(event).setDroppedExp(0);
    }

    @Test
    @DisplayName("リセットする応答は落ちた持ち物に触らない")
    void leavesDropsAloneForReset() {
        // ワールドごと消えるので触る意味が無い。
        PlayerDeathEvent event = deathOf("Steve", managed(true));

        listener.suppressDeathMessage(event);

        assertEquals(1, event.getDrops().size());
        verify(event, never()).setDroppedExp(anyInt());
    }

    // ------------------------------------------------------------------ 補助

    /** 差し替えた応答。何を聞かれたか・何で起動されたかを覚えるだけ。 */
    private static final class FakeResponse implements DeathResponse {

        private boolean handlesDeaths = true;
        private boolean clearsDeathDrops;
        private ResetCause triggered;

        @Override
        public boolean handlesDeaths() {
            return handlesDeaths;
        }

        @Override
        public boolean clearsDeathDrops() {
            return clearsDeathDrops;
        }

        @Override
        public boolean trigger(ResetCause cause) {
            this.triggered = cause;
            return true;
        }
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
        List<ItemStack> drops = new ArrayList<>();
        drops.add(mock(ItemStack.class));
        when(event.getDrops()).thenReturn(drops);
        return event;
    }
}
