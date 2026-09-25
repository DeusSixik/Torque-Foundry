package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.FlywheelMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static dev.sdm.torque_foundry.physics.PhysicsMath.kineticEnergyNetworkJ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Маховик — чистая инерция сети (С1 п.5, раздел 7.11): энергия — производная
 * фактической скорости, отдельного буфера джоулей нет.
 *
 * <p>Регрессия: прежний маховик хранил джоули и подпирал момент без падения
 * оборотов — аккумулятор, создающий энергию из ничего.
 */
public class FlywheelTest {

    private static final Direction EAST = Direction.EAST;

    /** Генератор -> маховик -> потребитель 32 Nm @ 64 RPM вдоль X. */
    private static MechanicalGroup rig(long sourceTorqueNm, double flywheelInertia,
                                       long consumerTorqueNm) {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = TestRig.highGen(
                256_000, sourceTorqueNm * 1000);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        final FlywheelMachine flywheel = new FlywheelMachine(
                flywheelInertia, Direction.WEST, EAST);
        flywheel.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(flywheel);

        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, consumerTorqueNm * 1000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);
        return group;
    }

    @Test
    void flywheel_slowsSpinUp_butDoesNotCreateTorque() {
        // Тяжёлый маховик растягивает разгон: за те же 10 тиков сеть
        // набирает меньше оборотов, чем лёгкая
        final MechanicalGroup light = rig(64, 0.5, 20);
        final MechanicalGroup heavy = rig(64, 160, 20);

        for (int t = 0; t < 10; t++) {
            light.computeTick();
            heavy.computeTick();
        }

        assertTrue(heavy.getCurrentSpeedRpm() < light.getCurrentSpeedRpm(),
                "flywheel inertia must slow spin-up: heavy "
                        + heavy.getCurrentSpeedRpm() + " vs light "
                        + light.getCurrentSpeedRpm());
    }

    @Test
    void flywheel_noFreeTorque_underLoad() {
        // Потребитель требует больше, чем даёт источник: прежний буфер
        // «покрывал дефицит из запаса» — маховик-инерция так не умеет,
        // сеть честно проваливается в перегруз
        final MechanicalGroup group = rig(32, 160, 64);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        final MechanicalMachine consumer = group.getMachine(2);
        assertTrue(consumer.getReceived().getTorqueRaw() <= 32_000 + 500,
                "flywheel must not boost torque above source capability: "
                        + consumer.getReceived().getTorqueRaw());
        assertNotEqualsWorking(consumer);
    }

    /** Перегруженный потребитель не может значиться рабочим. */
    private static void assertNotEqualsWorking(MechanicalMachine consumer) {
        org.junit.jupiter.api.Assertions.assertNotEquals(
                WorkState.WORKING, consumer.getWorkState(),
                "source 32 Nm cannot satisfy a 64 Nm consumer: no buffer");
    }

    @Test
    void flywheel_energy_isDerivedFromActualSpeed() {
        final MechanicalGroup group = rig(64, 160, 20);
        final FlywheelMachine flywheel = (FlywheelMachine) group.getMachine(1);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        // На плато энергия = ½Jω² фактической скорости узла — и ни джоуля больше
        final double expected = kineticEnergyNetworkJ(
                160.0, flywheel.getReceived().getSpeedRaw());
        assertEquals(expected, flywheel.storedEnergyJ(), 1.0,
                "stored energy is derived from actual speed");
        assertTrue(flywheel.storedEnergyJ() > 500,
                "flywheel at 256 RPM with J=160 stores real energy: "
                        + flywheel.storedEnergyJ());
    }

    @Test
    void flywheel_coastsLonger_afterSourceRemoved() {
        // Инерция тянет выбег: тяжёлая сеть замедляется медленнее лёгкой.
        // Раскрутка фиксированно долгая (тяжёлой нужно ~2500 тиков до
        // плато — settle обрывается раньше и выбег начинался бы с середины
        // разгона), выбег — 200 тиков
        final MechanicalGroup light = rig(64, 0.5, 20);
        final MechanicalGroup heavy = rig(64, 160, 20);
        final MechanicalMachine lightGen = light.getMachine(0);
        final MechanicalMachine heavyGen = heavy.getMachine(0);

        for (int t = 0; t < 5000; t++) {
            light.computeTick();
            heavy.computeTick();
        }
        assertEquals(256_000, light.currentSpeedForTest(), 2000, "light at plateau");
        assertEquals(256_000, heavy.currentSpeedForTest(), 2000, "heavy at plateau");

        light.removeElement(lightGen);
        heavy.removeElement(heavyGen);

        for (int t = 0; t < 200; t++) {
            light.computeTick();
            heavy.computeTick();
        }

        assertTrue(heavy.getCurrentSpeedRpm() > light.getCurrentSpeedRpm() * 2,
                "bigger flywheel = longer coast after 200 ticks: heavy "
                        + heavy.getCurrentSpeedRpm() + " vs light "
                        + light.getCurrentSpeedRpm());
    }
}
