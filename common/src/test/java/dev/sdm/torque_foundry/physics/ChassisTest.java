package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.item.GearItem;
import dev.sdm.torque_foundry.core.machine.ChassisMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Приоритет 9: шасси + предмет-шестерня. Зацепление с приводной
 * шестернёй (8 зубьев), реверс, рейтинги момента/оборотов.
 */
public class ChassisTest {

    private static final Direction IN = Direction.NORTH;
    private static final Direction OUT = Direction.SOUTH;

    @Test
    void emptyChassis_isSealed() {
        final ChassisMachine chassis = new ChassisMachine();
        assertFalse(chassis.hasGear());
        assertTrue(chassis.getInputDirections().length == 0, "empty chassis = no input port");
        assertTrue(chassis.getOutputDirections().length == 0, "empty chassis = no output port");
    }

    @Test
    void gear16HalvesSpeedDoublesTorque() {
        final ChassisMachine chassis = new ChassisMachine();
        chassis.setGear(16, PhysicsMaterials.STEEL);

        assertEquals(1, chassis.getInputDirections().length, "with gear: one input");
        assertEquals(1, chassis.getOutputDirections().length, "with gear: one output");

        // Вход: 256 RPM / 16 Nm, выход по FACING (SOUTH)
        final RotationalPower in = RotationalPower.fromRaw(256_000, 16_000, RotationDirection.FORWARD.index);
        final RotationalPower out = chassis.transform(in, OUT);

        // ratio = 8/16: скорость вдвое ниже, момент вдвое выше
        assertEquals(128_000, out.getSpeedRaw(), "16 teeth halves speed");
        assertEquals(32_000, out.getTorqueRaw(), "16 teeth doubles torque");
        // Внешнее зацепление реверсирует
        assertEquals(RotationDirection.REVERSE.index, out.getDirection());
    }

    @Test
    void gear8IsOneToOne() {
        final ChassisMachine chassis = new ChassisMachine();
        chassis.setGear(8, PhysicsMaterials.STEEL);

        final RotationalPower in = RotationalPower.fromRaw(256_000, 64_000, RotationDirection.FORWARD.index);
        final RotationalPower out = chassis.transform(in, OUT);

        assertEquals(256_000, out.getSpeedRaw(), "8 teeth = 1:1 speed");
        assertEquals(64_000, out.getTorqueRaw(), "8 teeth = 1:1 torque");
    }

    @Test
    void transformOnlyToOutputSide() {
        final ChassisMachine chassis = new ChassisMachine();
        chassis.setGear(16, PhysicsMaterials.STEEL);

        final RotationalPower in = RotationalPower.fromRaw(256_000, 16_000, RotationDirection.FORWARD.index);
        final RotationalPower passthrough = chassis.transform(in, Direction.EAST);

        assertEquals(in.getSpeedRaw(), passthrough.getSpeedRaw(), "non-output side = passthrough");
        assertEquals(in.getTorqueRaw(), passthrough.getTorqueRaw(), "non-output side = passthrough");
    }

    @Test
    void ratings_scaleWithMaterialAndSize() {
        // Сталь держит больше дерева при тех же зубьях
        final double steel16 = GearItem.ratedTorqueNm(PhysicsMaterials.STEEL, 16);
        final double wood16 = GearItem.ratedTorqueNm(PhysicsMaterials.WOOD, 16);
        assertTrue(steel16 > wood16 * 5, "steel gear much stronger than wood");

        // Больше зубьев = прочнее зуб
        final double steel8 = GearItem.ratedTorqueNm(PhysicsMaterials.STEEL, 8);
        final double steel32 = GearItem.ratedTorqueNm(PhysicsMaterials.STEEL, 32);
        assertTrue(steel32 > steel8 * 2, "bigger gear holds more torque");
    }

    @Test
    void overRatedTorque_losesEfficiency() {
        final ChassisMachine chassis = new ChassisMachine();
        // Деревянная шестерня 8 зубьев: рейтинг момента ~ (тiny) Nm
        chassis.setGear(8, PhysicsMaterials.WOOD);
        final double rated = GearItem.ratedTorqueNm(PhysicsMaterials.WOOD, 8);
        final long ratedRaw = Math.round(rated * 1000);

        // Вход с моментом В 4 раза выше рейтинга
        final RotationalPower in = RotationalPower.fromRaw(64_000, ratedRaw * 4, RotationDirection.FORWARD.index);
        final RotationalPower out = chassis.transform(in, OUT);

        // Потери ~1% × превышение(3.0) = ~3% от момента (после ratio 1:1)
        assertTrue(out.getTorqueRaw() < in.getTorqueRaw(),
                "over-rated torque must lose power: " + out.getTorqueRaw()
                        + " vs " + in.getTorqueRaw());
        assertTrue(out.getTorqueRaw() > in.getTorqueRaw() * 0.9,
                "losses small at moderate overload: " + out.getTorqueRaw());
    }

    @Test
    void overRatedSpeed_losesEfficiency() {
        final ChassisMachine chassis = new ChassisMachine();
        // Стальная шестерня: рейтинг оборотов = 256+ RPM; на 400 RPM — потери
        chassis.setGear(8, PhysicsMaterials.IRON);
        final int rated = GearItem.ratedSpeedRpm(PhysicsMaterials.IRON);
        assertTrue(400 > rated, "test premise: 400 RPM over iron rating");

        final RotationalPower in = RotationalPower.fromRaw(400_000, 10_000, RotationDirection.FORWARD.index);
        final RotationalPower out = chassis.transform(in, OUT);

        assertTrue(out.getTorqueRaw() < in.getTorqueRaw(),
                "over-rated speed must lose power");
    }

    @Test
    void machineSpec_passthroughDefaults() {
        // Шасси без спец-полей: КПД 1.0 (потери уже в рейтингах)
        final ChassisMachine chassis = new ChassisMachine();
        assertEquals(1.0, chassis.getEfficiency(), 1e-9);
        assertEquals(0, chassis.getBreakawayTorqueRaw());
    }

    @Test
    void facingRotatesSides() {
        final ChassisMachine chassis = new ChassisMachine();
        chassis.setGear(16, PhysicsMaterials.STEEL);
        chassis.setFacing(Direction.EAST);

        // Выход теперь на EAST (FACING), вход на WEST: редукция работает
        final RotationalPower in = RotationalPower.fromRaw(256_000, 16_000, RotationDirection.FORWARD.index);
        final RotationalPower out = chassis.transform(in, Direction.EAST);
        assertEquals(128_000, out.getSpeedRaw(), "output follows FACING");
        // Не-выходная грань — passthrough без изменений
        final RotationalPower wrong = chassis.transform(in, Direction.SOUTH);
        assertEquals(256_000, wrong.getSpeedRaw(), "old side is passthrough now");
    }
}
