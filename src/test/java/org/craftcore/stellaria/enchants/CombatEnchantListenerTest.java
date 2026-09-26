package org.craftcore.stellaria.enchants;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CombatEnchantListenerTest {

    @Test
    void pursuitRestoresInvulnerabilityLastDamageAndVelocityAfterTheHit() {
        LivingEntity target = mock(LivingEntity.class);
        Player source = mock(Player.class);
        Vector velocity = new Vector(0.1, 0.2, 0.3);
        when(target.getNoDamageTicks()).thenReturn(12);
        when(target.getLastDamage()).thenReturn(7.0);
        when(target.getVelocity()).thenReturn(velocity);

        CombatEnchantListener.applyPursuitDamage(target, source, 3.5);

        // 本命の攻撃の無敵時間と「直前のダメージ」を残しておかないと、次の剣の攻撃から追撃分が差し引かれる
        InOrder order = inOrder(target);
        order.verify(target).setNoDamageTicks(0);
        order.verify(target).damage(3.5, source);
        order.verify(target).setNoDamageTicks(12);
        order.verify(target).setLastDamage(7.0);
        order.verify(target).setVelocity(velocity);
    }
}
