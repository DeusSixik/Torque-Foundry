package dev.sdm.torque_foundry.api.debug;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Сборщик отладочных метрик для TF Inspector.
 *
 * <p>Компоненты физдвижка (машины, BlockEntity, хуки, аддон-компоненты)
 * заполняют его в {@code addDebugInfo(DebugInfoCollector)} — инспектор
 * забирает готовые секции и показывает их вместе со стандартными.
 *
 * <p>Стиль записи — fluent, секция задаётся один раз:
 * <pre>{@code
 * collector.section("MyAddon")
 *     .add("Charge", "42 J")
 *     .bar("Fill", "42%", 0.42f)
 *     .alert("Overheat", "yes", temp > LIMIT)
 *     .hint("Mode", "turbo", "Включается ключом");
 * }</pre>
 *
 * <p>Не является hot path: вызывается сборкой инспектора (рендер-тред,
 * раз в кадр для блока под прицелом) — строковые значения и Locale.format
 * здесь легальны. Экземпляр переиспользуется вызовом {@link #clear}.
 *
 * <p>Каждой записи нужен стабильный {@code key} (id пина HUD): если он не
 * задан явно, выводится из label. Для одного label в одной секции — один key.
 */
public final class DebugInfoCollector {

    /** Одна метрика отладки. barFraction NaN — без прогресс-бара. */
    public record Entry(String key, String label, String value, boolean alert,
                        float barFraction, String hint) {
    }

    /** Секция метрик с заголовком (совпадает по смыслу с секцией инспектора). */
    public record Section(String name, List<Entry> entries) {
    }

    /** Секция по умолчанию, если аддон не открыл свою перед записью. */
    public static final String DEFAULT_SECTION = "Custom";

    private final List<Section> sections = new ObjectArrayList<>();
    private String currentSectionName;
    private List<Entry> currentEntries;

    /**
     * Начать/продолжить секцию с именем {@code name}. Повторный вызов с тем
     * же именем дописывает в существующую секцию (порядок вызовов сохранён).
     */
    public DebugInfoCollector section(String name) {
        final String safe = name == null || name.isBlank() ? DEFAULT_SECTION : name;
        if (safe.equals(currentSectionName)) {
            return this;
        }
        // Существующая секция с этим именем? Дописываем в неё.
        for (Section section : sections) {
            if (section.name().equals(safe)) {
                currentSectionName = safe;
                currentEntries = section.entries();
                return this;
            }
        }
        currentSectionName = safe;
        currentEntries = new ObjectArrayList<>();
        sections.add(new Section(safe, currentEntries));
        return this;
    }

    /** Простая метрика: label = value. */
    public DebugInfoCollector add(String label, String value) {
        return entry(label, value, false, Float.NaN, null);
    }

    /** Метрика с подсветкой-тревогой (красная строка). */
    public DebugInfoCollector alert(String label, String value, boolean alert) {
        return entry(label, value, alert, Float.NaN, null);
    }

    /** Метрика с прогресс-баром 0..1. */
    public DebugInfoCollector bar(String label, String value, float fraction) {
        return entry(label, value, false, clamp01(fraction), null);
    }

    /** Метрика с баром и подсветкой. */
    public DebugInfoCollector barAlert(String label, String value, boolean alert, float fraction) {
        return entry(label, value, alert, clamp01(fraction), null);
    }

    /** Метрика с подсказкой (tooltip). */
    public DebugInfoCollector hint(String label, String value, String hint) {
        return entry(label, value, false, Float.NaN, hint);
    }

    /** Полная форма: подсветка + бар + подсказка. */
    public DebugInfoCollector entry(String label, String value, boolean alert,
                                    float barFraction, String hint) {
        return appendEntry(keyOf(currentSectionName(), label), label, value, alert,
                barFraction, hint);
    }

    // --- Варианты с ЯВНЫМ ключом (сохранение существующих id метрик) ---
    // Стандартные секции физдвижка держат исторические id ("Machine.leafpower")
    // — автоключ из label дал бы другие ("machine.leaf_power") и сломал пины.

    /** Простая метрика с явным ключом. */
    public DebugInfoCollector addKey(String key, String label, String value) {
        return appendEntry(key, label, value, false, Float.NaN, null);
    }

    /** Метрика с подсветкой и явным ключом. */
    public DebugInfoCollector alertKey(String key, String label, String value, boolean alert) {
        return appendEntry(key, label, value, alert, Float.NaN, null);
    }

    /** Метрика с баром и явным ключом. */
    public DebugInfoCollector barKey(String key, String label, String value, float fraction) {
        return appendEntry(key, label, value, false, clamp01(fraction), null);
    }

    /** Метрика с баром, подсветкой и явным ключом. */
    public DebugInfoCollector barAlertKey(String key, String label, String value, boolean alert,
                                          float fraction) {
        return appendEntry(key, label, value, alert, clamp01(fraction), null);
    }

    /** Метрика с подсказкой и явным ключом. */
    public DebugInfoCollector hintKey(String key, String label, String value, String hint) {
        return appendEntry(key, label, value, false, Float.NaN, hint);
    }

    /** Полная форма с явным ключом: подсветка + бар + подсказка. */
    public DebugInfoCollector entryKey(String key, String label, String value, boolean alert,
                                       float barFraction, String hint) {
        return appendEntry(key, label, value, alert, barFraction, hint);
    }

    private String currentSectionName() {
        if (currentEntries == null) {
            section(DEFAULT_SECTION);
        }
        return currentSectionName;
    }

    private DebugInfoCollector appendEntry(String key, String label, String value, boolean alert,
                                           float barFraction, String hint) {
        if (currentEntries == null) {
            section(DEFAULT_SECTION);
        }
        final String safeLabel = label == null ? "?" : label;
        currentEntries.add(new Entry(
                key == null ? keyOf(currentSectionName, safeLabel) : key, safeLabel,
                value == null ? "—" : value, alert, barFraction, hint));
        return this;
    }

    /** Все собранные секции (только чтение). */
    public List<Section> sections() {
        return Collections.unmodifiableList(sections);
    }

    /** Была ли собрана хоть одна метрика. */
    public boolean isEmpty() {
        for (Section section : sections) {
            if (!section.entries().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Сброс для переиспользования (рендер-тред владеет экземпляром). */
    public void clear() {
        sections.clear();
        currentSectionName = null;
        currentEntries = null;
    }

    /**
     * Стабильный id "section.key" (ключ пина HUD): key выводится из label —
     * lower-case, не-алфанумерика в '_'. Аддон с одинаковыми label в одной
     * секции получит один id — задавайте уникальные label.
     */
    private static String keyOf(String section, String label) {
        final StringBuilder sb = new StringBuilder(label.length());
        for (int i = 0; i < label.length(); i++) {
            final char c = label.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            } else {
                sb.append('_');
            }
        }
        return (section + "." + sb).toLowerCase(Locale.ROOT);
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) {
            return Float.NaN;
        }
        return Math.min(1.0f, Math.max(0.0f, v));
    }
}
