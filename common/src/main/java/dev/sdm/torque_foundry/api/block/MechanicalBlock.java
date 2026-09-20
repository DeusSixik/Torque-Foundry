package dev.sdm.torque_foundry.api.block;

import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.core.network.TFNetworking;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public abstract class MechanicalBlock extends BaseEntityBlock {

    protected final RotationalPower power;

    public MechanicalBlock(RotationalPower power, Properties properties) {
        super(properties);
        this.power = power;
    }

    public RotationalPower getPower() {
        return power;
    }

    /**
     * Физический материал машины для этого blockstate (по умолчанию — железо).
     * Переопределяется блоками со свойством MATERIAL (вал).
     */
    public dev.sdm.torque_foundry.physics.material.PhysicsMaterial materialOf(BlockState state) {
        return dev.sdm.torque_foundry.physics.material.PhysicsMaterials.DEFAULT;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos blockPos, BlockState blockState, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, blockPos, blockState, placer, stack);

        if (level.isClientSide) {
            // Клиент: запускаем свечение портов после установки.
            dev.sdm.torque_foundry.core.client.render.PortFaceOverlay.markPlaced(blockPos);
            return;
        }

        final BlockEntity entity = level.getBlockEntity(blockPos);
        if (entity instanceof MechanicalBlockEntity mechanicalBlock) {
            final MechanicalGroup group = MechanicalGroupManager.createOrAdd(level, mechanicalBlock);
            TFNetworking.syncGroup((ServerLevel) level, group, blockPos);
        }
    }

    @Override
    protected void onRemove(BlockState blockState, Level level, BlockPos blockPos, BlockState newState, boolean movedByPiston) {
        if (!blockState.is(newState.getBlock()) && !level.isClientSide) {
            final BlockEntity entity = level.getBlockEntity(blockPos);
            if (entity instanceof MechanicalBlockEntity mechanicalBlock) {
                final long groupId = mechanicalBlock.machine.getGroupIndex();
                final MechanicalGroup group = groupId == -1 ? null : MechanicalGroupManager.getGroup(groupId);

                if (MechanicalGroupManager.remove(mechanicalBlock)) {
                    TFNetworking.syncGroupRemoved((ServerLevel) level, groupId, blockPos);
                } else if (group != null) {
                    TFNetworking.syncGroup((ServerLevel) level, group, blockPos);

                    // Цепочка распалась — отправляем образовавшиеся группы
                    for (MechanicalGroup newGroup : MechanicalGroupManager.drainNewGroups()) {
                        TFNetworking.syncGroup((ServerLevel) level, newGroup, blockPos);
                    }
                }

                for (long removedGroupId : MechanicalGroupManager.drainRemovedGroups()) {
                    TFNetworking.syncGroupRemoved((ServerLevel) level, removedGroupId, blockPos);
                }
            }
        }
        super.onRemove(blockState, level, blockPos, newState, movedByPiston);
    }
}
