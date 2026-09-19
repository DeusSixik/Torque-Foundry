package dev.sdm.torque_foundry.physics.machine;

import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.RotationalPower;

public class GearRatio {

    private final int ratio;
    private final boolean stepUp;   // true = ↑speed ↓torque, false = ↓speed ↑torque
    private final boolean reverses;

    // "долг" по скорости — копится между тиками, чтобы деление было без потерь
    private long remainder = 0;

    public GearRatio(int ratio, boolean stepUp, boolean reverses) {
        this.ratio = ratio;
        this.stepUp = stepUp;
        this.reverses = reverses;
    }

    public RotationalPower transform(RotationalPower input) {
        long outSpeedRaw;
        long outTorqueRaw;

        if (stepUp) {
            long torqueTotal = input.getTorqueRaw() + remainder;
            outTorqueRaw = torqueTotal / ratio;
            this.remainder = torqueTotal % ratio;
            outSpeedRaw = input.getSpeedRaw() * ratio;
        } else {
            long speedTotal = input.getSpeedRaw() + remainder;
            outSpeedRaw = speedTotal / ratio;
            this.remainder = speedTotal % ratio;
            outTorqueRaw = input.getTorqueRaw() * ratio;
        }

        byte outDirection = reverses
                ? RotationDirection.opposite(input.getDirection())
                : input.getDirection();

        return RotationalPower.fromRaw(outSpeedRaw, outTorqueRaw, outDirection);
    }
}
