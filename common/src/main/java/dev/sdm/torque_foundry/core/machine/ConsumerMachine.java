package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.RotationDirection;
import net.minecraft.core.Direction;

/**
 * Потребитель: принимает мощность со всех горизонтальных граней,
 * не имеет выходов. Требует скорость и момент из параметров блока.
 */
public class ConsumerMachine extends MechanicalMachine {

    public ConsumerMachine(long requiredSpeedRaw, long requiredTorqueRaw, RotationDirection direction) {
        super(RotationalPower.fromRaw(requiredSpeedRaw, requiredTorqueRaw, direction),
                direction == null ? (byte) -1 : direction.index);
    }

    @Override
    protected void createDirections() {
        port(Direction.NORTH, PortRole.INPUT);
        port(Direction.SOUTH, PortRole.INPUT);
        port(Direction.WEST, PortRole.INPUT);
        port(Direction.EAST, PortRole.INPUT);
    }

    /**
     * Потребителю нужен момент страгивания = его рабочей потребности.
     */
    @Override
    public long getBreakawayTorqueRaw() {
        return getRequired().getTorqueRaw();
    }
}

