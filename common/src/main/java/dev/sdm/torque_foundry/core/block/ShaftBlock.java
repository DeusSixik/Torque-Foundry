package dev.sdm.torque_foundry.core.block;

import com.mojang.serialization.MapCodec;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Пассивный вал: передаёт вращение между машинами группы.
 * Ставится вдоль оси, по которой кликнули (грань блока).
 */
public class ShaftBlock extends MechanicalBlock {

    public static final MapCodec<ShaftBlock> CODEC = simpleCodec(ShaftBlock::new);

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;

    /**
     * true — блок висит на потолке (клик по нижней грани): модель перевёрнута.
     */
    public static final BooleanProperty CEILING = BooleanProperty.create("ceiling");

    public ShaftBlock(Properties properties) {
        super(MechanicalPower.from(0, 0), properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(AXIS, Direction.Axis.Y)
                .setValue(CEILING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, CEILING);
    }

    /**
     * По умолчанию вал лежит вдоль X. Направить его по Y (или по грани)
     * можно только ставя об другой механический блок — тогда ось
     * берётся из нормали грани, на которую кликнули.
     * С зажатым Shift вал всегда ставится без Y-ориентации (чистый X).
     * Если над точкой установки плотный блок — вал переворачивается на потолок.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        final BlockState above = context.getLevel().getBlockState(context.getClickedPos().above());
        final boolean ceiling = above.isSolid();

        if (context.isSecondaryUseActive()) {
            return this.defaultBlockState()
                    .setValue(AXIS, Direction.Axis.X)
                    .setValue(CEILING, ceiling);
        }

        final BlockPos againstPos = context.getClickedPos().relative(context.getClickedFace().getOpposite());
        final BlockState against = context.getLevel().getBlockState(againstPos);

        if (against.getBlock() instanceof MechanicalBlock) {
            return this.defaultBlockState()
                    .setValue(AXIS, context.getClickedFace().getAxis())
                    .setValue(CEILING, ceiling);
        }
        return this.defaultBlockState()
                .setValue(AXIS, Direction.Axis.X)
                .setValue(CEILING, ceiling);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return switch (rotation) {
            case COUNTERCLOCKWISE_90, CLOCKWISE_90 -> state.setValue(AXIS,
                    state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
            default -> state;
        };
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state;
    }

    /**
     * Блок полностью рисуется через ShaftRenderer (BER),
     * статическая модель блока не нужна.
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
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
