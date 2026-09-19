package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Износ валов на оборотах выше безопасного лимита материала.
 * Железо (256 RPM) на 256 RPM не изнашивается.
 * Дерево (120 RPM) на 256 RPM изнашивается быстро.
 */
public class MaterialWearTest {

    private static final long GEN_SPEED = 256_000;  // 256 RPM
    private static final long GEN_TORQUE = 64_000;  // 64 Nm

    @Test
    void ironShafts_noWearAtSafeSpeed() {
        // Железо: лимит 256 RPM = обороты генератора -> износа нет
        final MechanicalGroup group = chainOf(
                PhysicsMaterials.IRON, PhysicsMaterials.IRON, PhysicsMaterials.IRON);

        for (int t = 0; t < 200; t++) {
            group.computeTick();
        }

        // Последний вал получает полный момент генератора
        assertEquals(GEN_TORQUE, lastShaftTorque(group),
                "iron shafts at safe speed must not lose torque");
    }

    @Test
    void woodShafts_wearAtHighSpeed() {
        // Дерево: лимит 120 RPM < обороты генератора 256 RPM -> износ
        final MechanicalGroup group = chainOf(
                PhysicsMaterials.WOOD, PhysicsMaterials.WOOD, PhysicsMaterials.WOOD);

        for (int t = 0; t < 200; t++) {
            group.computeTick();
        }

        final long last = lastShaftTorque(group);
        assertTrue(last < GEN_TORQUE,
                "wood shafts must lose torque at 256 RPM");
        assertTrue(last > 0, "but not lose everything");
    }

    @Test
    void steelChain_noWearEvenAbove() {
        // Сталь: лимит 400 RPM, генератор 256 — запас огромный
        final MechanicalGroup group = chainOf(
                PhysicsMaterials.STEEL, PhysicsMaterials.STEEL, PhysicsMaterials.STEEL);

        for (int t = 0; t < 200; t++) {
            group.computeTick();
        }

        assertEquals(GEN_TORQUE, lastShaftTorque(group),
                "steel shafts must not lose torque at 256 RPM");
    }

    // --- хелперы ---

    private MechanicalGroup chainOf(dev.sdm.torque_foundry.physics.material.PhysicsMaterial m0,
                                    dev.sdm.torque_foundry.physics.material.PhysicsMaterial m1,
                                    dev.sdm.torque_foundry.physics.material.PhysicsMaterial m2) {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        gen.setMaterial(m0);
        group.addElement(gen);

        for (int i = 1; i <= 3; i++) {
            final MechanicalMachine shaft = new ShaftMachine();
            shaft.setBlockPos(new BlockPos(i, 0, 0));
            shaft.setMaterial(i == 1 ? m0 : (i == 2 ? m1 : m2));
            group.addElement(shaft);
        }
        return group;
    }

    private long lastShaftTorque(MechanicalGroup group) {
        return group.getMachine(3).getReceived().getTorqueRaw();
    }
}
