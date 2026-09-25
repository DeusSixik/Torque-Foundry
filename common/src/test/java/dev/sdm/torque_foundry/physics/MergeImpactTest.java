package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Слияние сетей как неупругий удар (С1 п.4, раздел 11.1): жёсткое соединение
 * сохраняет момент импульса, разница кинетической энергии греет узел стыка.
 *
 * <p>Регрессия: прежний merge оставлял скорость группы-приёмника — установка
 * блока между двумя крутящимися линиями создавала или уничтожала энергию
 * фактом установки. Инерции сетей приведены (С1 п.3), поэтому формула удара
 * считается по честным J.
 */
public class MergeImpactTest {

    /**
     * Сеть: генератор + валы вдоль +X от origin, все на базовом уровне.
     * Приведённая инерция после тика = 2·(1 + число валов).
     */
    private static MechanicalGroup spinUp(long speedRpm, long torqueNm, RotationDirection dir,
                                          int shafts, BlockPos origin) {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = TestRig.highGen(speedRpm * 1000, torqueNm * 1000, dir);
        gen.setBlockPos(origin);
        group.addElement(gen);
        for (int i = 1; i <= shafts; i++) {
            final MechanicalMachine shaft = new ShaftMachine();
            shaft.setBlockPos(new BlockPos(origin.getX() + i, origin.getY(), origin.getZ()));
            group.addElement(shaft);
        }
        TestRig.settle(group, TestRig.SETTLE_TICKS);
        return group;
    }

    @Test
    void merge_spinningNetworks_momentumPreserved_heatAtJoint() {
        final MechanicalGroup a = spinUp(256, 64, RotationDirection.FORWARD, 2,
                new BlockPos(0, 0, 0));
        final MechanicalGroup b = spinUp(64, 64, RotationDirection.FORWARD, 1,
                new BlockPos(0, 10, 0));
        assertEquals(256_000, a.currentSpeedForTest(), 1000, "A at plateau");
        assertEquals(64_000, b.currentSpeedForTest(), 1000, "B at plateau");

        final MechanicalMachine joint = new ShaftMachine();
        a.addElement(joint);

        // Ожидание — по формуле из ФАКТИЧЕСКИХ приведённых инерций сетей
        // (материал машин — чугун, relativeDensity ≈ 1.834, не 2.0)
        final double jA = a.reducedInertiaForTest() + joint.getInertia();
        final double jB = Math.max(1.0, b.reducedInertiaForTest());
        final double expected = (jA * 256_000 + jB * 64_000) / (jA + jB);

        a.merge(b, joint, a.getMachine(0), b.getMachine(0));

        assertEquals(expected, a.currentSpeedForTest(), 1.0,
                "speed = momentum / total inertia, not receiver's speed");
        assertEquals(jA + jB, a.reducedInertiaForTest(), 0.01,
                "merge stores the combined reduced inertia");
        assertTrue(a.currentSpeedForTest() < 250_000,
                "old merge kept receiver speed (256k)");
        assertTrue(joint.getSimulationState().getThermalEnergyJ() > 100,
                "impact energy must heat the joint: "
                        + joint.getSimulationState().getThermalEnergyJ());
        assertTrue(b.isEmpty(), "merged group is emptied");
    }

    @Test
    void merge_opposingRotation_grindsDown() {
        final MechanicalGroup a = spinUp(256, 64, RotationDirection.FORWARD, 2,
                new BlockPos(0, 0, 0));
        final MechanicalGroup b = spinUp(64, 64, RotationDirection.REVERSE, 1,
                new BlockPos(0, 10, 0));

        final MechanicalMachine joint = new ShaftMachine();
        a.addElement(joint);

        // Встречное вращение: импульсы вычитаются (знак d = −1)
        final double jA = a.reducedInertiaForTest() + joint.getInertia();
        final double jB = Math.max(1.0, b.reducedInertiaForTest());
        final double expected = (jA * 256_000 - jB * 64_000) / (jA + jB);

        a.merge(b, joint, a.getMachine(0), b.getMachine(0));

        assertEquals(expected, a.currentSpeedForTest(), 1.0,
                "opposing member subtracts from the momentum sum");
        assertTrue(joint.getSimulationState().getThermalEnergyJ() > 1000,
                "counter-rotating coupling burns much more energy in the joint: "
                        + joint.getSimulationState().getThermalEnergyJ());
    }

    @Test
    void merge_stationaryNetwork_energyConserved() {
        final MechanicalGroup a = spinUp(256, 64, RotationDirection.FORWARD, 2,
                new BlockPos(0, 0, 0));
        // Стоячая сеть: один вал, ни тика — скорость 0, инерция неизвестна (1)
        final MechanicalGroup b = new MechanicalGroup();
        final MechanicalMachine stander = new ShaftMachine();
        stander.setBlockPos(new BlockPos(0, 20, 0));
        b.addElement(stander);

        final MechanicalMachine joint = new ShaftMachine();
        a.addElement(joint);

        // Стоячая сеть: J_B неизвестен (ни тика) — принимается 1
        final double jA = a.reducedInertiaForTest() + joint.getInertia();
        final double expected = jA * 256_000 / (jA + 1.0);

        // ω раскручивает стоячую линию СВОЕЙ энергией
        a.merge(b, joint, a.getMachine(0), stander);

        assertEquals(expected, a.currentSpeedForTest(), 1.0,
                "joining a standing network slows the spinning one");
        assertTrue(a.currentSpeedForTest() < 250_000, "old merge kept receiver speed");
        assertTrue(joint.getSimulationState().getThermalEnergyJ() > 50,
                "the lost energy heats the joint: "
                        + joint.getSimulationState().getThermalEnergyJ());
    }

    @Test
    void merge_intoEmpty_otherGroupReturned() {
        // Слияние с пустой группой — no-op без удара (скорость не трогается)
        final MechanicalGroup a = spinUp(256, 64, RotationDirection.FORWARD, 2,
                new BlockPos(0, 0, 0));
        final MechanicalMachine joint = new ShaftMachine();
        a.addElement(joint);
        final MechanicalGroup empty = new MechanicalGroup();

        a.merge(empty, joint, null, null);

        assertEquals(256_000, a.currentSpeedForTest(), 1,
                "merging nothing must not touch the speed");
        assertEquals(0.0, joint.getSimulationState().getThermalEnergyJ(), 1e-9,
                "no impact — no heat");
    }
}
