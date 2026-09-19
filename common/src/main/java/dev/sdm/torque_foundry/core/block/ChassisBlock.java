package dev.sdm.torque_foundry.core.block;

import com.mojang.serialization.MapCodec;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.core.item.TFItems;
import dev.sdm.torque_foundry.physics.RotationalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Шасси коробки: корпус с входом (задняя грань относительно FACING)
 * и выходом (FACING). Внутри — слот одной шестерни (предмет).
 * Пустое шасси мощность не проводит.
 */
public class ChassisBlock extends MechanicalBlock {

    public static final MapCodec<ChassisBlock> CODEC = simpleCodec(ChassisBlock::new);

    /** Выход коробки (FACING): вход — противоположная грань. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public ChassisBlock(Properties properties) {
        super(RotationalPower.from(0, 0), properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Выход смотрит от игрока
        return defaultBlockState().setValue(
                FACING, context.getNearestLookingDirection().getOpposite());
    }

    /**
     * ПКМ по шасси:
     * - шестернёй — вставить (если слот пуст)
     * - гаечным ключом — извлечь шестерню
     * - рукой — статус
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ChassisBlockEntity be)) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }

        // Вставка шестерни
        if (stack.getItem() instanceof dev.sdm.torque_foundry.core.item.GearItem) {
            if (!be.getGear().isEmpty()) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.translatable(
                            "item.torque_foundry.chassis.gear_returned"), true);
                    // Старая возвращается в инвентарь
                    player.getInventory().placeItemBackInInventory(be.takeGear());
                    installGear(be, stack, player);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide) {
                installGear(be, stack, player);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Извлечение ключом
        if (stack.getItem() == TFItems.WRENCH.get()) {
            if (!level.isClientSide && !be.getGear().isEmpty()) {
                player.getInventory().placeItemBackInInventory(be.takeGear());
                player.displayClientMessage(Component.translatable(
                        "item.torque_foundry.chassis.gear_taken"), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    private static void installGear(ChassisBlockEntity be, ItemStack stack, Player player) {
        be.setGear(stack.copyWithCount(1));
        stack.consume(1, player);
        player.displayClientMessage(Component.translatable(
                "item.torque_foundry.chassis.gear_installed",
                dev.sdm.torque_foundry.core.item.GearItem.teethOf(stack)), true);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof ChassisBlockEntity be && !level.isClientSide) {
            if (be.getGear().isEmpty()) {
                player.displayClientMessage(Component.translatable(
                        "item.torque_foundry.chassis.empty"), false);
            } else {
                final var gear = be.getGear();
                final int teeth = dev.sdm.torque_foundry.core.item.GearItem.teethOf(gear);
                final double ratio = dev.sdm.torque_foundry.core.item.GearItem.ratio(teeth);
                player.displayClientMessage(Component.translatable(
                        "item.torque_foundry.chassis.status", teeth,
                        String.format(java.util.Locale.ROOT, "1:%.2f", ratio),
                        dev.sdm.torque_foundry.core.item.GearItem.materialOf(gear).name()), false);
            }
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new ChassisBlockEntity(blockPos, blockState);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, TFBlockEntities.CHASSIS.get(), (l, pos, s, be) -> be.clientTick());
        }
        return createTickerHelper(type, TFBlockEntities.CHASSIS.get(), (l, pos, s, be) -> be.serverTick());
    }
}
