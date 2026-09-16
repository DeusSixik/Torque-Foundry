package dev.sdm.torque_foundry.core.block;

import com.mojang.serialization.MapCodec;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Потребитель: требует от группы мощность, заданную параметрами блока.
 */
public class ConsumerBlock extends MechanicalBlock {

    public static final MechanicalPower DEFAULT_REQUIRED = MechanicalPower.from(64, 32);

    public static final MapCodec<ConsumerBlock> CODEC = simpleCodec(
            properties -> new ConsumerBlock(properties, DEFAULT_REQUIRED));

    public ConsumerBlock(Properties properties, MechanicalPower requiredPower) {
        super(requiredPower, properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new ConsumerBlockEntity(blockPos, blockState);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, TFBlockEntities.CONSUMER.get(), (l, pos, s, be) -> be.clientTick());
        }
        return createTickerHelper(type, TFBlockEntities.CONSUMER.get(), (l, pos, s, be) -> be.serverTick());
    }
}
