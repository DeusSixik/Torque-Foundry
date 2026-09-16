package dev.sdm.torque_foundry.core.block;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class ShaftBlockEntity extends MechanicalBlockEntity {

    public ShaftBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(TFBlockEntities.SHAFT.get(), blockPos, blockState);
    }

    @Override
    protected MechanicalMachine createMachine(MechanicalPower power) {
        return new ShaftMachine();
    }
}
