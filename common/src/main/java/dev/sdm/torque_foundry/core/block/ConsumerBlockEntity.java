package dev.sdm.torque_foundry.core.block;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class ConsumerBlockEntity extends MechanicalBlockEntity {

    public ConsumerBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(TFBlockEntities.CONSUMER.get(), blockPos, blockState);
    }

    @Override
    protected MechanicalMachine createMachine(MechanicalPower power) {
        return new ConsumerMachine(power.getSpeedRaw(), power.getTorqueRaw(),
                RotationDirection.from(power.getDirection()));
    }

    public ConsumerMachine getConsumer() {
        return (ConsumerMachine) this.machine;
    }
}
