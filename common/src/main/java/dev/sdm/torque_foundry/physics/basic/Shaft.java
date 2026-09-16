package dev.sdm.torque_foundry.physics.basic;

public class Shaft {

    private MechanicalPower input =
            new MechanicalPower(0, 0);

    public void receive(MechanicalPower input) {
        this.input = input;
    }

    public MechanicalPower output() {
        return input;
    }
}
