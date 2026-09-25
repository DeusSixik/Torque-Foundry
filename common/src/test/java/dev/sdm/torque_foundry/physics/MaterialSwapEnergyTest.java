package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static dev.sdm.torque_foundry.physics.PhysicsMath.kineticEnergyNetworkJ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Смена материала на ходу с сохранением энергии (С1 п.8, раздел 5.1):
 * ω_новая = ω_старая · √(J_старая / J_новая) по инерции ВСЕЙ сети.
 *
 * <p>Регрессия: голый setMaterial на крутящейся сети умножал/уничтожал
 * кинетическую энергию фактом смены (деталь x10 тяжелее — энергия x10).
 */
public class MaterialSwapEnergyTest {

    /** Генератор + 3 деревянных вала, высокий режим, плато 256 RPM. */
    private static MechanicalGroup woodenLine() {
        final MechanicalGroup group = new MechanicalGroup();
        final GeneratorMachine gen = new GeneratorMachine(
                256_000, 96_000, RotationDirection.FORWARD);
        gen.setHighMode(true);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        for (int i = 1; i <= 3; i++) {
            final MechanicalMachine shaft = new ShaftMachine();
            shaft.setBlockPos(new BlockPos(i, 0, 0));
            shaft.setMaterial(PhysicsMaterials.WOOD);
            group.addElement(shaft);
        }
        TestRig.settle(group, TestRig.SETTLE_TICKS);
        return group;
    }

    @Test
    void swapOnSpinningNetwork_conservesEnergy() {
        final MechanicalGroup group = woodenLine();
        final MechanicalMachine middle = group.getMachine(2);

        // Ставим вал на сталь (тяжелее дерева в ~11 раз) на ходу
        final double jBefore = group.reducedInertiaForTest();
        final double eBefore = kineticEnergyNetworkJ(jBefore, group.currentSpeedForTest());

        group.swapMaterialPreservingEnergy(middle, PhysicsMaterials.STEEL);

        final double jAfter = group.reducedInertiaForTest();
        final double eAfter = kineticEnergyNetworkJ(jAfter, group.currentSpeedForTest());

        assertSame(PhysicsMaterials.STEEL, middle.getMaterial(), "material applied");
        assertTrue(jAfter > jBefore, "steel adds inertia to the network");
        assertEquals(eBefore, eAfter, eBefore * 0.01,
                "kinetic energy conserved across the swap (1% tolerance)");
        assertTrue(group.currentSpeedForTest() < 256_000,
                "heavier network must slow down");
        // Валы по-прежнему крутятся вместе с сетью
        assertEquals(WorkState.WORKING, middle.getWorkState());
    }

    @Test
    void swapLighter_speedsUp_backToOriginal() {
        final MechanicalGroup group = woodenLine();
        final long baseSpeed = group.currentSpeedForTest();

        group.swapMaterialPreservingEnergy(group.getMachine(2), PhysicsMaterials.STEEL);
        final long slowed = group.currentSpeedForTest();
        assertTrue(slowed < baseSpeed, "steel must slow the network");

        group.swapMaterialPreservingEnergy(group.getMachine(2), PhysicsMaterials.WOOD);
        assertEquals(baseSpeed, group.currentSpeedForTest(), 3000,
                "swapping back returns the network to its speed");
    }

    @Test
    void swapWhileStalled_freeSwap() {
        // Сеть в клине считается стоящей: энергии нулевая, меняем свободно
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        final MechanicalMachine shaft = new ShaftMachine();
        shaft.setBlockPos(new BlockPos(1, 0, 0));
        shaft.setMaterial(PhysicsMaterials.WOOD);
        group.addElement(shaft);
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 128_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);

        TestRig.settle(group, TestRig.SETTLE_TICKS);
        assertEquals(0.0, group.getCurrentSpeedRpm(), 0.001, "network stalled");

        group.swapMaterialPreservingEnergy(shaft, PhysicsMaterials.STEEL);

        assertSame(PhysicsMaterials.STEEL, shaft.getMaterial());
        assertEquals(0, group.currentSpeedForTest(),
                "stalled network has no energy to conserve: speed stays zero");
    }

    @Test
    void swapDeadBranchMachine_doesNotTouchNetworkSpeed() {
        // Мёртвая ветвь (за потребителем) сеть не весит: смена её материала
        // не должна дёргать обороты сети
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 96_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 32_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(consumer);
        final MechanicalMachine dead = new ShaftMachine();
        dead.setBlockPos(new BlockPos(2, 0, 0));
        dead.setMaterial(PhysicsMaterials.WOOD);
        group.addElement(dead);

        TestRig.settle(group, TestRig.SETTLE_TICKS);
        final long before = group.currentSpeedForTest();

        group.swapMaterialPreservingEnergy(dead, PhysicsMaterials.STEEL);

        assertSame(PhysicsMaterials.STEEL, dead.getMaterial());
        assertEquals(before, group.currentSpeedForTest(),
                "dead branch machine weighs nothing: network speed untouched");
    }
}
