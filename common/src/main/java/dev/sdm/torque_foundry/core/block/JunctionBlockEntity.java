package dev.sdm.torque_foundry.core.block;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.machine.JunctionMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class JunctionBlockEntity extends MechanicalBlockEntity {

    public JunctionBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(TFBlockEntities.JUNCTION.get(), blockPos, blockState);
    }

    @Override
    protected MechanicalMachine createMachine(RotationalPower power) {
        return new JunctionMachine();
    }
}
