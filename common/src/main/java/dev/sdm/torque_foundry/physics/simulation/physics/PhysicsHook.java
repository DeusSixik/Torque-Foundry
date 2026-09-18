package dev.sdm.torque_foundry.physics.simulation.physics;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.simulation.SimulationContext;

/**
 * Хук внедрения физических условий в симуляцию группы.
 *
 * Точки вызова (в порядке одного тика):
 * <ol>
 *   <li>{@link #onGroupTickStart} — начало тика группы;</li>
 *   <li>{@link #onSourceOutput} — источник выдаёт мощность (можно ослабить:
 *       износ двигателя, нехватка топлива);</li>
 *   <li>{@link #onTransmit} — мощность идёт по ребру от машины к машине
 *       (потери на длине вала, центробежные потери, повреждения);</li>
 *   <li>{@link #onReceive} — машина получает мощность (до её transform);</li>
 *   <li>{@link #onGroupTickEnd} — конец тика.</li>
 * </ol>
 * Все методы имеют default-реализацию — переопределяй только нужные.
 * Вызываются только в потоке физики.
 */
public interface PhysicsHook {

    default void onGroupTickStart(MechanicalGroup group, SimulationContext context) {
    }

    default MechanicalPower onSourceOutput(MechanicalMachine source, MechanicalPower output, SimulationContext context) {
        return output;
    }

    default MechanicalPower onTransmit(MechanicalMachine from, MechanicalMachine to, MechanicalPower power, SimulationContext context) {
        return power;
    }

    default MechanicalPower onReceive(MechanicalMachine machine, MechanicalPower power, SimulationContext context) {
        return power;
    }

    default void onGroupTickEnd(MechanicalGroup group, SimulationContext context) {
    }
}
