package dev.sdm.torque_foundry;


import dev.sdm.torque_foundry.physics.basic.MechanicalGearbox;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;

public class TorqueFoundryProgram {

    public static void main(String[] args) {
        MechanicalPower power = MechanicalPower.from(256, 32);

        System.out.println(power);
        MechanicalGearbox gearbox = new MechanicalGearbox(2, true, false);
        power = gearbox.transform(power);
        System.out.println(power);

        gearbox = new MechanicalGearbox(2, false, false);
        power = gearbox.transform(power);
        System.out.println(power);
    }
}
