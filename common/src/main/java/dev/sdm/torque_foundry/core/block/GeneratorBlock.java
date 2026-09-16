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

public class GeneratorBlock extends MechanicalBlock {

    public static final MechanicalPower DEFAULT_OUTPUT = MechanicalPower.from(256, 64);

    public static final MapCodec<GeneratorBlock> CODEC = simpleCodec(
            properties -> new GeneratorBlock(properties, DEFAULT_OUTPUT));

    public GeneratorBlock(Properties properties, MechanicalPower outputPower) {
        super(outputPower, properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new GeneratorBlockEntity(blockPos, blockState);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, TFBlockEntities.GENERATOR.get(), (l, pos, s, be) -> be.clientTick());
        }
        return createTickerHelper(type, TFBlockEntities.GENERATOR.get(), (l, pos, s, be) -> be.serverTick());
    }
}
