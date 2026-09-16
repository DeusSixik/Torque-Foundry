package dev.sdm.torque_foundry.api.block;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class MechanicalBlockEntity extends BlockEntity {

    public final MechanicalMachine machine;

    public MechanicalBlockEntity(BlockEntityType<?> blockEntityType, BlockPos blockPos, BlockState blockState) {
        super(blockEntityType, blockPos, blockState);

        if(!(blockState.getBlock() instanceof MechanicalBlock block)) {
            throw new IllegalArgumentException("MechanicalBlocks only support MechanicalBlocks");
        }

        final MechanicalPower power = block.power;
        this.machine = createMachine(power);
    }

    protected MechanicalMachine createMachine(MechanicalPower power) {
        return MechanicalMachine.fromRaw(power.getSpeedRaw(),
                power.getTorqueRaw(), RotationDirection.from(power.getDirection())
        );
    }
}
