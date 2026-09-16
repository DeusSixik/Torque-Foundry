package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import net.minecraft.core.Direction;

/**
 * Пассивный передатчик: принимает и отдаёт вращение
 * со всех горизонтальных граней, ничего не потребляет.
 */
public class ShaftMachine extends MechanicalMachine {

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    public ShaftMachine() {
        super(MechanicalPower.fromRaw(0, 0), (byte) -1);
    }

    @Override
    protected void createDirections() {
        this.inputDirections = HORIZONTAL;
        this.outputDirections = HORIZONTAL;
    }
}
