package dev.sdm.torque_foundry.physics.simulation.physics;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.MechanicalPowerConstants;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.simulation.SimulationContext;

import java.util.ArrayList;
import dev.sdm.torque_foundry.physics.simulation.SimulationContext;

/**
 * Пример физического условия: износ валов на оборотах выше безопасного
 * лимита МАТЕРИАЛА (см. MachineMaterial.maxSafeSpeedRpm).
 *
 * Железный вал (256 RPM) на 256 RPM не изнашивается вовсе; на 400 RPM
 * (стальной лимит) теряет момент за каждый проход. Деревянный вал
 * (лимит 120 RPM) на 256 RPM теряет очень быстро.
 *
 * Mutable-архитектура: хук МОДИФИЦИРУЕТ переданный MechanicalPower
 * и возвращает его же — новых объектов на тик не создаётся.
 */
public final class ShaftWearHook implements PhysicsHook {

    /** Доля потери момента за ребро на единицу превышения (excess/safe). */
    private static final double WEAR_FACTOR = 0.01;

    @Override
    public MechanicalPower onTransmit(MechanicalMachine from, MechanicalMachine to, MechanicalPower power, SimulationContext context) {
        if (!(from instanceof dev.sdm.torque_foundry.core.machine.ShaftMachine)) {
            return power;
        }

        // Лимит материала в RPM -> milli-RPM (юниты скорости сети)
        final long safe = from.getMaterial().maxSafeSpeedRpm() * MechanicalPowerConstants.SCALE;
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
