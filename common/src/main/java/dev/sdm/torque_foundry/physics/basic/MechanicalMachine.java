package dev.sdm.torque_foundry.physics.basic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public class MechanicalMachine {

    public static MechanicalMachine from(long requiredSpeed, long requiredTorque) {
        return from(requiredSpeed, requiredTorque, null);
    }

    public static MechanicalMachine from(long requiredSpeed, long requiredTorque, RotationDirection direction) {
        MechanicalPower req = MechanicalPower.from(requiredSpeed, requiredTorque);
        return new MechanicalMachine(req, direction == null ? -1 : direction.index);
    }

    public static MechanicalMachine fromRaw(long requiredSpeedRaw, long requiredTorqueRaw, RotationDirection direction) {
        MechanicalPower req = MechanicalPower.fromRaw(requiredSpeedRaw, requiredTorqueRaw);
        return new MechanicalMachine(req, direction == null ? -1 : direction.index);
    }

    protected static final Direction[] EMPTY_DIRECTIONS = new Direction[0];

    // required храним как MechanicalPower — переиспользуем его SCALE-логику,
    // а не дублируем формулы конверсии тут
    protected final MechanicalPower required;
    protected final byte requiredDirection; // -1 = направление не важно
    protected long groupIndex = -1;
    protected int groupElementIndex = -1;

    protected Direction[] inputDirections = EMPTY_DIRECTIONS;
    protected Direction[] outputDirections = EMPTY_DIRECTIONS;

    /**
     * Позиция блока-владельца (null для headless-машин вне мира).
     */
    protected BlockPos pos;

    public BlockPos getBlockPos() {
        return pos;
    }

    public void setBlockPos(BlockPos pos) {
        this.pos = pos;
    }

    protected MechanicalMachine(MechanicalPower required, byte requiredDirection) {
        this.required = required;
        this.requiredDirection = requiredDirection;
        createDirections();
    }

    public boolean canWork(MechanicalPower input) {
        if (requiredDirection != -1 && input.getDirection() != requiredDirection) {
            return false;
        }
        return input.getSpeedRaw() >= required.getSpeedRaw()
                && input.getTorqueRaw() >= required.getTorqueRaw()
                && input.getPower() >= required.getPower();
    }

    public MechanicalPower getRequired() {
        return required;
    }

    public void setGroupIndex(long groupIndex) {
        this.groupIndex = groupIndex;
    }

    public void setGroupElementIndex(int groupIndex) {
        groupElementIndex = groupIndex;
    }

    public long getGroupIndex() {
        return groupIndex;
    }

    public int getGroupElementIndex() {
        return groupElementIndex;
    }

    protected void createDirections() {
        inputDirections = new Direction[] { Direction.SOUTH };
        outputDirections = new Direction[] { Direction.NORTH };
    }

    public Direction[] getInputDirections() {
        return inputDirections;
    }

    public Direction[] getOutputDirections() {
        return outputDirections;
    }
}