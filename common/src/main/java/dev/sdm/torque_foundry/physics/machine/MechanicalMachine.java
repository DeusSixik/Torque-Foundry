package dev.sdm.torque_foundry.physics.machine;

import dev.sdm.torque_foundry.physics.PhysicsMath;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
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
        RotationalPower req = RotationalPower.from(requiredSpeed, requiredTorque);
        return new MechanicalMachine(req, direction == null ? -1 : direction.index);
    }

    public static MechanicalMachine fromRaw(long requiredSpeedRaw, long requiredTorqueRaw, RotationDirection direction) {
        RotationalPower req = RotationalPower.fromRaw(requiredSpeedRaw, requiredTorqueRaw);
        return new MechanicalMachine(req, direction == null ? -1 : direction.index);
    }

    protected static final Direction[] EMPTY_DIRECTIONS = new Direction[0];

    // required храним как RotationalPower — переиспользуем его SCALE-логику,
    // а не дублируем формулы конверсии тут
    protected final RotationalPower required;
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
    protected final RotationalPower received = RotationalPower.fromRaw(0, 0);

    /**
     * Свободная мощность узла (в ваттах): received минус требования детей.
     * "Сколько осталось в этом узле на собственные нужды".
     */
    protected long freePower;

    /**
     * Показатели симуляции (мощность за тик, тепло, счётчики ресурса).
     */
    private final SimulationState simulationState = new SimulationState();

    /**
     * Опорные точки (подшипники) машины. У осевых машин (вал) их две —
     * торцы по оси; у остальных машин слоты неактивны и на физику
     * не влияют.
     */
    private final Bearing[] bearings = {new Bearing(), new Bearing()};

    /** Резервуар смазки (общий на машину). */
    private final LubricantState lubricant = new LubricantState();

    /**
     * Перекос/дисбаланс вала, градусы (из тира Grade при крафте).
     * Множители трения и износа: 1 + перекос × 0.5 / 1 + перекос.
     */
    private double misalignmentDeg = 0.0;

    /** Есть ли активные опорные точки (осевая машина). */
    private boolean bearingSlots = false;

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
     * Материал деталей машины: инерция (плотность), трение, безопасные обороты.
     */
    private PhysicsMaterial material =
            PhysicsMaterials.IRON;

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

    protected MechanicalMachine(RotationalPower required, byte requiredDirection) {
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
    public RotationalPower getOutput() {
        return null;
    }

    public byte getRequiredDirection() {
        return requiredDirection;
    }

    public RotationalPower getRequired() {
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
    public RotationalPower getReceived() {
        return received;
    }

    public void setReceived(RotationalPower power) {
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
     * @param outputSide грань, через которую мощность покидает машину
     */
    public RotationalPower transform(RotationalPower input, Direction outputSide) {
        return input;
    }

    /**
     * Балансовый хук конца физического тика:Received — что пришло в узел,
     * childrenWatts — что суммарно требуют дети (в ваттах).
     * Энергобуферы (маховик) здесь заряжаются от излишка и покрывают дефицит.
     * Вызывается в потоке физики.
     */
    public void onNetworkTick(long receivedWatts, long childrenWatts) {
    }

    /**
     * КПД передачи через машину (1.0 — без потерь). Потерянная мощность
     * P_loss = P_in · (1-η) уходит в тепло узла (SimulationState).
     */
    public double getEfficiency() {
        return 1.0;
    }

    /**
     * Момент страгивания (milli-Nm): входного момента меньше — машина
     * не тронется и клинит сеть. 0 — страгивание не требуется.
     */
    public long getBreakawayTorqueRaw() {
        return 0;
    }

    /**
     * Потери холостого хода (milli-Nm): ест момент сети, пока машина
     * вращается (трение рабочего органа, вентиляция). 0 — нет.
     */
    public long getIdleTorqueRaw() {
        return 0;
    }

    /**
     * Дополнительная инерция ротора/рабочего органа (в единицах инерции
     * сети, как getInertia()): тяжёлый барабан замедляет разгон сети.
     */
    public double getExtraInertia() {
        return 0.0;
    }

    /**
     * Тик работы ИСТОЧНИКА: потери преобразования греют ротор,
     * перегрев урезает выдачу (тепловой derate). Вызывается в фазе A
     * для каждой машины с output != null.
     *
     * @param outputWatts паспортная мощность источника на текущих оборотах, Вт
     */
    public void onSourceTick(long outputWatts) {
    }

    /**
     * Множитель паспортного момента источника (тепловой derate, 0..1].
     * Считается в {@link #onSourceTick}, применяется в фазе A.
     */
    public double getOutputFactor() {
        return 1.0;
    }

    /**
     * Показатели симуляции (мощность, тепло, ресурс) — мутабельная структура,
     * живёт в машине всё время существования. Тепло/деградация/буферы — читай
     * и пиши сюда вместо новых полей машины.
     */
    public SimulationState getSimulationState() {
        return simulationState;
    }

    /**
     * Симуляционный тик машины: нагрев трением + пассивное охлаждение,
     * износ подшипников, расход смазки. Вызывается конвейером в фазе
     * динамики для всех вращающихся машин. Переопределяется для своей
     * теплофизики (печи, тормоза и т.п.).
     *
     * @param frictionTorqueMilliNm момент трения этой машины, milli-Nm
     * @param speedMilliRpm         обороты, milli-RPM
     */
    public void onSimulationTick(long frictionTorqueMilliNm, long speedMilliRpm) {
        final double massKg = material.nominalMassKg();
        simulationState.addFrictionHeat(frictionTorqueMilliNm, speedMilliRpm);
        simulationState.coolTick(material, massKg);

        // Износ опор + расход смазки (только осевые машины со слотами)
        if (bearingSlots) {
            final double wf = wearFactor();
            bearings[0].wearTick(speedMilliRpm, wf);
            bearings[1].wearTick(speedMilliRpm, wf);

            int lubricatedCount = 0;
            for (Bearing b : bearings) {
                // Закрытый шариковый смазки не требует
                if (b.present() && b.type() != BearingType.BALL) {
                    lubricatedCount++;
                }
            }
            lubricant.consumeTick(speedMilliRpm, 1.0, lubricatedCount);
        }
    }

    public boolean isPassive() {
        return passive;
    }

    /**
     * Машина является проходным сегментом вала (полноценный вал-блок
     * или вал-вставка шасси): износ по оборотам/моменту применяется.
     */
    public boolean isShaftSegment() {
        return false;
    }

    protected void setPassive(boolean passive) {
        this.passive = passive;
    }

    /**
     * Машина-буфер (маховик): покрывает пиковый дефицит момента из своего
     * запаса — перегрузка не передаётся вверх по сети, пока есть резерв.
     */
    public boolean coversDeficitFromBuffer() {
        return false;
    }

    /**
     * Есть ли сейчас резерв буфера (для машин с coversDeficitFromBuffer).
     */
    public boolean hasBufferReserve() {
        return false;
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

    public PhysicsMaterial getMaterial() {
        return material;
    }

    public void setMaterial(PhysicsMaterial material) {
        this.material = material == null
                ? PhysicsMaterials.DEFAULT : material;
    }

    /**
     * Приведённая инерция машины (вклад в разгон/торможение сети):
     * плотность материала, нормированная от стали. В будущем — объём и
     * размеры деталей (реальный момент инерции).
     */
    public double getInertia() {
        return material.relativeDensity();
    }

    /**
     * Момент трения машины при заданных оборотах (milli-Nm):
     * вязкое трение из реального коэффициента μ с множителями опор
     * (подшипники, сухой ход, перекос). Минимум 1 milli-Nm —
     * чтобы сеть всегда останавливалась трением.
     */
    public long getFrictionTorque(long speedRaw) {
        return PhysicsMath.viscousFrictionTorque(
                material.viscousFriction() * frictionMultiplier(speedRaw), speedRaw);
    }

    /**
     * Множитель трения узла от опор и перекоса: произведение множителей
     * опорных точек × перекос. Без активных слотов — только перекос.
     */
    public double frictionMultiplier(long speedRaw) {
        double m = 1.0;
        if (bearingSlots) {
            final boolean lubed = lubricant.available();
            for (Bearing b : bearings) {
                m *= b.frictionMultiplier(lubed);
            }
        }
        // Перекос: дисбаланс мешает вращению
        m *= 1.0 + misalignmentDeg * 0.5;
        return m;
    }

    /**
     * Множитель износа узла (подшипники): перекос ускоряет,
     * сухой ход ускоряет втрое — но только опорам, требующим смазки.
     */
    public double wearFactor() {
        double w = 1.0 + misalignmentDeg;
        if (bearingSlots && !lubricant.available()) {
            boolean anyNeedsLube = false;
            for (Bearing b : bearings) {
                if (b.needsLubrication()) {
                    anyNeedsLube = true;
                    break;
                }
            }
            if (anyNeedsLube) {
                w *= 3.0; // на сухую износ втрое быстрее
            }
        }
        return w;
    }

    // --- Опоры и смазка ---

    /** Активирует опорные слоты (осевая машина: вал). */
    protected void enableBearingSlots() {
        this.bearingSlots = true;
    }

    public boolean hasBearingSlots() {
        return bearingSlots;
    }

    public Bearing getBearing(int slot) {
        return bearings[slot];
    }

    /** Установить подшипник в точку (ПКМ предметом). */
    public void installBearing(int slot, BearingType type) {
        bearings[slot].install(type);
    }

    /** Снять подшипник ключом. Возвращает снятый тип (для выпадения). */
    public BearingType removeBearing(int slot) {
        final BearingType t = bearings[slot].type();
        bearings[slot].remove();
        return t;
    }

    /** Сломанный подшипник: точка опустела сама (для дропа обломков). */
    public BearingType pollBrokenBearing(int slot) {
        if (bearings[slot].broken()) {
            final BearingType t = bearings[slot].type();
            bearings[slot].remove();
            return t;
        }
        return BearingType.NONE;
    }

    public LubricantState getLubricant() {
        return lubricant;
    }

    public double getMisalignmentDeg() {
        return misalignmentDeg;
    }

    public void setMisalignmentDeg(double deg) {
        this.misalignmentDeg = Math.max(0, deg);
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
