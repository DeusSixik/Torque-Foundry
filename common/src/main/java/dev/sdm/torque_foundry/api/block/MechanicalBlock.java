package dev.sdm.torque_foundry.api.block;

import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public abstract class MechanicalBlock extends BaseEntityBlock {

    protected final MechanicalPower power;

    public MechanicalBlock(MechanicalPower power, Properties properties) {
        super(properties);
        this.power = power;
    }

    @Override
    protected void onPlace(BlockState blockState, Level level, BlockPos blockPos, BlockState blockState2, boolean bl) {
        final BlockEntity entity = level.getBlockEntity(blockPos);
        if(!(entity instanceof MechanicalBlockEntity mechanicalBlock)) {
            throw new RuntimeException(blockPos.toString() + " is not a MechanicalBlockEntity");
        }

        MechanicalGroupManager.createOrAdd(level, mechanicalBlock);
    }

    @Override
    public void destroy(LevelAccessor levelAccessor, BlockPos blockPos, BlockState blockState) {
        final BlockEntity entity = levelAccessor.getBlockEntity(blockPos);
        if(!(entity instanceof MechanicalBlockEntity mechanicalBlock)) {
            throw new RuntimeException(blockPos.toString() + " is not a MechanicalBlockEntity");
        }

        MechanicalGroupManager.remove(mechanicalBlock);
    }
}
