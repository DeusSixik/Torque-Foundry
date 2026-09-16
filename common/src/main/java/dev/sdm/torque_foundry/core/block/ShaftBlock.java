package dev.sdm.torque_foundry.core.block;

import com.mojang.serialization.MapCodec;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Пассивный вал: передаёт вращение между машинами группы.
 */
public class ShaftBlock extends MechanicalBlock {

    public static final MapCodec<ShaftBlock> CODEC = simpleCodec(ShaftBlock::new);

    public ShaftBlock(Properties properties) {
        super(MechanicalPower.from(0, 0), properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new ShaftBlockEntity(blockPos, blockState);
    }
}
