package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import dev.sdm.torque_foundry.physics.RotationDirection;
import net.minecraft.core.Direction;

/**
 * Коническая передача: поворот оси на 90 градусов (вход горизонталь,
 * выход вертикаль или наоборот) с передаточным числом по зубцам.
 */
public class BevelGearMachine extends MechanicalMachine {

    private final int teethIn;
    private final int teethOut;
    private final boolean reverses;
    private final Direction outputSide;

    public BevelGearMachine(int teethIn, int teethOut, boolean reverses,
                            Direction inputSide, Direction outputSide) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.teethIn = Math.max(1, teethIn);
        this.teethOut = Math.max(1, teethOut);
        this.reverses = reverses;
        this.outputSide = outputSide;
        port(inputSide, PortRole.INPUT);
        port(outputSide, PortRole.OUTPUT);
    }

    @Override
    protected void createDirections() {
        // Порты задаёт конструктор
    }

    @Override
    public RotationalPower transform(RotationalPower input, Direction outputSide) {
        if (outputSide != this.outputSide) {
            return input;
        }

        final long outSpeedRaw = input.getSpeedRaw() * teethIn / teethOut;
        final long outTorqueRaw = input.getTorqueRaw() * teethOut / teethIn;
        final byte dir = reverses
                ? RotationDirection.opposite(input.getDirection())
                : input.getDirection();

        return RotationalPower.fromRaw(outSpeedRaw, outTorqueRaw, dir);
    }
}
