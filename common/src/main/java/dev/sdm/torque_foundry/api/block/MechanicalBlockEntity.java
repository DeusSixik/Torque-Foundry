package dev.sdm.torque_foundry.api.block;

import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.core.network.ClientGroupCache;
import dev.sdm.torque_foundry.core.network.TFNetworking;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class MechanicalBlockEntity extends BlockEntity {

    public final MechanicalMachine machine;

    public MechanicalBlockEntity(BlockEntityType<?> blockEntityType, BlockPos blockPos, BlockState blockState) {
        super(blockEntityType, blockPos, blockState);

        if(!(blockState.getBlock() instanceof MechanicalBlock block)) {
            throw new IllegalArgumentException("MechanicalBlocks only support MechanicalBlocks");
        }

        final MechanicalPower power = block.power;
        this.machine = createMachine(power);
        this.machine.setBlockPos(blockPos);
    }

    /**
     * Серверный тик: ленивая (пере)регистрация машины в группе.
     * Срабатывает после любой загрузки чанка — старт сервера,
     * подгрузка чанков, перезагрузка измерения.
     */
    public void serverTick() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }

        if (this.machine.getGroupIndex() == -1) {
            final MechanicalGroup group = MechanicalGroupManager.createOrAdd(this.level, this);
            TFNetworking.syncGroup((ServerLevel) this.level, group, this.worldPosition);

            // Группы, влитые при этом соединении (или удалённые), убираем у клиентов
            for (MechanicalGroup newGroup : MechanicalGroupManager.drainNewGroups()) {
                TFNetworking.syncGroup((ServerLevel) this.level, newGroup, this.worldPosition);
            }
            for (long removedGroupId : MechanicalGroupManager.drainRemovedGroups()) {
                TFNetworking.syncGroupRemoved((ServerLevel) this.level, removedGroupId, this.worldPosition);
            }
        }
    }

    /**
     * Клиентский тик: до применяет данные группы из кэша,
     * если пакет пришёл раньше, чем догрузился чанк.
     */
    public void clientTick() {
        if (this.machine.getGroupIndex() == -1) {
            ClientGroupCache.applyTo(this);
        }
    }

    protected MechanicalMachine createMachine(MechanicalPower power) {
        return MechanicalMachine.fromRaw(power.getSpeedRaw(),
                power.getTorqueRaw(), RotationDirection.from(power.getDirection())
        );
    }
}
