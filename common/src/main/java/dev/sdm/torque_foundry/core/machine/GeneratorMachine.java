package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import net.minecraft.core.Direction;

/**
 * Источник механической энергии: не имеет входов,
 * выдаёт мощность на все горизонтальные грани.
 */
public class GeneratorMachine extends MechanicalMachine {

    private final MechanicalPower output;

    public GeneratorMachine(long outputSpeedRaw, long outputTorqueRaw, RotationDirection direction) {
        super(MechanicalPower.fromRaw(0, 0), (byte) -1);
        this.output = MechanicalPower.fromRaw(outputSpeedRaw, outputTorqueRaw, direction);
    }

    public MechanicalPower getOutput() {
        return output;
    }

    @Override
    protected void createDirections() {
        this.inputDirections = EMPTY_DIRECTIONS;
        this.outputDirections = new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        };
    }
}
