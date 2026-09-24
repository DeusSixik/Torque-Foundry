package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Клин и источники (С1 п.6, раздел 11.3): в заклинившей сети источник
 * давит упором на паспортных оборотах — момент уходит в тепло узла,
 * а не в никуда: Q += k · T_упора · ω_паспорта · Δt.
 *
 * <p>k — из паспорта источника: мотор/турбина греются (k = 1, по умолчанию),
 * водяное колесо — нет (k = 0: вода переливается через неподвижные ковши).
 * Без подвода этого тепла перегрев генератора от клина не посчитать:
 * мощность τ·ω на нулевой скорости — ноль.
 */
public class StallHeatTest {

    /** Источник, отсоединяющийся от потока в упор (водяное колесо). */
    static class ColdStallGenerator extends GeneratorMachine {
        ColdStallGenerator(BlockPos pos) {
            super(256_000, 64_000, RotationDirection.FORWARD);
            setBlockPos(pos);
        }

        @Override
        public boolean heatsUpWhenStalled() {
            return false;
        }
    }

    /** Сеть в жёстком клине: генератор 64 Nm против потребителя 128 Nm. */
    private static MechanicalGroup stalledRig(MechanicalMachine gen) {
        final MechanicalGroup group = new MechanicalGroup();
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        final MechanicalMachine shaft = new ShaftMachine();
        shaft.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(shaft);
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 128_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);
        return group;
    }

    @Test
    void generator_stalled_heatsUp() {
        final MechanicalGroup group = stalledRig(new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD));
        final MechanicalMachine gen = group.getMachine(0);

        // Клин: потребитель требует вдвое больше тяги — сеть встаёт сразу
        for (int t = 0; t < 200; t++) {
            group.computeTick();
        }

        assertEquals(WorkState.JAMMED, group.getMachine(2).getWorkState(), "stalled");
        assertEquals(0.0, group.getCurrentSpeedRpm(), 0.001, "network stalled");

        // Q за тик = 64 Nm · 26.8 рад/с / 20 = ~86 Дж; за 200 тиков — ~17 кДж
        final double heat = gen.getSimulationState().getThermalEnergyJ();
        assertTrue(heat > 10_000,
                "stalled motor must heat by stall torque at passport speed: " + heat);
    }

    @Test
    void waterWheel_stalled_staysCold() {
        final MechanicalGroup group = stalledRig(new ColdStallGenerator(
                new BlockPos(0, 0, 0)));
        final MechanicalMachine gen = group.getMachine(0);

        for (int t = 0; t < 200; t++) {
            group.computeTick();
        }

        assertEquals(WorkState.JAMMED, group.getMachine(2).getWorkState(), "stalled");
        // Не ноль в строгом смысле: первые ~12 тиков сеть взбирается до порога
        // потребителя, и стартовое трение греет генератор на доли джоуля.
        // Клинового тепла (86 Дж/тик) нет — иначе было бы ~17 кДж
        assertTrue(gen.getSimulationState().getThermalEnergyJ() < 1.0,
                "wheel disconnected from its flow consumes nothing at stall: "
                        + gen.getSimulationState().getThermalEnergyJ());
    }

    @Test
    void spinningGenerator_heatsOnlyByConversion_notByStall() {
        // Рабочий генератор греется ТОЛЬКО потерями преобразования (7.18):
        // подвод тепла клина не должен работать на ходу
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 8_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(new ShaftMachine() {{
            setBlockPos(new BlockPos(1, 0, 0));
        }});
        group.addElement(consumer);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        assertEquals(WorkState.WORKING, consumer.getWorkState());
        // На плато сеть стоит на цели: потребители крутятся, клина нет —
        // тепло генератора умеренное (потери 0.9 КПД от фактической тяги),
        // а не клиновые 86 Дж/тик
        assertTrue(group.getCurrentSpeedRpm() > 200,
                "healthy network spins");
        final double heat = gen.getSimulationState().getThermalEnergyJ();
        assertTrue(heat < 10_000,
                "spinning source heats by conversion only, not stall: " + heat);
    }
}
