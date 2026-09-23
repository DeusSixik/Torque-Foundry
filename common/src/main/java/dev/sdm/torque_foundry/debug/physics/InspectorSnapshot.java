package dev.sdm.torque_foundry.debug.physics;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.api.debug.DebugInfoCollector;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Снепшот всех показателей механического блока для отладочного инспектора.
 * Собирается раз за кадр из живой машины (клиент видит серверный снепшот
 * через ClientGroupCache) и раскладывается по секциям. Каждая метрика имеет
 * стабильный id вида "section.key" — он же ключ закрепления в PinStore.
 *
 * <p>После перевода на механизм {@code addDebugInfo} стандартные секции
 * (Machine/Material/Thermal/Bearings/Transmission) отдаёт сама база
 * {@link MechanicalMachine}, класс-специфика (Source/Chassis/Flywheel) —
 * подклассы, BE-уровень — BlockEntity, аддоны — свои компоненты. Инспектор
 * знает только Group-секцию (она требует данных группы, а не машины).
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
        return collect(blockKey, blockPos, machineClass,
                groupLine, membersLine, netSpeedLine, groupUnknown, machine, null);
    }

    /**
     * Полная сборка: Group-секция инспектора + вклады всех компонентов через
     * {@code addDebugInfo} (машина отдаёт Machine/Material/Thermal/Bearings/
     * Transmission + класс-специфику, BE — свой уровень, аддоны — свои секции).
     * Единая точка сбора: вызывающему не нужно самому дёргать addDebugInfo.
     *
     * <p>Коллектор переиспользуется (рендер-тред — единственный потребитель,
     * как SYNC_VIEW в TFNetworking).
     *
     * @param blockEntity BlockEntity блока (null — вклад BE недоступен)
     */
    public static InspectorSnapshot collect(
            String blockKey, String blockPos, String machineClass,
            String groupLine, String membersLine, String netSpeedLine, boolean groupUnknown,
            MechanicalMachine machine,
            MechanicalBlockEntity blockEntity) {
        final List<Section> sections = new ArrayList<>();
        sections.add(groupSection(groupLine, membersLine, netSpeedLine, groupUnknown));

        final DebugInfoCollector extras = EXTRAS;
        extras.clear();
        if (machine != null) {
            machine.addDebugInfo(extras);
        }
        if (blockEntity != null) {
            blockEntity.addDebugInfo(extras);
        }
        appendExtras(sections, extras);

        return new InspectorSnapshot(
                "TF Inspector — " + machineClass,
                blockKey + " @ " + blockPos,
                sections);
    }

    /** Переиспользуемый коллектор вкладов (рендер-тред — единственный потребитель). */
    private static final DebugInfoCollector EXTRAS = new DebugInfoCollector();

    /**
     * Конвертация секций коллектора в метрики инспектора. Стабильный id
     * записи коллектора ("section.key") становится id метрики — пины HUD
     * работают и для аддонских секций. Package-private: тесты порядка.
     */
    static void appendExtras(List<Section> sections, DebugInfoCollector extras) {
        if (extras == null) {
            return;
        }
        for (DebugInfoCollector.Section extra : extras.sections()) {
            final List<Metric> metrics = new ArrayList<>(extra.entries().size());
            for (DebugInfoCollector.Entry entry : extra.entries()) {
                metrics.add(toMetric(extra.name(), entry));
            }
            sections.add(new Section(extra.name(), metrics));
        }
    }

    /**
     * id метрики = id записи коллектора (без двойного префикса секции) —
     * приватный конструктор доступен: класс тот же.
     */
    private static Metric toMetric(String section, DebugInfoCollector.Entry e) {
        return new Metric(e.key(), section, e.label(), e.value(),
                e.alert(), e.barFraction(), e.hint());
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

    /** Пустой снепшот-заглушка (нечего показать). */
    public static InspectorSnapshot empty(String reason) {
        return new InspectorSnapshot("TF Inspector", reason, Collections.emptyList());
    }
}
