package dev.sdm.torque_foundry.physics.hook;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.simulation.SimulationContext;

import java.util.List;

/**
 * Хук внедрения физических условий в симуляцию группы.
 * <p>
 * Точки вызова (в порядке одного тика):
 * <ol>
 *   <li>{@link #onGroupTickStart} — начало тика группы;</li>
 *   <li>{@link #onSourceOutput} — источник выдаёт мощность (можно ослабить:
 *       износ двигателя, нехватка топлива);</li>
 *   <li>{@link #onTransmit} — мощность идёт по ребру от машины к машине
 *       (потери на длине вала, центробежные потери, повреждения);</li>
 *   <li>{@link #onReceive} — машина получает мощность (до её transform);</li>
 *   <li>{@link #onJam} — заклинивание цепочки (потребителю не хватило
 *       момента, вся ветка до источников блокирована);</li>
 *   <li>{@link #onGroupTickEnd} — конец тика.</li>
 * </ol>
 * Все методы имеют default-реализацию — переопределяй только нужные.
 * Вызываются только в потоке физики.
 */
public interface PhysicsHook {

    default void onGroupTickStart(MechanicalGroup group, SimulationContext context) {
    }

    default RotationalPower onSourceOutput(MechanicalMachine source, RotationalPower output, SimulationContext context) {
        return output;
    }

    default RotationalPower onTransmit(MechanicalMachine from, MechanicalMachine to, RotationalPower power, SimulationContext context) {
        return power;
    }

    default RotationalPower onReceive(MechanicalMachine machine, RotationalPower power, SimulationContext context) {
        return power;
    }

    /**
     * Заклинивание цепочки: потребителю не хватило момента, жёсткая сцепка
     * блокирует всю цепь до производителей (двигатели глохнут под нагрузкой).
     *
     * @param group     группа, в которой произошло заклинивание
     * @param jammed    все заклинившие машины (потребитель + цепь до источников)
     * @param producers производители группы (точка внедрения поведения при клине)
     * @param context   контекст тика
     */
    default void onJam(MechanicalGroup group, List<MechanicalMachine> jammed,
                       List<MechanicalMachine> producers, SimulationContext context) {
    }

    default void onGroupTickEnd(MechanicalGroup group, SimulationContext context) {
    }
}
