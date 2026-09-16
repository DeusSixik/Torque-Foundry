package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import net.minecraft.core.Direction;

/**
 * Потребитель: принимает мощность со всех горизонтальных граней,
 * не имеет выходов. Требует скорость и момент из параметров блока.
 */
public class ConsumerMachine extends MechanicalMachine {

    public ConsumerMachine(long requiredSpeedRaw, long requiredTorqueRaw, RotationDirection direction) {
        super(MechanicalPower.fromRaw(requiredSpeedRaw, requiredTorqueRaw, direction),
                direction == null ? (byte) -1 : direction.index);
    }

    @Override
    protected void createDirections() {
        this.inputDirections = new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        };
        this.outputDirections = EMPTY_DIRECTIONS;
    }
}
