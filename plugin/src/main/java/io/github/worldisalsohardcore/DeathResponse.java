package io.github.worldisalsohardcore;

/**
 * 対象ワールドでプレイヤーが死んだときの応答。
 *
 * <p>2通りある。ワールドを作り直す {@link ResetManager} と、ワールドを残したまま
 * ハードコアを終える {@link FinaleManager}。どちらが呼ばれるかは config の
 * {@code on-death} で決まる ({@link WorldIsAlsoHardcorePlugin#deathResponse()})。
 */
public interface DeathResponse {

    /**
     * 死亡に手を入れるか。
     *
     * <p>false の間、死亡は普通の死亡として扱われる — {@link DeathListener} は
     * チャットの死亡メッセージを打ち消さず、落ちた物にも触らない。ハードコアが
     * 終わった後の死亡がこれにあたる。リセットの方は「終わった後」が無い
     * (発火したらサーバーごと止まる) ので常に true。
     */
    boolean handlesDeaths();

    /** 死亡したときに、地面へ落ちた持ち物と経験値も消すか。 */
    boolean clearsDeathDrops();

    /**
     * 応答を始める。同じ応答が二重に走らないようにするのは実装側の責任。
     *
     * @return 実際に発火したら true。すでに発火済み・終了済みなら false
     */
    boolean trigger(ResetCause cause);
}
