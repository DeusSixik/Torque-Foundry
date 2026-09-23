package dev.sdm.torque_foundry.physics.machine;

import dev.sdm.torque_foundry.api.debug.DebugInfoCollector;
import dev.sdm.torque_foundry.api.debug.DebugInfoProvider;
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
import java.util.Locale;
import java.util.Map;

public class MechanicalMachine implements DebugInfoProvider {

    /**
     * Стандартные секции машины в TF Inspector: Machine, Material, Thermal,
     * Bearings, Transmission. Ключи метрик совпадают с историческими id
     * ("Machine.leafpower" и т.д.) — сохранённые пины HUD остаются живыми.
     *
     * <p>Подклассы вызывают {@code super.addDebugInfo(collector)} и дописывают
     * свои секции ({@code collector.section("MyAddon")...}) — см.
     * GeneratorMachine/ChassisMachine/FlywheelMachine.
     *
     * <p>Тредовый контракт: рендер-тред (см. DebugInfoProvider).
     */
    @Override
    public void addDebugInfo(DebugInfoCollector collector) {
        debugMachineSection(collector);
        debugMaterialSection(collector);
        debugThermalSection(collector);
        debugBearingsSection(collector);
        debugTransmissionSection(collector);
    }

    /** Секция Machine: состояние, потребность, received, мощности тика. */
    private void debugMachineSection(DebugInfoCollector c) {
        final boolean alarm = workState == WorkState.JAMMED
                || workState == WorkState.INSUFFICIENT_POWER;
        c.section("Machine")
                .entryKey("Machine.state", "State", workState.name(), alarm, Float.NaN,
                        "WORKING — работает; IDLE — стоит; INSUFFICIENT — перегруз; JAMMED — клин")
                .addKey("Machine.required", "Required", fmtPower(getRequired()))
                .addKey("Machine.received", "Received", fmtPower(getReceived()))
                .addKey("Machine.netpower", "Net power", getReceived().getPower() + " W")
                .hintKey("Machine.leafpower", "Leaf power", freePower + " W",
                        "Свободная мощность узла: received минус требования детей")
                .addKey("Machine.inputs", "Inputs", fmtDirections(getInputDirections()))
                .addKey("Machine.outputs", "Outputs", fmtDirections(getOutputDirections()));

        final SimulationState sim = getSimulationState();
        c.addKey("Machine.tickpower", "Tick power",
                "recv " + sim.getReceivedWatts() + " W, children "
                        + sim.getChildrenWatts() + " W, free " + sim.getFreeWatts() + " W");
    }

    /** Секция Material: физический паспорт материала машины. */
    private void debugMaterialSection(DebugInfoCollector c) {
        final PhysicsMaterial m = getMaterial();
        c.section("Material")
                .addKey("Material.name", "Material", m.name())
                .addKey("Material.limits", "Safe RPM / T_max",
                        m.maxSafeSpeedRpm() + " RPM / " + String.format(Locale.ROOT,
                                "%.0f Nm", m.defaultMaxSafeTorqueNm()))
                .addKey("Material.elastic", "E / G / nu / rho",
                        String.format(Locale.ROOT, "%.0f GPa / %.1f GPa / %.2f / %.0f kg/m3",
                                m.youngModulusGpa(), m.shearModulusGpa(), m.poissonRatio(),
                                m.densityKgM3()))
                .addKey("Material.strength", "sig_y / tau_y / sig_u / sig-1",
                        String.format(Locale.ROOT, "%.0f / %.0f / %.0f / %.0f MPa",
                                m.yieldTensileMpa(), m.yieldShearMpa(), m.tensileStrengthMpa(),
                                m.fatigueStrengthMpa()))
                .addKey("Material.surface", "mu / HB / c / lambda",
                        String.format(Locale.ROOT, "%.2f / %.0f / %.0f J/kgK / %.1f W/mK",
                                m.frictionCoefficient(), m.hardnessHb(), m.heatCapacityJPerKgK(),
                                m.thermalConductivityWPerMK()))
                .hintKey("Material.derived", "Inertia / Friction / Mass",
                        String.format(Locale.ROOT, "%.3f / %.5f / %.2f kg",
                                m.relativeDensity(), m.viscousFriction(), m.nominalMassKg()),
                        "Игровые производные: вклад в инерцию сети, вязкое трение, масса детали");
    }

    /** Секция Thermal: температура/тепло/ресурс из SimulationState. */
    private void debugThermalSection(DebugInfoCollector c) {
        final SimulationState sim = getSimulationState();
        final PhysicsMaterial m = getMaterial();
        final double t = sim.temperatureC(m, m.nominalMassKg());
        final double overheat = sim.overheatingK(m, m.nominalMassKg());
        c.section("Thermal")
                .barAlertKey("Thermal.temp", "Temperature",
                        String.format(Locale.ROOT, "%.1f C (overheat +%.1f K)", t, overheat),
                        overheat > 60.0, (float) ((t - 20.0) / 80.0))
                .addKey("Thermal.energy", "Thermal energy",
                        String.format(Locale.ROOT, "%.1f J", sim.getThermalEnergyJ()))
                .addKey("Thermal.throughput", "Throughput",
                        String.format(Locale.ROOT, "%.1f kJ", sim.getTotalThroughputJ() / 1000.0))
                .hintKey("Thermal.overload", "Overload ticks",
                        String.valueOf(sim.getOverloadTicks()),
                        "Счётчик ресурса: тики в INSUFFICIENT/JAMMED за жизнь детали");
    }

    /** Секция Bearings: опоры/смазка/перекос — только у осевых машин. */
    private void debugBearingsSection(DebugInfoCollector c) {
        if (!bearingSlots) {
            return;
        }
        c.section("Bearings");
        for (int slot = 0; slot < bearings.length; slot++) {
            final Bearing b = bearings[slot];
            final String label = "Slot " + slot;
            final String key = "Bearings.slot" + slot;
            if (!b.present()) {
                c.addKey(key, label, b.type() + " (bare)");
            } else if (b.broken()) {
                c.barAlertKey(key, label, b.type()
                        + String.format(Locale.ROOT, " BROKEN (%.0f%%)", b.wear() * 100),
                        true, (float) b.wear());
            } else {
                c.barAlertKey(key, label, b.type() + String.format(Locale.ROOT,
                                " %.0f%% worn, rating %d RPM, friction x%.2f",
                                b.wear() * 100, b.type().rpmRating(),
                                b.frictionMultiplier(getLubricant().available())),
                        b.wear() > 0.7, (float) b.wear());
            }
        }

        final LubricantState lube = getLubricant();
        final float fill = (float) (lube.amount() / LubricantState.CAPACITY);
        c.barAlertKey("Bearings.lubricant", "Lubricant",
                lube.type() + String.format(Locale.ROOT, " %.0f/%.0f (%.0f%%)%s",
                        lube.amount(), LubricantState.CAPACITY, fill * 100.0,
                        lube.available() ? "" : " [DRY]"),
                !lube.available() && lube.type() != LubricantState.Type.NONE, fill);
        c.addKey("Bearings.alignment", "Misalignment / Friction / Wear",
                String.format(Locale.ROOT, "+%.1f deg / x%.2f / x%.2f",
                        getMisalignmentDeg(), frictionMultiplier(0), wearFactor()));
    }

    /** Секция Transmission: КПД/страгивание/холостой ход/инерция ротора. */
    private void debugTransmissionSection(DebugInfoCollector c) {
        c.section("Transmission");
        final double eta = getEfficiency();
        c.barKey("Transmission.efficiency", "Efficiency",
                eta < 1.0
                        ? String.format(Locale.ROOT, "%.0f%% (loss heats this machine)", eta * 100)
                        : "100% (lossless)",
                (float) eta);

        final long breakaway = getBreakawayTorqueRaw();
        if (breakaway > 0) {
            c.addKey("Transmission.breakaway", "Breakaway torque",
                    String.format(Locale.ROOT, "%.1f Nm", breakaway / 1000.0));
        }
        final long idle = getIdleTorqueRaw();
        if (idle > 0) {
            c.addKey("Transmission.idle", "Idle torque",
                    String.format(Locale.ROOT, "%.1f Nm", idle / 1000.0));
        }
        final double extraI = getExtraInertia();
        if (extraI > 0) {
            c.addKey("Transmission.inertia", "Rotor inertia",
                    String.format(Locale.ROOT, "+%.2f", extraI));
        }
    }

    /** Форматирование мощности для отладки (debug-качество, рендер-тред). */
    protected static String fmtPower(RotationalPower power) {
        return String.format(Locale.ROOT, "%.3f RPM, %.3f Nm, dir=%s",
                power.getSpeedRpm(), power.getTorqueNm(),
                RotationDirection.from(power.getDirection()));
    }

    /** Форматирование списка граней для отладки. */
    protected static String fmtDirections(Direction[] directions) {
        if (directions == null || directions.length == 0) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder();
        for (Direction direction : directions) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(direction.name());
        }
        return sb.toString();
    }

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

    // required С…СЂР°РЅРёРј РєР°Рє RotationalPower вЂ” РїРµСЂРµРёСЃРїРѕР»СЊР·СѓРµРј РµРіРѕ SCALE-Р»РѕРіРёРєСѓ,
    // Р° РЅРµ РґСѓР±Р»РёСЂСѓРµРј С„РѕСЂРјСѓР»С‹ РєРѕРЅРІРµСЂСЃРёРё С‚СѓС‚
    protected final RotationalPower required;
    protected final byte requiredDirection; // -1 = РЅР°РїСЂР°РІР»РµРЅРёРµ РЅРµ РІР°Р¶РЅРѕ
    protected long groupIndex = -1;
    protected int groupElementIndex = -1;

    /**
     * РЎРѕСЃС‚РѕСЏРЅРёРµ СЂР°Р±РѕС‚С‹, РІС‹С‡РёСЃР»СЏРµС‚СЃСЏ С„РёР·РёС‡РµСЃРєРёРј С‚РёРєРѕРј РіСЂСѓРїРїС‹.
     */
    protected WorkState workState = WorkState.IDLE;

    /**
     * РњРѕС‰РЅРѕСЃС‚СЊ СЃРµС‚Рё, РїРѕР»СѓС‡РµРЅРЅР°СЏ РјР°С€РёРЅРѕР№ РЅР° РїСЂРѕС€Р»РѕРј С„РёР·РёС‡РµСЃРєРѕРј С‚РёРєРµ.
     */
    protected final RotationalPower received = RotationalPower.fromRaw(0, 0);

    /**
     * РЎРІРѕР±РѕРґРЅР°СЏ РјРѕС‰РЅРѕСЃС‚СЊ СѓР·Р»Р° (РІ РІР°С‚С‚Р°С…): received РјРёРЅСѓСЃ С‚СЂРµР±РѕРІР°РЅРёСЏ РґРµС‚РµР№.
     * "РЎРєРѕР»СЊРєРѕ РѕСЃС‚Р°Р»РѕСЃСЊ РІ СЌС‚РѕРј СѓР·Р»Рµ РЅР° СЃРѕР±СЃС‚РІРµРЅРЅС‹Рµ РЅСѓР¶РґС‹".
     */
    protected long freePower;

    /**
     * РџРѕРєР°Р·Р°С‚РµР»Рё СЃРёРјСѓР»СЏС†РёРё (РјРѕС‰РЅРѕСЃС‚СЊ Р·Р° С‚РёРє, С‚РµРїР»Рѕ, СЃС‡С‘С‚С‡РёРєРё СЂРµСЃСѓСЂСЃР°).
     */
    private final SimulationState simulationState = new SimulationState();

    /**
     * РћРїРѕСЂРЅС‹Рµ С‚РѕС‡РєРё (РїРѕРґС€РёРїРЅРёРєРё) РјР°С€РёРЅС‹. РЈ РѕСЃРµРІС‹С… РјР°С€РёРЅ (РІР°Р») РёС… РґРІРµ вЂ”
     * С‚РѕСЂС†С‹ РїРѕ РѕСЃРё; Сѓ РѕСЃС‚Р°Р»СЊРЅС‹С… РјР°С€РёРЅ СЃР»РѕС‚С‹ РЅРµР°РєС‚РёРІРЅС‹ Рё РЅР° С„РёР·РёРєСѓ
     * РЅРµ РІР»РёСЏСЋС‚.
     */
    private final Bearing[] bearings = {new Bearing(), new Bearing()};

    /** Р РµР·РµСЂРІСѓР°СЂ СЃРјР°Р·РєРё (РѕР±С‰РёР№ РЅР° РјР°С€РёРЅСѓ). */
    private final LubricantState lubricant = new LubricantState();

    /**
     * РџРµСЂРµРєРѕСЃ/РґРёСЃР±Р°Р»Р°РЅСЃ РІР°Р»Р°, РіСЂР°РґСѓСЃС‹ (РёР· С‚РёСЂР° Grade РїСЂРё РєСЂР°С„С‚Рµ).
     * РњРЅРѕР¶РёС‚РµР»Рё С‚СЂРµРЅРёСЏ Рё РёР·РЅРѕСЃР°: 1 + РїРµСЂРµРєРѕСЃ Г— 0.5 / 1 + РїРµСЂРµРєРѕСЃ.
     */
    private double misalignmentDeg = 0.0;

    /** Р•СЃС‚СЊ Р»Рё Р°РєС‚РёРІРЅС‹Рµ РѕРїРѕСЂРЅС‹Рµ С‚РѕС‡РєРё (РѕСЃРµРІР°СЏ РјР°С€РёРЅР°). */
    private boolean bearingSlots = false;

    /**
     * Р›РѕРєР°Р»СЊРЅС‹Рµ РїРѕСЂС‚С‹ РјР°С€РёРЅС‹ (РІ СЃРёСЃС‚РµРјРµ РєРѕРѕСЂРґРёРЅР°С‚ Р±Р»РѕРєР° РїСЂРё РїРѕРІРѕСЂРѕС‚Рµ 0).
     * Р—Р°РїРѕР»РЅСЏРµС‚СЃСЏ РІ {@link #createDirections()} С‡РµСЂРµР· {@link #port}.
     * РњРёСЂРѕРІС‹Рµ РЅР°РїСЂР°РІР»РµРЅРёСЏ РїРѕР»СѓС‡Р°СЋС‚СЃСЏ РїРѕРІРѕСЂРѕС‚РѕРј РїРѕ {@link #facing}.
     */
    private final Map<Direction, PortRole> localPorts = new EnumMap<>(Direction.class);
    private final Map<Direction, PortRole> worldPorts = new EnumMap<>(Direction.class);
    private Direction[] inputDirections = EMPTY_DIRECTIONS;
    private Direction[] outputDirections = EMPTY_DIRECTIONS;

    /**
     * РџР°СЃСЃРёРІРЅР°СЏ РјР°С€РёРЅР° (РІР°Р»): РїСЂРёРЅРёРјР°РµС‚ РјРѕС‰РЅРѕСЃС‚СЊ РІС…РѕРґРЅС‹РјРё РіСЂР°РЅСЏРјРё
     * Рё РѕС‚РґР°С‘С‚ С‡РµСЂРµР· РґСЂСѓРіРёРµ РіСЂР°РЅРё вЂ” РЅР°РїСЂР°РІР»РµРЅРёРµ РїРѕС‚РѕРєР° РѕРїСЂРµРґРµР»СЏРµС‚СЃСЏ
     * РїРѕР»РѕР¶РµРЅРёРµРј РёСЃС‚РѕС‡РЅРёРєР°, Р° РЅРµ С„РёРєСЃРёСЂРѕРІР°РЅРЅС‹РјРё РїРѕСЂС‚Р°РјРё.
     */
    private boolean passive;

    /**
     * РћСЃСЊ РјР°С€РёРЅС‹ (РґР»СЏ РѕСЃРµРІС‹С… РјР°С€РёРЅ: РІР°Р»).
     */
    protected Direction.Axis axis = Direction.Axis.X;

    /**
     * РњР°С‚РµСЂРёР°Р» РґРµС‚Р°Р»РµР№ РјР°С€РёРЅС‹: РёРЅРµСЂС†РёСЏ (РїР»РѕС‚РЅРѕСЃС‚СЊ), С‚СЂРµРЅРёРµ, Р±РµР·РѕРїР°СЃРЅС‹Рµ РѕР±РѕСЂРѕС‚С‹.
     */
    private PhysicsMaterial material =
            PhysicsMaterials.IRON;

    /**
     * РћСЂРёРµРЅС‚Р°С†РёСЏ Р±Р»РѕРєР°: РІ РєР°РєСѓСЋ РјРёСЂРѕРІСѓСЋ СЃС‚РѕСЂРѕРЅСѓ СЃРјРѕС‚СЂРёС‚ Р»РѕРєР°Р»СЊРЅС‹Р№ NORTH.
     * РџРѕСЂС‚С‹, Р·Р°РґР°РЅРЅС‹Рµ РІ Р»РѕРєР°Р»СЊРЅС‹С… РєРѕРѕСЂРґРёРЅР°С‚Р°С…, РїРѕРІРѕСЂР°С‡РёРІР°СЋС‚СЃСЏ РІРјРµСЃС‚Рµ СЃ РЅРµР№.
     */
    private Direction facing = Direction.NORTH;

    /**
     * РџРѕР·РёС†РёСЏ Р±Р»РѕРєР°-РІР»Р°РґРµР»СЊС†Р° (null РґР»СЏ headless-РјР°С€РёРЅ РІРЅРµ РјРёСЂР°).
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
     * РСЃС‚РѕС‡РЅРёРє: РѕС‚РґР°С‘С‚ СЌРЅРµСЂРіРёСЋ (РІС…РѕРґРѕРІ РЅРµС‚, РІС‹С…РѕРґС‹ РµСЃС‚СЊ).
     */
    public boolean isSource() {
        return inputDirections.length == 0 && outputDirections.length > 0;
    }

    /**
     * Р’С‹РґР°РІР°РµРјР°СЏ РјРѕС‰РЅРѕСЃС‚СЊ РёСЃС‚РѕС‡РЅРёРєР°. РќРµ РёСЃС‚РѕС‡РЅРёРє вЂ” null.
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
     * РњРѕС‰РЅРѕСЃС‚СЊ СЃРµС‚Рё, РїРѕР»СѓС‡РµРЅРЅР°СЏ РјР°С€РёРЅРѕР№ (Р·Р°РїРѕР»РЅСЏРµС‚СЃСЏ С„РёР·РёС‡РµСЃРєРёРј С‚РёРєРѕРј).
     */
    public RotationalPower getReceived() {
        return received;
    }

    public void setReceived(RotationalPower power) {
        this.received.copyFrom(power);
    }

    /**
     * РЎРІРѕР±РѕРґРЅР°СЏ РјРѕС‰РЅРѕСЃС‚СЊ СѓР·Р»Р° РІ РІР°С‚С‚Р°С… (received в€’ С‚СЂРµР±РѕРІР°РЅРёСЏ РґРµС‚РµР№).
     */
    public long getFreePower() {
        return freePower;
    }

    public void setFreePower(long watts) {
        this.freePower = watts;
    }

    /**
     * РўСЂР°РЅСЃС„РѕСЂРјР°С†РёСЏ РјРѕС‰РЅРѕСЃС‚Рё РїСЂРё РїРµСЂРµРґР°С‡Рµ С‡РµСЂРµР· РјР°С€РёРЅСѓ РІ РіСЂР°РЅСЊ outputSide.
     * РљРѕСЂРѕР±РєРё РїРµСЂРµРґР°С‡/РїР»Р°РЅРµС‚Р°СЂРєРё/СЂРµРјРЅРё РјРµРЅСЏСЋС‚ СЃРѕРѕС‚РЅРѕС€РµРЅРёРµ RPM/Nm,
     * РєРѕРЅРёС‡РµСЃРєРёРµ РїРѕРІРѕСЂР°С‡РёРІР°СЋС‚ РѕСЃСЊ Рё С‚.Рґ. РџРѕ СѓРјРѕР»С‡Р°РЅРёСЋ вЂ” passthrough.
     *
     * @param input      РјРѕС‰РЅРѕСЃС‚СЊ РЅР° РІС…РѕРґРµ РјР°С€РёРЅС‹
     * @param outputSide РіСЂР°РЅСЊ, С‡РµСЂРµР· РєРѕС‚РѕСЂСѓСЋ РјРѕС‰РЅРѕСЃС‚СЊ РїРѕРєРёРґР°РµС‚ РјР°С€РёРЅСѓ
     */
    public RotationalPower transform(RotationalPower input, Direction outputSide) {
        return input;
    }

    /**
     * Р‘Р°Р»Р°РЅСЃРѕРІС‹Р№ С…СѓРє РєРѕРЅС†Р° С„РёР·РёС‡РµСЃРєРѕРіРѕ С‚РёРєР°:Received вЂ” С‡С‚Рѕ РїСЂРёС€Р»Рѕ РІ СѓР·РµР»,
     * childrenWatts вЂ” С‡С‚Рѕ СЃСѓРјРјР°СЂРЅРѕ С‚СЂРµР±СѓСЋС‚ РґРµС‚Рё (РІ РІР°С‚С‚Р°С…).
     * Р­РЅРµСЂРіРѕР±СѓС„РµСЂС‹ (РјР°С…РѕРІРёРє) Р·РґРµСЃСЊ Р·Р°СЂСЏР¶Р°СЋС‚СЃСЏ РѕС‚ РёР·Р»РёС€РєР° Рё РїРѕРєСЂС‹РІР°СЋС‚ РґРµС„РёС†РёС‚.
     * Р’С‹Р·С‹РІР°РµС‚СЃСЏ РІ РїРѕС‚РѕРєРµ С„РёР·РёРєРё.
     */
    public void onNetworkTick(long receivedWatts, long childrenWatts) {
    }

    /**
     * РљРџР” РїРµСЂРµРґР°С‡Рё С‡РµСЂРµР· РјР°С€РёРЅСѓ (1.0 вЂ” Р±РµР· РїРѕС‚РµСЂСЊ). РџРѕС‚РµСЂСЏРЅРЅР°СЏ РјРѕС‰РЅРѕСЃС‚СЊ
     * P_loss = P_in В· (1-О·) СѓС…РѕРґРёС‚ РІ С‚РµРїР»Рѕ СѓР·Р»Р° (SimulationState).
     */
    public double getEfficiency() {
        return 1.0;
    }

    /**
     * РњРѕРјРµРЅС‚ СЃС‚СЂР°РіРёРІР°РЅРёСЏ (milli-Nm): РІС…РѕРґРЅРѕРіРѕ РјРѕРјРµРЅС‚Р° РјРµРЅСЊС€Рµ вЂ” РјР°С€РёРЅР°
     * РЅРµ С‚СЂРѕРЅРµС‚СЃСЏ Рё РєР»РёРЅРёС‚ СЃРµС‚СЊ. 0 вЂ” СЃС‚СЂР°РіРёРІР°РЅРёРµ РЅРµ С‚СЂРµР±СѓРµС‚СЃСЏ.
     */
    public long getBreakawayTorqueRaw() {
        return 0;
    }

    /**
     * РџРѕС‚РµСЂРё С…РѕР»РѕСЃС‚РѕРіРѕ С…РѕРґР° (milli-Nm): РµСЃС‚ РјРѕРјРµРЅС‚ СЃРµС‚Рё, РїРѕРєР° РјР°С€РёРЅР°
     * РІСЂР°С‰Р°РµС‚СЃСЏ (С‚СЂРµРЅРёРµ СЂР°Р±РѕС‡РµРіРѕ РѕСЂРіР°РЅР°, РІРµРЅС‚РёР»СЏС†РёСЏ). 0 вЂ” РЅРµС‚.
     */
    public long getIdleTorqueRaw() {
        return 0;
    }

    /**
     * Р”РѕРїРѕР»РЅРёС‚РµР»СЊРЅР°СЏ РёРЅРµСЂС†РёСЏ СЂРѕС‚РѕСЂР°/СЂР°Р±РѕС‡РµРіРѕ РѕСЂРіР°РЅР° (РІ РµРґРёРЅРёС†Р°С… РёРЅРµСЂС†РёРё
     * СЃРµС‚Рё, РєР°Рє getInertia()): С‚СЏР¶С‘Р»С‹Р№ Р±Р°СЂР°Р±Р°РЅ Р·Р°РјРµРґР»СЏРµС‚ СЂР°Р·РіРѕРЅ СЃРµС‚Рё.
     */
    public double getExtraInertia() {
        return 0.0;
    }

    /**
     * РўРёРє СЂР°Р±РѕС‚С‹ РРЎРўРћР§РќРРљРђ: РїРѕС‚РµСЂРё РїСЂРµРѕР±СЂР°Р·РѕРІР°РЅРёСЏ РіСЂРµСЋС‚ СЂРѕС‚РѕСЂ,
     * РїРµСЂРµРіСЂРµРІ СѓСЂРµР·Р°РµС‚ РІС‹РґР°С‡Сѓ (С‚РµРїР»РѕРІРѕР№ derate). Р’С‹Р·С‹РІР°РµС‚СЃСЏ РІ С„Р°Р·Рµ A
     * РґР»СЏ РєР°Р¶РґРѕР№ РјР°С€РёРЅС‹ СЃ output != null.
     *
     * @param outputWatts РїР°СЃРїРѕСЂС‚РЅР°СЏ РјРѕС‰РЅРѕСЃС‚СЊ РёСЃС‚РѕС‡РЅРёРєР° РЅР° С‚РµРєСѓС‰РёС… РѕР±РѕСЂРѕС‚Р°С…, Р’С‚
     */
    public void onSourceTick(long outputWatts) {
    }

    /**
     * РњРЅРѕР¶РёС‚РµР»СЊ РїР°СЃРїРѕСЂС‚РЅРѕРіРѕ РјРѕРјРµРЅС‚Р° РёСЃС‚РѕС‡РЅРёРєР° (С‚РµРїР»РѕРІРѕР№ derate, 0..1].
     * РЎС‡РёС‚Р°РµС‚СЃСЏ РІ {@link #onSourceTick}, РїСЂРёРјРµРЅСЏРµС‚СЃСЏ РІ С„Р°Р·Рµ A.
     */
    public double getOutputFactor() {
        return 1.0;
    }

    /**
     * РџРѕРєР°Р·Р°С‚РµР»Рё СЃРёРјСѓР»СЏС†РёРё (РјРѕС‰РЅРѕСЃС‚СЊ, С‚РµРїР»Рѕ, СЂРµСЃСѓСЂСЃ) вЂ” РјСѓС‚Р°Р±РµР»СЊРЅР°СЏ СЃС‚СЂСѓРєС‚СѓСЂР°,
     * Р¶РёРІС‘С‚ РІ РјР°С€РёРЅРµ РІСЃС‘ РІСЂРµРјСЏ СЃСѓС‰РµСЃС‚РІРѕРІР°РЅРёСЏ. РўРµРїР»Рѕ/РґРµРіСЂР°РґР°С†РёСЏ/Р±СѓС„РµСЂС‹ вЂ” С‡РёС‚Р°Р№
     * Рё РїРёС€Рё СЃСЋРґР° РІРјРµСЃС‚Рѕ РЅРѕРІС‹С… РїРѕР»РµР№ РјР°С€РёРЅС‹.
     */
    public SimulationState getSimulationState() {
        return simulationState;
    }

    /**
     * РЎРёРјСѓР»СЏС†РёРѕРЅРЅС‹Р№ С‚РёРє РјР°С€РёРЅС‹: РЅР°РіСЂРµРІ С‚СЂРµРЅРёРµРј + РїР°СЃСЃРёРІРЅРѕРµ РѕС…Р»Р°Р¶РґРµРЅРёРµ,
     * РёР·РЅРѕСЃ РїРѕРґС€РёРїРЅРёРєРѕРІ, СЂР°СЃС…РѕРґ СЃРјР°Р·РєРё. Р’С‹Р·С‹РІР°РµС‚СЃСЏ РєРѕРЅРІРµР№РµСЂРѕРј РІ С„Р°Р·Рµ
     * РґРёРЅР°РјРёРєРё РґР»СЏ РІСЃРµС… РІСЂР°С‰Р°СЋС‰РёС…СЃСЏ РјР°С€РёРЅ. РџРµСЂРµРѕРїСЂРµРґРµР»СЏРµС‚СЃСЏ РґР»СЏ СЃРІРѕРµР№
     * С‚РµРїР»РѕС„РёР·РёРєРё (РїРµС‡Рё, С‚РѕСЂРјРѕР·Р° Рё С‚.Рї.).
     *
     * @param frictionTorqueMilliNm РјРѕРјРµРЅС‚ С‚СЂРµРЅРёСЏ СЌС‚РѕР№ РјР°С€РёРЅС‹, milli-Nm
     * @param speedMilliRpm         РѕР±РѕСЂРѕС‚С‹, milli-RPM
     */
    public void onSimulationTick(long frictionTorqueMilliNm, long speedMilliRpm) {
        final double massKg = material.nominalMassKg();
        simulationState.addFrictionHeat(frictionTorqueMilliNm, speedMilliRpm);
        simulationState.coolTick(material, massKg);

        // РР·РЅРѕСЃ РѕРїРѕСЂ + СЂР°СЃС…РѕРґ СЃРјР°Р·РєРё (С‚РѕР»СЊРєРѕ РѕСЃРµРІС‹Рµ РјР°С€РёРЅС‹ СЃРѕ СЃР»РѕС‚Р°РјРё)
        if (bearingSlots) {
            final double wf = wearFactor();
            bearings[0].wearTick(speedMilliRpm, wf);
            bearings[1].wearTick(speedMilliRpm, wf);

            int lubricatedCount = 0;
            for (Bearing b : bearings) {
                // Р—Р°РєСЂС‹С‚С‹Р№ С€Р°СЂРёРєРѕРІС‹Р№ СЃРјР°Р·РєРё РЅРµ С‚СЂРµР±СѓРµС‚
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
     * РњР°С€РёРЅР° СЏРІР»СЏРµС‚СЃСЏ РїСЂРѕС…РѕРґРЅС‹Рј СЃРµРіРјРµРЅС‚РѕРј РІР°Р»Р° (РїРѕР»РЅРѕС†РµРЅРЅС‹Р№ РІР°Р»-Р±Р»РѕРє
     * РёР»Рё РІР°Р»-РІСЃС‚Р°РІРєР° С€Р°СЃСЃРё): РёР·РЅРѕСЃ РїРѕ РѕР±РѕСЂРѕС‚Р°Рј/РјРѕРјРµРЅС‚Сѓ РїСЂРёРјРµРЅСЏРµС‚СЃСЏ.
     */
    public boolean isShaftSegment() {
        return false;
    }

    protected void setPassive(boolean passive) {
        this.passive = passive;
    }

    /**
     * РњР°С€РёРЅР°-Р±СѓС„РµСЂ (РјР°С…РѕРІРёРє): РїРѕРєСЂС‹РІР°РµС‚ РїРёРєРѕРІС‹Р№ РґРµС„РёС†РёС‚ РјРѕРјРµРЅС‚Р° РёР· СЃРІРѕРµРіРѕ
     * Р·Р°РїР°СЃР° вЂ” РїРµСЂРµРіСЂСѓР·РєР° РЅРµ РїРµСЂРµРґР°С‘С‚СЃСЏ РІРІРµСЂС… РїРѕ СЃРµС‚Рё, РїРѕРєР° РµСЃС‚СЊ СЂРµР·РµСЂРІ.
     */
    public boolean coversDeficitFromBuffer() {
        return false;
    }

    /**
     * Р•СЃС‚СЊ Р»Рё СЃРµР№С‡Р°СЃ СЂРµР·РµСЂРІ Р±СѓС„РµСЂР° (РґР»СЏ РјР°С€РёРЅ СЃ coversDeficitFromBuffer).
     */
    public boolean hasBufferReserve() {
        return false;
    }

    public Direction.Axis getAxis() {
        return axis;
    }

    /**
     * РћСЃСЊ РёР· blockstate (РІР°Р» Рё С‚.Рї.). РџР°СЃСЃРёРІРЅС‹Рµ РјР°С€РёРЅС‹ РїРµСЂРµРѕРїСЂРµРґРµР»СЏСЋС‚
     * Рё РїРµСЂРµСЃС‚СЂР°РёРІР°СЋС‚ РїРѕСЂС‚С‹.
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
     * РџСЂРёРІРµРґС‘РЅРЅР°СЏ РёРЅРµСЂС†РёСЏ РјР°С€РёРЅС‹ (РІРєР»Р°Рґ РІ СЂР°Р·РіРѕРЅ/С‚РѕСЂРјРѕР¶РµРЅРёРµ СЃРµС‚Рё):
     * РїР»РѕС‚РЅРѕСЃС‚СЊ РјР°С‚РµСЂРёР°Р»Р°, РЅРѕСЂРјРёСЂРѕРІР°РЅРЅР°СЏ РѕС‚ СЃС‚Р°Р»Рё. Р’ Р±СѓРґСѓС‰РµРј вЂ” РѕР±СЉС‘Рј Рё
     * СЂР°Р·РјРµСЂС‹ РґРµС‚Р°Р»РµР№ (СЂРµР°Р»СЊРЅС‹Р№ РјРѕРјРµРЅС‚ РёРЅРµСЂС†РёРё).
     */
    public double getInertia() {
        return material.relativeDensity();
    }

    /**
     * РњРѕРјРµРЅС‚ С‚СЂРµРЅРёСЏ РјР°С€РёРЅС‹ РїСЂРё Р·Р°РґР°РЅРЅС‹С… РѕР±РѕСЂРѕС‚Р°С… (milli-Nm):
     * РІСЏР·РєРѕРµ С‚СЂРµРЅРёРµ РёР· СЂРµР°Р»СЊРЅРѕРіРѕ РєРѕСЌС„С„РёС†РёРµРЅС‚Р° Ој СЃ РјРЅРѕР¶РёС‚РµР»СЏРјРё РѕРїРѕСЂ
     * (РїРѕРґС€РёРїРЅРёРєРё, СЃСѓС…РѕР№ С…РѕРґ, РїРµСЂРµРєРѕСЃ). РњРёРЅРёРјСѓРј 1 milli-Nm вЂ”
     * С‡С‚РѕР±С‹ СЃРµС‚СЊ РІСЃРµРіРґР° РѕСЃС‚Р°РЅР°РІР»РёРІР°Р»Р°СЃСЊ С‚СЂРµРЅРёРµРј.
     */
    public long getFrictionTorque(long speedRaw) {
        return PhysicsMath.viscousFrictionTorque(
                material.viscousFriction() * frictionMultiplier(speedRaw), speedRaw);
    }

    /**
     * РњРЅРѕР¶РёС‚РµР»СЊ С‚СЂРµРЅРёСЏ СѓР·Р»Р° РѕС‚ РѕРїРѕСЂ Рё РїРµСЂРµРєРѕСЃР°: РїСЂРѕРёР·РІРµРґРµРЅРёРµ РјРЅРѕР¶РёС‚РµР»РµР№
     * РѕРїРѕСЂРЅС‹С… С‚РѕС‡РµРє Г— РїРµСЂРµРєРѕСЃ. Р‘РµР· Р°РєС‚РёРІРЅС‹С… СЃР»РѕС‚РѕРІ вЂ” С‚РѕР»СЊРєРѕ РїРµСЂРµРєРѕСЃ.
     */
    public double frictionMultiplier(long speedRaw) {
        double m = 1.0;
        if (bearingSlots) {
            final boolean lubed = lubricant.available();
            for (Bearing b : bearings) {
                m *= b.frictionMultiplier(lubed);
            }
        }
        // РџРµСЂРµРєРѕСЃ: РґРёСЃР±Р°Р»Р°РЅСЃ РјРµС€Р°РµС‚ РІСЂР°С‰РµРЅРёСЋ
        m *= 1.0 + misalignmentDeg * 0.5;
        return m;
    }

    /**
     * РњРЅРѕР¶РёС‚РµР»СЊ РёР·РЅРѕСЃР° СѓР·Р»Р° (РїРѕРґС€РёРїРЅРёРєРё): РїРµСЂРµРєРѕСЃ СѓСЃРєРѕСЂСЏРµС‚,
     * СЃСѓС…РѕР№ С…РѕРґ СѓСЃРєРѕСЂСЏРµС‚ РІС‚СЂРѕРµ вЂ” РЅРѕ С‚РѕР»СЊРєРѕ РѕРїРѕСЂР°Рј, С‚СЂРµР±СѓСЋС‰РёРј СЃРјР°Р·РєРё.
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
                w *= 3.0; // РЅР° СЃСѓС…СѓСЋ РёР·РЅРѕСЃ РІС‚СЂРѕРµ Р±С‹СЃС‚СЂРµРµ
            }
        }
        return w;
    }

    // --- РћРїРѕСЂС‹ Рё СЃРјР°Р·РєР° ---

    /** РђРєС‚РёРІРёСЂСѓРµС‚ РѕРїРѕСЂРЅС‹Рµ СЃР»РѕС‚С‹ (РѕСЃРµРІР°СЏ РјР°С€РёРЅР°: РІР°Р»). */
    protected void enableBearingSlots() {
        this.bearingSlots = true;
    }

    public boolean hasBearingSlots() {
        return bearingSlots;
    }

    public Bearing getBearing(int slot) {
        return bearings[slot];
    }

    /** РЈСЃС‚Р°РЅРѕРІРёС‚СЊ РїРѕРґС€РёРїРЅРёРє РІ С‚РѕС‡РєСѓ (РџРљРњ РїСЂРµРґРјРµС‚РѕРј). */
    public void installBearing(int slot, BearingType type) {
        bearings[slot].install(type);
    }

    /** РЎРЅСЏС‚СЊ РїРѕРґС€РёРїРЅРёРє РєР»СЋС‡РѕРј. Р’РѕР·РІСЂР°С‰Р°РµС‚ СЃРЅСЏС‚С‹Р№ С‚РёРї (РґР»СЏ РІС‹РїР°РґРµРЅРёСЏ). */
    public BearingType removeBearing(int slot) {
        final BearingType t = bearings[slot].type();
        bearings[slot].remove();
        return t;
    }

    /** РЎР»РѕРјР°РЅРЅС‹Р№ РїРѕРґС€РёРїРЅРёРє: С‚РѕС‡РєР° РѕРїСѓСЃС‚РµР»Р° СЃР°РјР° (РґР»СЏ РґСЂРѕРїР° РѕР±Р»РѕРјРєРѕРІ). */
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
     * РћСЂРёРµРЅС‚Р°С†РёСЏ Р±Р»РѕРєР° РёР· blockstate (FACING): РїРѕРІРѕСЂР°С‡РёРІР°РµС‚ РїРѕСЂС‚С‹.
     */
    public void setFacing(Direction facing) {
        this.facing = facing;
        rebuildWorldPorts();
    }

    // --- РџРѕСЂС‚С‹ ---

    /**
     * Р РµРіРёСЃС‚СЂРёСЂСѓРµС‚ РїРѕСЂС‚ РІ Р›РћРљРђР›Р¬РќР«РҐ РєРѕРѕСЂРґРёРЅР°С‚Р°С… (РїСЂРё РїРѕРІРѕСЂРѕС‚Рµ Р±Р»РѕРєР° 0).
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
     * РџСЂСЏРјР°СЏ СѓСЃС‚Р°РЅРѕРІРєР° РњРР РћР’РћР“Рћ РїРѕСЂС‚Р° (РјРёРЅСѓСЏ РїРѕРІРѕСЂРѕС‚) вЂ” РґР»СЏ РѕСЃРµРІС‹С… РјР°С€РёРЅ (РІР°Р»).
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
     * INPUT-РіСЂР°РЅСЊ (СѓС‡РёС‚С‹РІР°РµС‚СЃСЏ Рё IN_OUT вЂ” РїР°СЃСЃРёРІРЅС‹Р№ РїСЂРѕС…РѕРґРЅРѕР№ РїРѕСЂС‚).
     */
    public boolean isInputSide(Direction side) {
        final PortRole role = worldPorts.get(side);
        return role == PortRole.INPUT || role == PortRole.IN_OUT;
    }

    /**
     * OUTPUT-РіСЂР°РЅСЊ (СѓС‡РёС‚С‹РІР°РµС‚СЃСЏ Рё IN_OUT).
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
     * РџРѕРІРѕСЂРѕС‚ Р»РѕРєР°Р»СЊРЅРѕРіРѕ РЅР°РїСЂР°РІР»РµРЅРёСЏ РІ РјРёСЂРѕРІРѕРµ РїРѕ РѕСЂРёРµРЅС‚Р°С†РёРё Р±Р»РѕРєР°
     * (РІСЂР°С‰РµРЅРёРµ РІРѕРєСЂСѓРі Y: Р»РѕРєР°Р»СЊРЅС‹Р№ NORTH СЃРјРѕС‚СЂРёС‚ РІ СЃС‚РѕСЂРѕРЅСѓ facing).
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

    // --- РЎРѕРІРјРµСЃС‚РёРјРѕСЃС‚СЊ (РёСЃРїРѕР»СЊР·СѓРµС‚СЃСЏ РјРµРЅРµРґР¶РµСЂРѕРј/С„РёР·РёРєРѕР№/РѕРІРµСЂР»РµРµРј) ---

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
