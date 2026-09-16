package dev.sdm.torque_foundry.physics.basic;

public class BasicMachine {

    private final int requiredSpeed;
    private final int requiredTorque;
    private final long requiredPower;

    public BasicMachine(
            int requiredSpeed,
            int requiredTorque,
            long requiredPower
    ) {
        this.requiredSpeed = requiredSpeed;
        this.requiredTorque = requiredTorque;
        this.requiredPower = requiredPower;
    }

    public boolean canWork(MechanicalPower input) {
        return input.speed() >= requiredSpeed
                && input.torque() >= requiredTorque
                && input.power() >= requiredPower;
    }

    public void tick(MechanicalPower input) {
        if (canWork(input)) {
            System.out.println("Work");
        }
        else {
            System.out.println("not work");
        }
    }
}
