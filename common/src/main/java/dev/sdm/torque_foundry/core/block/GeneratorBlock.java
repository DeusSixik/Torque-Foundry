package dev.sdm.torque_foundry.core.block;

import com.mojang.serialization.MapCodec;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
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

public class GeneratorBlock extends MechanicalBlock {

    public static final RotationalPower DEFAULT_OUTPUT = RotationalPower.from(256, 64);

    public static final MapCodec<GeneratorBlock> CODEC = simpleCodec(
            properties -> new GeneratorBlock(properties, DEFAULT_OUTPUT));

    public GeneratorBlock(Properties properties, RotationalPower outputPower) {
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

    /**
     * ПКМ по блоку: явное переключение режима генератора (низкий/паспорт).
     * Умолчание — низкий: стартовая деревянная линия живёт без износа.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState blockState, Level level, BlockPos blockPos,
                                               Player player, BlockHitResult blockHitResult) {
        if (!level.isClientSide
                && level.getBlockEntity(blockPos) instanceof GeneratorBlockEntity be) {
            final GeneratorMachine gen = be.getGenerator();
            gen.setHighMode(!gen.isHighMode());
            // Целевая скорость сети изменилась — свежий снапшот клиентам
            MechanicalGroupManager.markDirty(gen.getGroupIndex());
            player.displayClientMessage(Component.translatable(
                    "message.torque_foundry.generator_mode",
                    gen.isHighMode()
                            ? String.valueOf(gen.getOutput().getSpeedRpm())
                            : String.valueOf(GeneratorMachine.LOW_MODE_SPEED_RAW / 1000)), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, TFBlockEntities.GENERATOR.get(), (l, pos, s, be) -> be.clientTick());
        }
        return createTickerHelper(type, TFBlockEntities.GENERATOR.get(), (l, pos, s, be) -> be.serverTick());
    }
}
