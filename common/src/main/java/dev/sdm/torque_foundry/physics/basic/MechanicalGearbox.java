package dev.sdm.torque_foundry.physics.basic;

public class MechanicalGearbox {

    private final int ratio;
    private final boolean stepUp;   // true = ↑speed ↓torque, false = ↓speed ↑torque
    private final boolean reverses;

    // "долг" по скорости — копится между тиками, чтобы деление было без потерь
    private long remainder = 0;

    public MechanicalGearbox(int ratio, boolean stepUp, boolean reverses) {
        this.ratio = ratio;
        this.stepUp = stepUp;
        this.reverses = reverses;
    }

    public MechanicalPower transform(MechanicalPower input) {
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

        return MechanicalPower.fromRaw(outSpeedRaw, outTorqueRaw, outDirection);
    }
}
