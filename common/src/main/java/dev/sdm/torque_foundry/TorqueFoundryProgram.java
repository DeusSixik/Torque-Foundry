package dev.sdm.torque_foundry;


import dev.sdm.torque_foundry.core.client.render.Model;
import dev.sdm.torque_foundry.core.client.render.structs.Quad;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.resources.ResourceLocation;

public class TorqueFoundryProgram {

    public static void main(String[] args) {

//        Model test = new Model(ResourceLocation.tryBuild("m", "a"),
//                new Quad[]{
//                    new Quad()
//                });

        MechanicalGroup group = new MechanicalGroup();
        group.setMachines(
            MechanicalMachine.from(
                    4, 16
            ),
            MechanicalMachine.from(
                    8, 4
            )
        );

        iteration(group);
    }

    public static void iteration(MechanicalGroup group) {
        var machines = group.getMachines();

        MechanicalPower power = MechanicalPower.from(256, 64);
        final long availableTorque = power.getTorqueRaw();
        final long currentSpeed = power.getSpeedRaw();

        int activeMachinesCount = 0;
        MechanicalMachine[] activeMachines = new MechanicalMachine[machines.length];

        long totalRequiredTorque = 0;
        for (MechanicalMachine machine : machines) {
            MechanicalPower required = machine.getRequired();

            if(currentSpeed >= required.getSpeedRaw()) {
                totalRequiredTorque += required.getTorqueRaw();
                activeMachines[activeMachinesCount++] = machine;
            }
        }

        if(availableTorque >= totalRequiredTorque) {
            for (int i = 0; i < activeMachinesCount; i++) {
                System.out.println(activeMachines[i]);
            }
        } else {
            System.out.println("Not enough troque");
        }
    }
}
