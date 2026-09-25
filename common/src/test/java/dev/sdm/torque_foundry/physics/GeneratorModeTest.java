package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Два режима генератора (С1 п.9, раздел 7.18): умолчание — низкий (ниже
 * предела дерева: деревянная стартовая линия живёт без износа), паспорт —
 * только явным переключением. Момент в обоих режимах паспортный — «не
 * прячется»: низкий режим даёт меньшую мощность, а не ослабленный момент.
 */
public class GeneratorModeTest {

    /** Генератор + деревянный вал + потребитель 32 Nm @ 64 RPM. */
    private static MechanicalGroup woodLine() {
        final MechanicalGroup group = new MechanicalGroup();
        final GeneratorMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        final MechanicalMachine shaft = new ShaftMachine();
        shaft.setBlockPos(new BlockPos(1, 0, 0));
        shaft.setMaterial(PhysicsMaterials.WOOD);
        group.addElement(shaft);
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 32_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);
        return group;
    }

    /** Тепловой баланс ротора до начала измерений: нулевое тепло. */
    private static void assertGeneratorHeatStartsCold(GeneratorMachine gen) {
        assertEquals(0.0, gen.getSimulationState().getThermalEnergyJ(), 1e-9,
                "fresh generator must hold no rotor heat");
    }

    @Test
    void defaultMode_isLow_belowWoodLimit() {
        final GeneratorMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        assertGeneratorHeatStartsCold(gen);
        assertFalse(gen.isHighMode(), "default is low mode");
        assertEquals(96_000, gen.getOutput().getSpeedRaw(),
                "low mode: below the wood safety limit (107 RPM)");
        assertEquals(64_000, gen.getOutput().getTorqueRaw(),
                "torque is not hidden in low mode");
    }

    @Test
    void lowMode_woodLine_spinsBelowWearLimit() {
        // Деревянный вал на низком режиме: 96 < 107 — без износа
        final MechanicalGroup group = woodLine();
        TestRig.settle(group, TestRig.SETTLE_TICKS);

        assertEquals(96.0, group.getCurrentSpeedRpm(), 1.0,
                "network plateaus at the low-mode speed");
        assertEquals(WorkState.WORKING, group.getMachine(2).getWorkState(),
                "base consumer is fed in low mode");
        assertEquals(32_000, group.getMachine(2).getReceived().getTorqueRaw(), 100,
                "consumer receives its demand: wood intact");
    }

    @Test
    void highMode_explicitSwitch_reachesPassport() {
        final MechanicalGroup group = woodLine();
        final GeneratorMachine gen = (GeneratorMachine) group.getMachine(0);
        TestRig.settle(group, TestRig.SETTLE_TICKS);
        assertEquals(96.0, group.getCurrentSpeedRpm(), 1.0, "starts low");

        // Явное переключение: орган управления, а не предмет
        gen.setHighMode(true);
        assertTrue(gen.isHighMode(), "switch accepted before spin-up");
        TestRig.settle(group, TestRig.SETTLE_TICKS);

        assertEquals(256.0, group.getCurrentSpeedRpm(), 1.0,
                "high mode = passport speed");
        // Износ дерева на 256 RPM проверен в MaterialWearTest (хук напрямую) —
        // end-to-end сравнение здесь маргинально: срез 1.4% виден только при
        // спросе в пределах износа от потолка, что всегда даёт пилу
    }

    @Test
    void lowMode_neverExceedsPassport_forSmallGenerators() {
        // Слабый источник (48 RPM): низкий режим не поднимает обороты
        final GeneratorMachine gen = new GeneratorMachine(
                48_000, 64_000, RotationDirection.FORWARD);
        assertEquals(48_000, gen.getOutput().getSpeedRaw(),
                "low mode = min(passport, 96 RPM)");
        gen.setHighMode(true);
        assertEquals(48_000, gen.getOutput().getSpeedRaw(),
                "high mode = passport for a small generator");
    }
}
