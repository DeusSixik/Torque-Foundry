package dev.sdm.torque_foundry.core.block;

import com.mojang.serialization.MapCodec;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.core.item.ShaftItem;
import dev.sdm.torque_foundry.core.item.ShaftPartItem;
import dev.sdm.torque_foundry.core.item.TFItems;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.BearingType;
import dev.sdm.torque_foundry.physics.machine.LubricantKind;
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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * Корпус (Case): пустым ставится как глухой Casing (портов нет, сеть не
 * проводит) и рендерится без вала. ПКМ предметом-вала (shaft_part)
 * вставляет вал — корпус становится проходным сегментом вала IN_OUT
 * по оси блока из материала вставки. ПКМ ключом — достать вал обратно.
 *
 * <p>Замена будущим механизмам: тот же слот позже примут шестерни
 * и прочие вставки — chassis при этом остаётся как есть.
 */
public class CaseBlock extends MechanicalBlock {

    public static final MapCodec<CaseBlock> CODEC = simpleCodec(CaseBlock::new);

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;

    /**
     * true — блок висит на потолке (клик по нижней грани): модель перевёрнута.
     */
    public static final BooleanProperty CEILING = BooleanProperty.create("ceiling");

    public CaseBlock(Properties properties) {
        super(RotationalPower.from(0, 0), properties);
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
     * Ось — как у вала: об механический блок — по нормали грани,
     * с зажатым Shift — чистый X, иначе X по умолчанию.
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
     * Блок полностью рисуется через CaseRenderer (BER),
     * статическая модель блока не нужна.
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new CaseBlockEntity(blockPos, blockState);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, TFBlockEntities.CASE.get(), (l, pos, s, be) -> be.clientTick());
        }
        return createTickerHelper(type, TFBlockEntities.CASE.get(), (l, pos, s, be) -> be.serverTick());
    }

    /**
     * Сломанный корпус роняет и вставленный вал (а не только корпус).
     */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        final List<ItemStack> drops = super.getDrops(state, params);
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof CaseBlockEntity be
                && !be.getShaft().isEmpty()) {
            drops.add(be.getShaft().copy());
        }
        return drops;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof CaseBlockEntity be && !level.isClientSide) {
            if (be.getShaft().isEmpty()) {
                player.displayClientMessage(Component.translatable(
                        "item.torque_foundry.case.empty"), false);
            } else {
                final var material = ShaftPartItem.materialOf(be.getShaft());
                player.displayClientMessage(Component.translatable(
                        "item.torque_foundry.chassis.shaft_status", material.name()), false);
            }
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    /**
     * ПКМ по корпусу:
     * - валом (shaft_part) — вставить (занятый слот: старый в инвентарь, новый внутрь)
     * - ключом — достать вал
     * - подшипником/смазкой по собранному валу — как у обычного вала
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CaseBlockEntity be)) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }

        // Вставка вала
        if (stack.getItem() instanceof ShaftPartItem) {
            if (!level.isClientSide) {
                if (!be.getShaft().isEmpty()) {
                    player.displayClientMessage(Component.translatable(
                            "item.torque_foundry.case.shaft_returned"), true);
                    player.getInventory().placeItemBackInInventory(be.takeShaft());
                }
                installShaft(be, stack, player);
                afterShaftChanged(level, pos, be);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Извлечение ключом
        if (stack.getItem() == TFItems.WRENCH.get()) {
            // Ключ по собранному валу: сначала пробуем снять подшипник с торца
            // (как у обычного вала), иначе — достать весь вал.
            final Direction face = hit.getDirection();
            if (!level.isClientSide && !be.getShaft().isEmpty()
                    && face.getAxis() == state.getValue(AXIS)) {
                final int slot = bearingSlotFor(face, state.getValue(AXIS));
                final BearingType removed = be.machine.removeBearing(slot);
                if (removed != BearingType.NONE) {
                    final Item back = TFItems.itemOf(removed);
                    if (back != null) {
                        player.getInventory().placeItemBackInInventory(new ItemStack(back));
                    }
                    player.displayClientMessage(Component.translatable(
                            "message.torque_foundry.bearing_removed"), true);
                    return ItemInteractionResult.sidedSuccess(false);
                }
            }
            if (!level.isClientSide && !be.getShaft().isEmpty()) {
                player.getInventory().placeItemBackInInventory(be.takeShaft());
                player.displayClientMessage(Component.translatable(
                        "item.torque_foundry.case.shaft_taken"), true);
                afterShaftChanged(level, pos, be);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Подшипник/смазка — только по собранному валу (как у ShaftBlock)
        if (!be.getShaft().isEmpty()) {
            final BearingType bearing = TFItems.bearingOf(stack.getItem());
            if (bearing != null) {
                final Direction face = hit.getDirection();
                if (face.getAxis() != state.getValue(AXIS)) {
                    return super.useItemOn(stack, state, level, pos, player, hand, hit);
                }
                if (!level.isClientSide) {
                    be.machine.installBearing(bearingSlotFor(face, state.getValue(AXIS)), bearing);
                    stack.consume(1, player);
                    player.displayClientMessage(Component.translatable(
                            "message.torque_foundry.bearing_installed",
                            bearing.name(), bearingSlotFor(face, state.getValue(AXIS))), true);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }

            final LubricantKind lube = TFItems.lubricantOf(stack.getItem());
            if (lube != null && !level.isClientSide) {
                be.machine.getLubricant().fill(lube, LubricantState.CAPACITY);
                stack.consume(1, player);
                player.displayClientMessage(Component.translatable(
                        "message.torque_foundry.lubricated", lube.name()), true);
                return ItemInteractionResult.sidedSuccess(false);
            }
        }

        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    private static void installShaft(CaseBlockEntity be, ItemStack stack, Player player) {
        be.setShaft(stack.copyWithCount(1));
        // Тир балансировки вставки -> перекос машины (как у ShaftBlock)
        be.machine.setMisalignmentDeg(ShaftItem.gradeOf(stack).misalignmentDeg());
        stack.consume(1, player);
        player.displayClientMessage(Component.translatable(
                "item.torque_foundry.case.shaft_installed",
                ShaftPartItem.materialOf(be.getShaft()).name()), true);
    }

    /**
     * Порты машины изменились (вал вставлен/извлечён): выкидываем машину
     * из группы — следующий serverTick переподключит её по новым портам
     * и разошлёт синк; клиенту досылаем BE (рендер Val).
     */
    private static void afterShaftChanged(Level level, BlockPos pos, CaseBlockEntity be) {
        MechanicalGroupManager.remove(be.machine);
        final BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }

    /** Слот опоры по торцу: положительный конец оси = 0, отрицательный = 1. */
    private static int bearingSlotFor(Direction face, Direction.Axis axis) {
        final Direction positive = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        return face == positive ? 0 : 1;
    }
}
