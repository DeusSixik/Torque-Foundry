package dev.sdm.torque_foundry.api.block;

import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.core.network.ClientGroupCache;
import dev.sdm.torque_foundry.core.network.TFNetworking;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import dev.sdm.torque_foundry.physics.basic.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

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
        this.machine.setMaterial(block.materialOf(blockState));
        applyOrientation(this.machine, blockState);
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

        // Накопление угла: скорость из физического состояния машины
        this.angle += getAngularVelocity();
        if (this.angle > (float) (2 * Math.PI) || this.angle < -(float) (2 * Math.PI)) {
            this.angle %= (float) (2 * Math.PI);
        }
    }

    /**
     * Накопленный угол вращения (радианы). Обновляется в клиентском тике,
     * рендер берёт через {@link #getRenderAngle(float)}.
     */
    private float angle;

    /**
     * @param partialTick доля тика для плавной интерполяции.
     * @return угол для рендера с учётом частичного тика.
     */
    public float getRenderAngle(float partialTick) {
        return angle + getAngularVelocity() * partialTick;
    }

    /**
     * Угловая скорость (рад/тик) из полученной мощности: WORKING — по RPM,
     * иначе 0. Направление вращения учтено.
     */
    public float getAngularVelocity() {
        if (machine.getWorkState() != WorkState.WORKING) {
            return 0;
        }
        final float rpm = (float) machine.getReceived().getSpeedRpm();
        // рад/тик: rpm / 60 * 2pi * (тик = 1/20 c)
        return (float) (rpm / 60.0 * 2.0 * Math.PI / 20.0)
                * (RotationDirection.from(machine.getReceived().getDirection()) == RotationDirection.FORWARD ? 1 : -1);
    }

    protected MechanicalMachine createMachine(MechanicalPower power) {
        return MechanicalMachine.fromRaw(power.getSpeedRaw(),
                power.getTorqueRaw(), RotationDirection.from(power.getDirection())
        );
    }

    /**
     * Передаёт машине ориентацию блока: FACING поворачивает порты,
     * AXIS задаёт ось (вал).
     */
    private void applyOrientation(MechanicalMachine machine, BlockState blockState) {
        if (blockState.hasProperty(BlockStateProperties.FACING)) {
            machine.setFacing(blockState.getValue(BlockStateProperties.FACING));
        } else if (blockState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            machine.setFacing(blockState.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        if (blockState.hasProperty(BlockStateProperties.AXIS)) {
            machine.setAxis(blockState.getValue(BlockStateProperties.AXIS));
        }
    }
}
