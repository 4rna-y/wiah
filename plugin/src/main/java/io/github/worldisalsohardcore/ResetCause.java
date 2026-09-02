package io.github.worldisalsohardcore;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

/**
 * リセットが起きた理由。ログの文面と Discord へ送る Embed の中身をここから作る。
 *
 * @param kind         死亡なのか、コマンドなのか、通知の試し撃ちなのか
 * @param subject      文面の {@code <player>} に差し込む名前
 * @param playerUuid   スキンの頭を引くための UUID。プレイヤーでなければ null
 * @param deathMessage 打ち消した死亡メッセージの平文。死亡以外では null
 * @param reason       ログに残す理由
 */
public record ResetCause(Kind kind, String subject, UUID playerUuid, String deathMessage,
                         String reason) {

    public enum Kind {
        /** プレイヤーが死んだ。 */
        DEATH,
        /** {@code /wiahc reset} で手動リセットした。 */
        COMMAND,
        /** {@code /wiahc testwebhook}。ワールドはリセットしない。 */
        TEST
    }

    /**
     * 死亡から作る。
     *
     * @param deathMessage {@link DeathListener} が打ち消す前に控えておいた死亡メッセージ
     */
    public static ResetCause ofDeath(Player player, Component deathMessage) {
        return new ResetCause(Kind.DEATH, player.getName(), player.getUniqueId(),
                plain(deathMessage), player.getName() + " の死亡");
    }

    /** {@code /wiahc reset} から作る。 */
    public static ResetCause ofCommand(String who, UUID uuid) {
        return new ResetCause(Kind.COMMAND, who, uuid, null, "/wiahc reset (" + who + ")");
    }

    /** {@code /wiahc testwebhook} から作る。 */
    public static ResetCause ofTest(String who, UUID uuid) {
        return new ResetCause(Kind.TEST, who, uuid, null, "/wiahc testwebhook (" + who + ")");
    }

    /** Embed で名前の上に出す見出し。 */
    public String subjectLabel() {
        return switch (kind) {
            case DEATH -> "死亡したプレイヤー";
            case COMMAND -> "リセット実行者";
            case TEST -> "テスト実行者";
        };
    }

    /** 試し撃ちか。本物のリセットと見分けが付くよう Embed の題に印を付ける。 */
    public boolean test() {
        return kind == Kind.TEST;
    }

    private static String plain(Component component) {
        return component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
