package dev.sdm.torque_foundry.physics.hook.impl;

import dev.sdm.torque_foundry.physics.PhysicsConstants;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.hook.PhysicsHook;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.simulation.SimulationContext;

/**
 * Пример физического условия: износ валов на оборотах выше безопасного
 * лимита МАТЕРИАЛА (см. PhysicsMaterial.maxSafeSpeedRpm()).
 *
 * <p>Железный вал (лимит ~301 RPM) на 256 RPM не изначивается вовсе;
 * на 400 RPM теряет момент за каждый проход. Деревянный вал (лимит ~107)
 * на 256 RPM теряет быстро.
 *
 * <p>Mutable-архитектура: хук МОДИФИЦИРУЕТ переданный RotationalPower
 * и возвращает его же — новых объектов на тик не создаётся.
 */
public final class ShaftWearHook implements PhysicsHook {

    /** Доля потери момента за ребро на единицу превышения (excess/safe). */
    private static final double WEAR_FACTOR = 0.01;

    @Override
    public RotationalPower onTransmit(MechanicalMachine from, MechanicalMachine to, RotationalPower power, SimulationContext context) {
        if (!(from instanceof dev.sdm.torque_foundry.core.machine.ShaftMachine)) {
            return power;
        }

        // Лимит материала в RPM -> milli-RPM (юниты скорости сети)
        final long safe = from.getMaterial().maxSafeSpeedRpm() * PhysicsConstants.SCALE;
        final long speed = power.getSpeedRaw();
        if (speed <= safe) {
            return power;
        }

        final long excess = speed - safe;
        final long loss = Math.round(power.getTorqueRaw() * (excess / (double) safe) * WEAR_FACTOR);
        if (loss <= 0) {
            return power;
        }

        // Вал люфтит: теряется момент (КПД), обороты сети сохраняются.
        // Мутируем объект мощности вместо создания нового.
        power.setTorqueRaw(Math.max(0, power.getTorqueRaw() - loss));
        return power;
    }
}
