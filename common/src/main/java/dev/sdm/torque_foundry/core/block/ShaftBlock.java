package dev.sdm.torque_foundry.core.block;

import com.mojang.serialization.MapCodec;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.core.item.TFItems;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.BearingType;
import dev.sdm.torque_foundry.physics.machine.LubricantState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
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

    /**
     * Материал вала: безопасные обороты, инерция, трение.
     * Переключается Shift+ПКМ (смотри useWithoutItem).
     */
    public static final EnumProperty<ShaftMaterial> MATERIAL = EnumProperty.create("material", ShaftMaterial.class);

    public ShaftBlock(Properties properties) {
        super(RotationalPower.from(0, 0), properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(AXIS, Direction.Axis.Y)
                .setValue(CEILING, false)
                .setValue(MATERIAL, ShaftMaterial.IRON));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, CEILING, MATERIAL);
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
    public dev.sdm.torque_foundry.physics.material.PhysicsMaterial materialOf(BlockState state) {
        return state.getValue(MATERIAL).machine;
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
    public void setPlacedBy(Level level, BlockPos blockPos, BlockState blockState, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, blockPos, blockState, placer, stack);
        // Тир балансировки предмета -> перекос машины (Grade C..S)
        if (!level.isClientSide && level.getBlockEntity(blockPos) instanceof ShaftBlockEntity be) {
            be.machine.setMisalignmentDeg(dev.sdm.torque_foundry.core.item.ShaftItem.gradeOf(stack).misalignmentDeg());
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState blockState, Level level, BlockPos blockPos, Player player, BlockHitResult blockHitResult) {
        final MechanicalBlockEntity be = (MechanicalBlockEntity) level.getBlockEntity(blockPos);

        if (player.isSecondaryUseActive()) {
            // Shift+ПКМ: следующий материал вала
            if (!level.isClientSide) {
                final ShaftMaterial next = blockState.getValue(MATERIAL).next();
                level.setBlock(blockPos, blockState.setValue(MATERIAL, next), Block.UPDATE_ALL);
                // BE не пересоздаётся при смене свойства — обновляем машину сами
                if (be != null) {
                    be.machine.setMaterial(next.machine);
                }
                player.displayClientMessage(Component.translatable(
                        "message.torque_foundry.shaft_material", next.getSerializedName()), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // Статус узла: группа, перекос, опоры, смазка.
        // ВАЖНО: TranslatableContents принимает только Component|Number|Boolean|String —
        // enum'ы (BearingType, LubricantState.Type) отдаём через .name(), иначе
        // сервер роняет пакет useItemOn с IllegalArgumentException.
        if (be != null && !level.isClientSide) {
            final dev.sdm.torque_foundry.physics.machine.Bearing a = be.machine.getBearing(0);
            final dev.sdm.torque_foundry.physics.machine.Bearing b = be.machine.getBearing(1);
            final LubricantState lube = be.machine.getLubricant();
            player.displayClientMessage(Component.translatable(
                    "message.torque_foundry.shaft_status",
                    be.machine.getGroupIndex(),
                    String.format(java.util.Locale.ROOT, "%.1f", be.machine.getMisalignmentDeg()),
                    a.type().name(), Math.round(a.wear() * 100),
                    b.type().name(), Math.round(b.wear() * 100),
                    Math.round(lube.amount()), (int) LubricantState.CAPACITY,
                    lube.type().name()), false);
        }
        return super.useWithoutItem(blockState, level, blockPos, player, blockHitResult);
    }

    /**
     * ПКМ предметом по валу:
     * - подшипник в торец (клик по осевой грани вала)
     * - смазка в резервуар
     * - гаечный ключ: снять подшипник с кликнутого торца
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        final MechanicalBlockEntity be = (MechanicalBlockEntity) level.getBlockEntity(pos);
        if (be == null || !be.machine.hasBearingSlots()) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }

        // Подшипник: только по осевой (торцевой) грани вала
        final BearingType bearing = TFItems.bearingOf(stack.getItem());
        if (bearing != null) {
            final Direction face = hit.getDirection();
            if (face.getAxis() != state.getValue(AXIS)) {
                return super.useItemOn(stack, state, level, pos, player, hand, hit);
            }
            if (!level.isClientSide) {
                final int slot = bearingSlotFor(face, state.getValue(AXIS));
                // Отказавшая опора уже опустела — ставим поверх свободно
                be.machine.installBearing(slot, bearing);
                stack.consume(1, player);
                player.displayClientMessage(Component.translatable(
                        "message.torque_foundry.bearing_installed",
                        bearing.name(), slot), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Смазка: в резервуар машины
        final LubricantState.Type lube = TFItems.lubricantOf(stack.getItem());
        if (lube != null && !level.isClientSide) {
            // Один предмет заполняет весь резервуар (упрощение первой итерации)
            be.machine.getLubricant().fill(lube, LubricantState.CAPACITY);
            stack.consume(1, player);
            player.displayClientMessage(Component.translatable(
                    "message.torque_foundry.lubricated", lube.name()), true);
            return ItemInteractionResult.sidedSuccess(false);
        }

        // Гаечный ключ: снять подшипник с кликнутого торца
        if (stack.getItem() == TFItems.WRENCH.get()) {
            final Direction face = hit.getDirection();
            if (face.getAxis() == state.getValue(AXIS) && !level.isClientSide) {
                final int slot = bearingSlotFor(face, state.getValue(AXIS));
                final BearingType removed = be.machine.removeBearing(slot);
                if (removed != BearingType.NONE) {
                    final Item back = TFItems.itemOf(removed);
                    if (back != null) {
                        player.getInventory().placeItemBackInInventory(new ItemStack(back));
                    }
                    player.displayClientMessage(Component.translatable(
                            "message.torque_foundry.bearing_removed"), true);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /** Слот опоры по торцу: положительный конец оси = 0, отрицательный = 1. */
    private static int bearingSlotFor(Direction face, Direction.Axis axis) {
        final Direction positive = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        return face == positive ? 0 : 1;
    }
}
