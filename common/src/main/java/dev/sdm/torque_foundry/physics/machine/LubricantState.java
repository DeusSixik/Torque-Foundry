package dev.sdm.torque_foundry.physics.machine;

/**
 * Резервуар смазки механизма. Наносится предметом (позже — трубами),
 * расходуется пропорционально режиму работы (обороты × нагрузка).
 * Пустой резервуар — подшипники работают «на сухую»: трение и износ выше.
 *
 * <p>Резервуар хранит паспорт сорта {@link LubricantKind} из реестра
 * {@link LubricantKinds}, а не перечисление: свойства сорта (температурный
 * лимит, множители трения и износа) живут в паспорте, новый сорт
 * регистрируется аддоном без правки движка.
 *
 * <p>Смешивать сорта нельзя: подача другого сорта в непустой резервуар
 * отвергается — сначала выработать или слить ({@link #drain}).
 */
public final class LubricantState {

    /**
     * Ёмкость резервуара в условных единицах.
     */
    public static final double CAPACITY = 1000.0;

    /**
     * Расход за тик на ОДНУ нагруженную точку при 1000 milli-RPM.
     * Полный бак (~1000 ед.) при 256 RPM на двух втулках ~16 минут.
     */
    private static final double CONSUMPTION_PER_TICK = 1.0e-4;

    private LubricantKind kind = LubricantKinds.NONE;
    private double amount = 0.0;

    /**
     * Паспорт заправленного сорта ({@link LubricantKinds#NONE}, если пуст).
     */
    public LubricantKind kind() {
        return kind;
    }

    /**
     * Стабильный id сорта — для сериализации (п.28 «хвостов»).
     */
    public String kindId() {
        return kind.id();
    }

    public double amount() {
        return amount;
    }

    public boolean available() {
        return kind != LubricantKinds.NONE && amount > 0;
    }

    /**
     * Заправка предметом-смазкой. Возвращает, сколько фактически влезло
     * (в условных единицах). Смена сорта в непустом резервуаре отвергается.
     *
     * @param newKind  паспорт заправляемого сорта
     * @param amountIn предлагаемое количество, условные единицы, &ge; 0
     * @return принятое количество, условные единицы
     */
    public double fill(LubricantKind newKind, double amountIn) {
        if (newKind == null || amount > 0 && kind != newKind) {
            // смешивать сорта нельзя — сначала выработать/слить старую
            return 0.0;
        }
        kind = newKind;
        final double accepted = Math.min(amountIn, CAPACITY - amount);
        amount += accepted;
        return accepted;
    }

    /**
     * Восстановление сорта из сохранения по стабильному id. Незнакомый id
     * (сорт аддона удалён из сборки) деградирует до NONE: количество
     * остаётся, но узел считает резервуар сухим — игрок перезаправляет.
     *
     * @param kindId   стабильный id сорта ({@link LubricantKind#id()})
     * @param amountIn количество, условные единицы
     */
    public void restore(String kindId, double amountIn) {
        this.kind = LubricantKinds.byIdOrDefault(kindId, LubricantKinds.NONE);
        this.amount = Math.max(0, Math.min(CAPACITY, amountIn));
    }

    public void drain() {
        kind = LubricantKinds.NONE;
        amount = 0.0;
    }

    /**
     * Расход смазки за тик.
     *
     * @param speedRaw     обороты, milli-RPM
     * @param loadFactor   нагрузочный фактор узла (доля потребления тяги, 0..1+)
     * @param bearingCount число активных опор, потребляющих смазку
     */
    public void consumeTick(long speedRaw, double loadFactor, int bearingCount) {
        if (!available() || bearingCount <= 0) {
            return;
        }
        amount = Math.max(0, amount
                - CONSUMPTION_PER_TICK * bearingCount
                * (speedRaw / 1000.0) * Math.max(0.1, loadFactor));
        if (amount <= 0) {
            amount = 0;
        }
    }
}
