package dev.sdm.torque_foundry.physics.simulation;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;

/**
 * Пример физического условия: износ валов на высоких оборотах.
 * Вал, крутящийся выше безопасного лимита, "люфтит" и теряет КПД:
 * каждый тик передачи через вал отнимает фиксированную долю МОМЕНТА
 * (растущую с превышением лимита). Обороты сети при этом не проседают —
 * в жёсткой модели они едины, потери выражаются в моменте (КПД).
 *
 * Mutable-архитектура: хук МОДИФИЦИРУЕТ переданный MechanicalPower
 * и возвращает его же — новых объектов на тик не создаётся.
 */
public final class ShaftWearHook implements PhysicsHook {

    /** Безопасные обороты вала (milli-RPM). */
    private static final long SAFE_SPEED_RAW = 8_000;

    /** Доля потери момента за ребро за тик на единицу превышения. */
    private static final double WEAR_FACTOR = 0.01;

    @Override
    public MechanicalPower onTransmit(MechanicalMachine from, MechanicalMachine to, MechanicalPower power, SimulationContext context) {
        if (!(from instanceof dev.sdm.torque_foundry.core.machine.ShaftMachine)) {
            return power;
        }

        final long speed = power.getSpeedRaw();
        if (speed <= SAFE_SPEED_RAW) {
            return power;
        }

        final long excess = speed - SAFE_SPEED_RAW;
        final long loss = Math.round(power.getTorqueRaw() * (excess / (double) SAFE_SPEED_RAW) * WEAR_FACTOR);
        if (loss <= 0) {
            return power;
        }

        // Вал люфтит: теряется момент (КПД), обороты сети сохраняются.
        // Мутируем объект мощности вместо создания нового.
        power.setTorqueRaw(Math.max(0, power.getTorqueRaw() - loss));
        return power;
    }
}
