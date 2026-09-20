package dev.sdm.torque_foundry.debug.physics;

import dev.sdm.torque_foundry.core.machine.ChassisMachine;
import dev.sdm.torque_foundry.core.machine.FlywheelMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.Bearing;
import dev.sdm.torque_foundry.physics.machine.LubricantState;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.machine.SimulationState;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Снепшот всех показателей механического блока для отладочного инспектора.
 * Собирается раз за кадр из живой машины (клиент видит серверный снепшот
 * через ClientGroupCache) и раскладывается по секциям. Каждая метрика имеет
 * стабильный id вида "section.key" — он же ключ закрепления в PinStore.
 */
public final class InspectorSnapshot {

    /** Одна строка-метрика инспектора. */
    public static final class Metric {
        private final String id;
        private final String section;
        private final String label;
        private final String value;
        private final boolean alert;
        private final float barFraction;
        private final String hint;

        private Metric(String id, String section, String label, String value,
                       boolean alert, float barFraction, String hint) {
            this.id = id;
            this.section = section;
            this.label = label;
            this.value = value;
            this.alert = alert;
            this.barFraction = barFraction;
            this.hint = hint;
        }

        /** Простая метрика без бара и подсветки. */
        public static Metric of(String section, String key, String label, String value) {
            return new Metric(section + "." + key, section, label, value,
                    false, Float.NaN, null);
        }

        /** Метрика с подсветкой-тревогой. */
        public static Metric alert(String section, String key, String label, String value,
                                   boolean alert) {
            return new Metric(section + "." + key, section, label, value,
                    alert, Float.NaN, null);
        }

        /** Метрика с прогресс-баром 0..1. */
        public static Metric bar(String section, String key, String label, String value,
                                 float barFraction) {
            return new Metric(section + "." + key, section, label, value,
                    false, barFraction, null);
        }

        /** Метрика с баром и подсветкой. */
        public static Metric barAlert(String section, String key, String label, String value,
                                      boolean alert, float barFraction) {
            return new Metric(section + "." + key, section, label, value,
                    alert, barFraction, null);
        }

        /** Метрика с подсказкой (tooltip). */
        public static Metric hint(String section, String key, String label, String value,
                                  String hint) {
            return new Metric(section + "." + key, section, label, value,
                    false, Float.NaN, hint);
        }

        /** Метрика с подсветкой и подсказкой. */
        public static Metric alertHint(String section, String key, String label, String value,
                                       boolean alert, String hint) {
            return new Metric(section + "." + key, section, label, value,
                    alert, Float.NaN, hint);
        }

        /** Метрика с баром и подсказкой. */
        public static Metric barHint(String section, String key, String label, String value,
                                     float barFraction, String hint) {
            return new Metric(section + "." + key, section, label, value,
                    false, barFraction, hint);
        }

        /** Полная форма: подсветка + бар + подсказка. */
        public static Metric full(String section, String key, String label, String value,
                                  boolean alert, float barFraction, String hint) {
            return new Metric(section + "." + key, section, label, value,
                    alert, barFraction, hint);
        }

        /** Стабильный id "section.key" для pin. */
        public String id() {
            return id;
        }

        /** Секция (Group, Machine, Material, ...). */
        public String section() {
            return section;
        }

        /** Подпись в UI. */
        public String label() {
            return label;
        }

        /** Отформатированное значение. */
        public String value() {
            return value;
        }

        /** Цветное ли значение (предупреждение/клин). */
        public boolean alert() {
            return alert;
        }

        /** Доля 0..1 для прогресс-бара, NaN — без бара. */
        public float barFraction() {
            return barFraction;
        }

        /** Подсказка для tooltip. */
        public String hint() {
            return hint;
        }

        public boolean hasBar() {
            return !Float.isNaN(barFraction);
        }
    }

    /** Секция метрик с заголовком. */
    public record Section(String name, List<Metric> metrics) {
    }

    private final String title;
    private final String subtitle;
    private final List<Section> sections;

    private InspectorSnapshot(String title, String subtitle, List<Section> sections) {
        this.title = title;
        this.subtitle = subtitle;
        this.sections = sections;
    }

    public String title() {
        return title;
    }

    public String subtitle() {
        return subtitle;
    }

    public List<Section> sections() {
        return sections;
    }

    /** Все метрики плоским списком (для поиска и pinned HUD). */
    public List<Metric> allMetrics() {
        final List<Metric> all = new ArrayList<>();
        for (Section section : sections) {
            all.addAll(section.metrics());
        }
        return all;
    }

    public Metric findById(String id) {
        for (Section section : sections) {
            for (Metric metric : section.metrics()) {
                if (metric.id().equals(id)) {
                    return metric;
                }
            }
        }
        return null;
    }

    // --- Сборка ---

    public static InspectorSnapshot collect(
            String blockKey, String blockPos, String machineClass,
            String groupLine, String membersLine, String netSpeedLine, boolean groupUnknown,
            MechanicalMachine machine) {
        final List<Section> sections = new ArrayList<>();

        sections.add(groupSection(groupLine, membersLine, netSpeedLine, groupUnknown));
        sections.add(machineSection(machine));
        sections.add(materialSection(machine));
        sections.add(thermalSection(machine));

        final Section bearings = bearingsSection(machine);
        if (bearings != null) {
            sections.add(bearings);
        }
        final Section source = sourceSection(machine);
        if (source != null) {
            sections.add(source);
        }
        final Section chassis = chassisSection(machine);
        if (chassis != null) {
            sections.add(chassis);
        }
        final Section flywheel = flywheelSection(machine);
        if (flywheel != null) {
            sections.add(flywheel);
        }
        sections.add(transmissionSection(machine));

        return new InspectorSnapshot(
                "TF Inspector — " + machineClass,
                blockKey + " @ " + blockPos,
                sections);
    }

    private static Section groupSection(
            String groupLine, String membersLine, String netSpeedLine, boolean unknown) {
        final List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.alert("Group", "id", "Group", groupLine, unknown));
        if (membersLine != null) {
            metrics.add(Metric.of("Group", "members", "Members", membersLine));
        }
        if (netSpeedLine != null) {
            metrics.add(Metric.of("Group", "netspeed", "Network speed", netSpeedLine));
        }
        return new Section("Group", metrics);
    }

    private static Section machineSection(MechanicalMachine machine) {
        final List<Metric> metrics = new ArrayList<>();
        final boolean jammed = machine.getWorkState() == dev.sdm.torque_foundry.physics.WorkState.JAMMED;
        final boolean insufficient =
                machine.getWorkState() == dev.sdm.torque_foundry.physics.WorkState.INSUFFICIENT_POWER;
        metrics.add(Metric.full("Machine", "state", "State",
                machine.getWorkState().name(), jammed || insufficient,
                Float.NaN, "WORKING — работает; IDLE — стоит; INSUFFICIENT — перегруз; JAMMED — клин"));
        metrics.add(Metric.of("Machine", "required", "Required",
                formatPower(machine.getRequired())));
        metrics.add(Metric.of("Machine", "received", "Received",
                formatPower(machine.getReceived())));
        metrics.add(Metric.of("Machine", "netpower", "Net power",
                machine.getReceived().getPower() + " W"));
        metrics.add(Metric.hint("Machine", "leafpower", "Leaf power",
                machine.getFreePower() + " W",
                "Свободная мощность узла: received минус требования детей"));
        metrics.add(Metric.of("Machine", "inputs", "Inputs",
                formatDirections(machine.getInputDirections())));
        metrics.add(Metric.of("Machine", "outputs", "Outputs",
                formatDirections(machine.getOutputDirections())));

        final SimulationState sim = machine.getSimulationState();
        metrics.add(Metric.of("Machine", "tickpower", "Tick power",
                "recv " + sim.getReceivedWatts() + " W, children "
                        + sim.getChildrenWatts() + " W, free " + sim.getFreeWatts() + " W"));
        return new Section("Machine", metrics);
    }

    private static Section materialSection(MechanicalMachine machine) {
        final PhysicsMaterial m = machine.getMaterial();
        final List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.of("Material", "name", "Material", m.name()));
        metrics.add(Metric.of("Material", "limits", "Safe RPM / T_max",
                m.maxSafeSpeedRpm() + " RPM / " + String.format(Locale.ROOT, "%.0f Nm",
                        m.defaultMaxSafeTorqueNm())));
        metrics.add(Metric.of("Material", "elastic", "E / G / nu / rho",
                String.format(Locale.ROOT, "%.0f GPa / %.1f GPa / %.2f / %.0f kg/m3",
                        m.youngModulusGpa(), m.shearModulusGpa(), m.poissonRatio(),
                        m.densityKgM3())));
        metrics.add(Metric.of("Material", "strength", "sig_y / tau_y / sig_u / sig-1",
                String.format(Locale.ROOT, "%.0f / %.0f / %.0f / %.0f MPa",
                        m.yieldTensileMpa(), m.yieldShearMpa(), m.tensileStrengthMpa(),
                        m.fatigueStrengthMpa())));
        metrics.add(Metric.of("Material", "surface", "mu / HB / c / lambda",
                String.format(Locale.ROOT, "%.2f / %.0f / %.0f J/kgK / %.1f W/mK",
                        m.frictionCoefficient(), m.hardnessHb(), m.heatCapacityJPerKgK(),
                        m.thermalConductivityWPerMK())));
        metrics.add(Metric.hint("Material", "derived", "Inertia / Friction / Mass",
                String.format(Locale.ROOT, "%.3f / %.5f / %.2f kg",
                        m.relativeDensity(), m.viscousFriction(), m.nominalMassKg()),
                "Игровые производные: вклад в инерцию сети, вязкое трение, масса детали"));
        return new Section("Material", metrics);
    }

    private static Section thermalSection(MechanicalMachine machine) {
        final SimulationState sim = machine.getSimulationState();
        final PhysicsMaterial m = machine.getMaterial();
        final double t = sim.temperatureC(m, m.nominalMassKg());
        final double overheat = sim.overheatingK(m, m.nominalMassKg());
        final List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.barAlert("Thermal", "temp", "Temperature",
                String.format(Locale.ROOT, "%.1f C (overheat +%.1f K)", t, overheat),
                overheat > 60.0, clamp01((float) ((t - 20.0) / 80.0))));
        metrics.add(Metric.of("Thermal", "energy", "Thermal energy",
                String.format(Locale.ROOT, "%.1f J", sim.getThermalEnergyJ())));
        metrics.add(Metric.of("Thermal", "throughput", "Throughput",
                String.format(Locale.ROOT, "%.1f kJ", sim.getTotalThroughputJ() / 1000.0)));
        metrics.add(Metric.hint("Thermal", "overload", "Overload ticks",
                String.valueOf(sim.getOverloadTicks()),
                "Счётчик ресурса: тики в INSUFFICIENT/JAMMED за жизнь детали"));
        return new Section("Thermal", metrics);
    }

    private static Section bearingsSection(MechanicalMachine machine) {
        if (!machine.hasBearingSlots()) {
            return null;
        }
        final List<Metric> metrics = new ArrayList<>();
        for (int slot = 0; slot < 2; slot++) {
            final Bearing b = machine.getBearing(slot);
            final String label = "Slot " + slot;
            if (!b.present()) {
                metrics.add(Metric.of("Bearings", "slot" + slot, label, b.type() + " (bare)"));
            } else if (b.broken()) {
                metrics.add(Metric.barAlert("Bearings", "slot" + slot, label,
                        b.type() + String.format(Locale.ROOT, " BROKEN (%.0f%%)", b.wear() * 100),
                        true, (float) b.wear()));
            } else {
                metrics.add(Metric.barAlert("Bearings", "slot" + slot, label,
                        b.type() + String.format(Locale.ROOT,
                                " %.0f%% worn, rating %d RPM, friction x%.2f",
                                b.wear() * 100, b.type().rpmRating(),
                                b.frictionMultiplier(machine.getLubricant().available())),
                        b.wear() > 0.7, (float) b.wear()));
            }
        }

        final LubricantState lube = machine.getLubricant();
        final float fill = (float) (lube.amount() / LubricantState.CAPACITY);
        metrics.add(Metric.barAlert("Bearings", "lubricant", "Lubricant",
                lube.type() + String.format(Locale.ROOT, " %.0f/%.0f (%.0f%%)%s",
                        lube.amount(), LubricantState.CAPACITY, fill * 100.0,
                        lube.available() ? "" : " [DRY]"),
                !lube.available() && lube.type() != LubricantState.Type.NONE,
                clamp01(fill)));

        metrics.add(Metric.of("Bearings", "alignment", "Misalignment / Friction / Wear",
                String.format(Locale.ROOT, "+%.1f deg / x%.2f / x%.2f",
                        machine.getMisalignmentDeg(),
                        machine.frictionMultiplier(0),
                        machine.wearFactor())));
        return new Section("Bearings", metrics);
    }

    private static Section sourceSection(MechanicalMachine machine) {
        if (!(machine instanceof GeneratorMachine generator)) {
            return null;
        }
        final List<Metric> metrics = new ArrayList<>();
        final RotationalPower out = generator.getOutput();
        metrics.add(Metric.of("Source", "rated", "Rated output", formatPower(out)));
        final double factor = generator.getOutputFactor();
        metrics.add(Metric.full("Source", "derate", "Thermal derate",
                String.format(Locale.ROOT, "%.0f%%", factor * 100)
                        + (factor < 1.0 ? " DERATED (overheated)" : ""),
                factor < 1.0, (float) factor,
                "Перегрев режет паспортный момент: потери P(1-eta)/eta греют ротор"));
        return new Section("Source", metrics);
    }

    private static Section chassisSection(MechanicalMachine machine) {
        if (!(machine instanceof ChassisMachine chassis)) {
            return null;
        }
        final List<Metric> metrics = new ArrayList<>();
        if (!chassis.hasCore()) {
            metrics.add(Metric.alert("Chassis", "core", "Core", "empty (open box, no power)",
                    true));
            return new Section("Chassis", metrics);
        }
        if (chassis.isShaftMode()) {
            metrics.add(Metric.of("Chassis", "core", "Core",
                    "shaft insert: " + chassis.getShaftMaterial().name()));
        } else {
            metrics.add(Metric.of("Chassis", "core", "Core",
                    "gear: " + chassis.getTeeth() + " teeth, " + chassis.getMaterial().name()));
            metrics.add(Metric.hint("Chassis", "ratio", "Ratio",
                    "8/" + chassis.getTeeth() + " (drive pinion 8T, external mesh reverses)",
                    "Больше зубьев — ниже обороты, выше момент"));
        }
        return new Section("Chassis", metrics);
    }

    private static Section flywheelSection(MechanicalMachine machine) {
        if (!(machine instanceof FlywheelMachine flywheel)) {
            return null;
        }
        final List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.barHint("Flywheel", "energy", "Stored energy",
                String.format(Locale.ROOT, "%.1f / %.0f J (%.0f%%)",
                        flywheel.getEnergy(), flywheel.getCapacity(), flywheel.getFill() * 100),
                clamp01((float) flywheel.getFill()),
                "Заряд от излишка сети, разряд покрывает дефицит момента"));
        return new Section("Flywheel", metrics);
    }

    private static Section transmissionSection(MechanicalMachine machine) {
        final List<Metric> metrics = new ArrayList<>();
        final double eta = machine.getEfficiency();
        metrics.add(Metric.bar("Transmission", "efficiency", "Efficiency",
                eta < 1.0
                        ? String.format(Locale.ROOT, "%.0f%% (loss heats this machine)", eta * 100)
                        : "100% (lossless)",
                (float) eta));

        final long breakaway = machine.getBreakawayTorqueRaw();
        if (breakaway > 0) {
            metrics.add(Metric.of("Transmission", "breakaway", "Breakaway torque",
                    String.format(Locale.ROOT, "%.1f Nm", breakaway / 1000.0)));
        }

        final long idle = machine.getIdleTorqueRaw();
        if (idle > 0) {
            metrics.add(Metric.of("Transmission", "idle", "Idle torque",
                    String.format(Locale.ROOT, "%.1f Nm", idle / 1000.0)));
        }

        final double extraI = machine.getExtraInertia();
        if (extraI > 0) {
            metrics.add(Metric.of("Transmission", "inertia", "Rotor inertia",
                    String.format(Locale.ROOT, "+%.2f", extraI)));
        }
        if (metrics.size() == 1) {
            // Только efficiency — секцию всё равно показываем (pin-стабильность).
            return new Section("Transmission", metrics);
        }
        return new Section("Transmission", metrics);
    }

    // --- Форматтеры ---

    public static String formatPower(RotationalPower power) {
        return String.format(Locale.ROOT, "%.3f RPM, %.3f Nm, dir=%s",
                power.getSpeedRpm(), power.getTorqueNm(),
                dev.sdm.torque_foundry.physics.RotationDirection.from(power.getDirection()));
    }

    private static String formatDirections(net.minecraft.core.Direction[] directions) {
        if (directions == null || directions.length == 0) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder();
        for (net.minecraft.core.Direction direction : directions) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(direction.name());
        }
        return sb.toString();
    }

    private static float clamp01(float v) {
        return Math.min(1.0f, Math.max(0.0f, v));
    }

    /** Пустой снепшот-заглушка (нечего показать). */
    public static InspectorSnapshot empty(String reason) {
        return new InspectorSnapshot("TF Inspector", reason, Collections.emptyList());
    }
}
