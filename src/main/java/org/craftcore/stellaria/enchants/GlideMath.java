package org.craftcore.stellaria.enchants;

import org.bukkit.util.Vector;

/**
 * 跳躍・滑空加速の移動計算。プレイヤーの移動はクライアントが決めるため、サーバー側の getVelocity() は
 * 実際の動きとずれることがある。そのため、ここでは実際の位置の変化（1tick あたりの移動量）を基準にする。
 */
public final class GlideMath {

    private GlideMath() {
    }

    /** 前の tick より高さが上がらなくなったら、上昇が止まった（頂点に達した）とみなす。 */
    public static boolean reachedApex(double previousY, double currentY) {
        return currentY <= previousY;
    }

    /** 実際の 1tick の移動量に、視線の向きへ長さ strength の加速を足した速度。引数は変更しない。 */
    public static Vector boostedVelocity(Vector perTickMotion, Vector lookDirection, double strength) {
        return perTickMotion.clone().add(lookDirection.clone().normalize().multiply(strength));
    }
}
