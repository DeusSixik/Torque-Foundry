package dev.sdm.torque_foundry.api.block;

import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public abstract class MechanicalBlock extends BaseEntityBlock {

    protected final MechanicalPower power;

    public MechanicalBlock(MechanicalPower power, Properties properties) {
        super(properties);
        this.power = power;
    }

    public MechanicalPower getPower() {
        return power;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos blockPos, BlockState blockState, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, blockPos, blockState, placer, stack);

        final BlockEntity entity = level.getBlockEntity(blockPos);
        if (entity instanceof MechanicalBlockEntity mechanicalBlock) {
            MechanicalGroupManager.createOrAdd(level, mechanicalBlock);
        }
    }

    @Override
    protected void onRemove(BlockState blockState, Level level, BlockPos blockPos, BlockState newState, boolean movedByPiston) {
        if (!blockState.is(newState.getBlock())) {
            final BlockEntity entity = level.getBlockEntity(blockPos);
            if (entity instanceof MechanicalBlockEntity mechanicalBlock) {
                MechanicalGroupManager.remove(mechanicalBlock);
            }
        }
        super.onRemove(blockState, level, blockPos, newState, movedByPiston);
    }
}
