package dev.sdm.torque_foundry.physics.machine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Реестр сортов смазки. Движок не знает перечисления сортов — только
 * паспорта {@link LubricantKind}: пресеты движка зарегистрированы здесь,
 * аддон добавляет свой сорт методом {@link #register} при инициализации
 * и больше нигде в движке не появляется.
 *
 * <p>Пресеты документа (таблица предварительная; твёрдая, синтетическая
 * и газовая пока не начаты — регистрируются аддоном или позже здесь же):
 * <ul>
 *   <li>{@link #GREASE} — пластичная, до 120 °C;</li>
 *   <li>{@link #OIL} — жидкая минеральная, до 90 °C.</li>
 * </ul>
 *
 * <p>Повторная регистрация занятого id ничего не меняет (первый
 * зарегистрированный паспорт выигрывает) — перезапуск в дев-среде безопасен.
 * Индексы в реестре стабильны в пределах запуска и используются снимком
 * группы для отладки; сериализация состояния — по {@link LubricantKind#id()}.
 */
public final class LubricantKinds {

    /**
     * Пустой резервуар: лимитов и множителей не задаёт.
     */
    public static final LubricantKind NONE = new LubricantKind(
            "none", "None", Double.POSITIVE_INFINITY, 1.0, 1.0);

    /**
     * Пластичная (литол, солидол) — рабочая до ~120 °C.
     */
    public static final LubricantKind GREASE = new LubricantKind(
            "grease", "Grease", 120.0, 1.0, 1.0);

    /**
     * Жидкая минеральная — рабочая до ~90 °C.
     */
    public static final LubricantKind OIL = new LubricantKind(
            "oil", "Oil", 90.0, 1.0, 1.0);

    /**
     * Реестр по стабильному id.
     */
    private static final Map<String, LubricantKind> BY_ID = new ConcurrentHashMap<>();

    /**
     * Сорта по индексам (снапшот группы, отладка).
     */
    private static final List<LubricantKind> BY_INDEX = new CopyOnWriteArrayList<>();

    static {
        register(NONE);
        register(GREASE);
        register(OIL);
    }

    private LubricantKinds() {
    }

    /**
     * Регистрирует сорт смазки. Повторная регистрация занятого id —
     * no-op, возвращается ранее зарегистрированный паспорт.
     *
     * @param kind паспорт сорта
     * @return фактический сорт в реестре под этим id
     */
    public static LubricantKind register(LubricantKind kind) {
        final LubricantKind stored = BY_ID.putIfAbsent(kind.id(), kind);
        if (stored != null) {
            return stored;
        }
        BY_INDEX.add(kind);
        return kind;
    }

    /**
     * Сорт по стабильному id.
     *
     * @param id ключ реестра
     * @return паспорт или {@code null}, если сорт не зарегистрирован
     */
    public static LubricantKind byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /**
     * Сорт по стабильному id с фолбэком: незнакомый id (аддон удалён из
     * сборки) деградирует до {@link #NONE} — резервуар остаётся, но узел
     * считает его сухим, игрок перезаправляет.
     *
     * @param id       ключ реестра
     * @param fallback сорт для незнакомого id
     * @return паспорт сорта
     */
    public static LubricantKind byIdOrDefault(String id, LubricantKind fallback) {
        final LubricantKind kind = byId(id);
        return kind != null ? kind : fallback;
    }

    /**
     * Стабильный в пределах запуска индекс сорта (снимок группы, отладка).
     *
     * @param kind паспорт
     * @return индекс 0..N; незарегистрированный паспорт — 0 (NONE)
     */
    public static int indexOf(LubricantKind kind) {
        if (kind == null) {
            return 0;
        }
        for (int i = 0; i < BY_INDEX.size(); i++) {
            if (BY_INDEX.get(i) == kind) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Сорт по индексу из снимка группы (обратна {@link #indexOf}).
     *
     * @param index индекс из снапшота
     * @return паспорт; NONE — если индекс вне реестра
     */
    public static LubricantKind byIndex(int index) {
        if (index < 0 || index >= BY_INDEX.size()) {
            return NONE;
        }
        return BY_INDEX.get(index);
    }
}
