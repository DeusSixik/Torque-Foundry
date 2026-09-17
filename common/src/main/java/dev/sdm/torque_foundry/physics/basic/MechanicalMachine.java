package dev.sdm.torque_foundry.physics.basic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class MechanicalMachine {

    /**
     * Роль грани машины. Одна роль на грань:
     * INPUT — только принимает, OUTPUT — только отдаёт,
     * IN_OUT — пассивный проходной порт (вал: принимает и отдаёт),
     * NONE — грань не проводит мощность.
     */
    public enum PortRole {
        NONE,
        INPUT,
        OUTPUT,
        IN_OUT
    }

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

    /**
     * Состояние работы, вычисляется физическим тиком группы.
     */
    protected WorkState workState = WorkState.IDLE;

    /**
     * Мощность сети, полученная машиной на прошлом физическом тике.
     */
    protected final MechanicalPower received = MechanicalPower.fromRaw(0, 0);

    /**
     * Свободная мощность узла (в ваттах): received минус требования детей.
     * "Сколько осталось в этом узле на собственные нужды".
     */
    protected long freePower;

    /**
     * Локальные порты машины (в системе координат блока при повороте 0).
     * Заполняется в {@link #createDirections()} через {@link #port}.
     * Мировые направления получаются поворотом по {@link #facing}.
     */
    private final Map<Direction, PortRole> localPorts = new EnumMap<>(Direction.class);
    private final Map<Direction, PortRole> worldPorts = new EnumMap<>(Direction.class);
    private Direction[] inputDirections = EMPTY_DIRECTIONS;
    private Direction[] outputDirections = EMPTY_DIRECTIONS;

    /**
     * Пассивная машина (вал): принимает мощность входными гранями
     * и отдаёт через другие грани — направление потока определяется
     * положением источника, а не фиксированными портами.
     */
    private boolean passive;

    /**
     * Ось машины (для осевых машин: вал).
     */
    protected Direction.Axis axis = Direction.Axis.X;

    /**
     * Ориентация блока: в какую мировую сторону смотрит локальный NORTH.
     * Порты, заданные в локальных координатах, поворачиваются вместе с ней.
     */
    private Direction facing = Direction.NORTH;

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
        rebuildWorldPorts();
    }

    /**
     * Источник: отдаёт энергию (входов нет, выходы есть).
     */
    public boolean isSource() {
        return inputDirections.length == 0 && outputDirections.length > 0;
    }

    /**
     * Выдаваемая мощность источника. Не источник — null.
     */
    public MechanicalPower getOutput() {
        return null;
    }

    public byte getRequiredDirection() {
        return requiredDirection;
    }

    public MechanicalPower getRequired() {
        return required;
    }

    public long getGroupIndex() {
        return groupIndex;
    }

    public void setGroupIndex(long groupIndex) {
        this.groupIndex = groupIndex;
    }

    public int getGroupElementIndex() {
        return groupElementIndex;
    }

    public void setGroupElementIndex(int groupElementIndex) {
        this.groupElementIndex = groupElementIndex;
    }

    public WorkState getWorkState() {
        return workState;
    }

    public void setWorkState(WorkState workState) {
        this.workState = workState;
    }

    /**
     * Мощность сети, полученная машиной (заполняется физическим тиком).
     */
    public MechanicalPower getReceived() {
        return received;
    }

    public void setReceived(MechanicalPower power) {
        this.received.copyFrom(power);
    }

    /**
     * Свободная мощность узла в ваттах (received − требования детей).
     */
    public long getFreePower() {
        return freePower;
    }

    public void setFreePower(long watts) {
        this.freePower = watts;
    }

    /**
     * Трансформация мощности при передаче через машину в грань outputSide.
     * Коробки передач/планетарки/ремни меняют соотношение RPM/Nm,
     * конические поворачивают ось и т.д. По умолчанию — passthrough.
     *
     * @param input      мощность на входе машины
     * @param outputSide мировая грань, через которую мощность покидает машину
     */
    public MechanicalPower transform(MechanicalPower input, Direction outputSide) {
        return input;
    }

    public boolean isPassive() {
        return passive;
    }

    protected void setPassive(boolean passive) {
        this.passive = passive;
    }

    public Direction.Axis getAxis() {
        return axis;
    }

    /**
     * Ось из blockstate (вал и т.п.). Пассивные машины переопределяют
     * и перестраивают порты.
     */
    public void setAxis(Direction.Axis axis) {
        this.axis = axis;
    }

    public Direction getFacing() {
        return facing;
    }

    /**
     * Ориентация блока из blockstate (FACING): поворачивает порты.
     */
    public void setFacing(Direction facing) {
        this.facing = facing;
        rebuildWorldPorts();
    }

    // --- Порты ---

    /**
     * Регистрирует порт в ЛОКАЛЬНЫХ координатах (при повороте блока 0).
     */
    protected void port(Direction localSide, PortRole role) {
        localPorts.put(localSide, role == null ? PortRole.NONE : role);
        rebuildWorldPorts();
    }

    protected void clearPorts() {
        localPorts.clear();
        rebuildWorldPorts();
    }

    /**
     * Прямая установка МИРОВОГО порта (минуя поворот) — для осевых машин (вал).
     */
    protected void worldPort(Direction worldSide, PortRole role) {
        worldPorts.put(worldSide, role == null ? PortRole.NONE : role);
        rebuildArrays();
    }

    protected void clearWorldPorts() {
        worldPorts.clear();
        rebuildArrays();
    }

    /**
     * INPUT-грань (учитывается и IN_OUT — пассивный проходной порт).
     */
    public boolean isInputSide(Direction side) {
        final PortRole role = worldPorts.get(side);
        return role == PortRole.INPUT || role == PortRole.IN_OUT;
    }

    /**
     * OUTPUT-грань (учитывается и IN_OUT).
     */
    public boolean isOutputSide(Direction side) {
        final PortRole role = worldPorts.get(side);
        return role == PortRole.OUTPUT || role == PortRole.IN_OUT;
    }

    private void rebuildWorldPorts() {
        worldPorts.clear();
        for (Map.Entry<Direction, PortRole> e : localPorts.entrySet()) {
            worldPorts.put(rotateLocalToWorld(e.getKey(), facing), e.getValue());
        }
        rebuildArrays();
    }

    private void rebuildArrays() {
        final List<Direction> in = new ArrayList<>(6);
        final List<Direction> out = new ArrayList<>(6);
        for (Map.Entry<Direction, PortRole> e : worldPorts.entrySet()) {
            if (e.getValue() == PortRole.INPUT || e.getValue() == PortRole.IN_OUT) {
                in.add(e.getKey());
            }
            if (e.getValue() == PortRole.OUTPUT || e.getValue() == PortRole.IN_OUT) {
                out.add(e.getKey());
            }
        }
        this.inputDirections = in.toArray(EMPTY_DIRECTIONS);
        this.outputDirections = out.toArray(EMPTY_DIRECTIONS);
    }

    /**
     * Поворот локального направления в мировое по ориентации блока
     * (вращение вокруг Y: локальный NORTH смотрит в сторону facing).
     */
    private static Direction rotateLocalToWorld(Direction local, Direction facing) {
        if (local.getAxis() == Direction.Axis.Y) {
            return local;
        }
        return switch (facing) {
            case EAST -> local.getClockWise();
            case SOUTH -> local.getOpposite();
            case WEST -> local.getCounterClockWise();
            default -> local;
        };
    }

    // --- Совместимость (используется менеджером/физикой/оверлеем) ---

    public Direction[] getInputDirections() {
        return inputDirections;
    }

    public Direction[] getOutputDirections() {
        return outputDirections;
    }

    protected void createDirections() {
        port(Direction.SOUTH, PortRole.INPUT);
        port(Direction.NORTH, PortRole.OUTPUT);
    }
}
