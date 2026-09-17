package dev.sdm.torque_foundry.core.block;

import com.mojang.serialization.MapCodec;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

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

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, TFBlockEntities.SHAFT.get(), (l, pos, s, be) -> be.clientTick());
        }
        return createTickerHelper(type, TFBlockEntities.SHAFT.get(), (l, pos, s, be) -> be.serverTick());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState blockState, Level level, BlockPos blockPos, Player player, BlockHitResult blockHitResult) {
        final MechanicalBlockEntity be = (MechanicalBlockEntity) level.getBlockEntity(blockPos);

        player.displayClientMessage(Component.literal("" + be.machine.getGroupIndex()), false);
        return super.useWithoutItem(blockState, level, blockPos, player, blockHitResult);
    }
}
