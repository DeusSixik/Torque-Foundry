package dev.sdm.torque_foundry.core.block;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.RotationDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class GeneratorBlockEntity extends MechanicalBlockEntity {

    public GeneratorBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(TFBlockEntities.GENERATOR.get(), blockPos, blockState);
    }

    @Override
    protected MechanicalMachine createMachine(RotationalPower power) {
        return new GeneratorMachine(power.getSpeedRaw(), power.getTorqueRaw(),
                RotationDirection.from(power.getDirection()));
    }

    public GeneratorMachine getGenerator() {
        return (GeneratorMachine) this.machine;
    }
}
