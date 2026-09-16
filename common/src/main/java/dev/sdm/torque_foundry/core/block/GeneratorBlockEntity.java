package dev.sdm.torque_foundry.core.block;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class GeneratorBlockEntity extends MechanicalBlockEntity {

    public GeneratorBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(TFBlockEntities.GENERATOR.get(), blockPos, blockState);
    }

    @Override
    protected MechanicalMachine createMachine(MechanicalPower power) {
        return new GeneratorMachine(power.getSpeedRaw(), power.getTorqueRaw(),
                RotationDirection.from(power.getDirection()));
    }

    public GeneratorMachine getGenerator() {
        return (GeneratorMachine) this.machine;
    }
}
