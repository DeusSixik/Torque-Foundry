package dev.sdm.torque_foundry.core.block;

import com.mojang.serialization.MapCodec;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.physics.RotationalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Узел разветвления: принимает крутящий момент с любой грани и передаёт
 * во все остальные (машина {@link dev.sdm.torque_foundry.core.machine.JunctionMachine}).
 * Направление вращения наследуется от источника, мощность не создаётся.
 */
public class JunctionBlock extends MechanicalBlock {

    public static final MapCodec<JunctionBlock> CODEC = simpleCodec(JunctionBlock::new);

    public JunctionBlock(Properties properties) {
        // required 0/0: узел ничего не потребляет, это пассивный проводник.
        super(RotationalPower.fromRaw(0, 0), properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new JunctionBlockEntity(blockPos, blockState);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, TFBlockEntities.JUNCTION.get(), (l, pos, s, be) -> be.clientTick());
        }
        return createTickerHelper(type, TFBlockEntities.JUNCTION.get(), (l, pos, s, be) -> be.serverTick());
    }
}
