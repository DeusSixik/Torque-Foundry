package dev.sdm.torque_foundry.physics.machine;

/**
 * Резервуар смазки механизма. Наносится предметом (позже — трубами),
 * расходуется пропорционально режиму работы (обороты × нагрузка).
 * Пустой резервуар — подшипники работают «на сухую»: трение и износ выше.
 *
 * <p>Типы смазки пока механически эквивалентны; различие (температурные
 * лимиты: пластичная до 120 C, минеральная до 90 C) вступит в силу
 * с температурной моделью смазок.
 */
public final class LubricantState {

    public enum Type {
        NONE,
        /** Пластичная (литол, солидол) — рабочая до ~120 C. */
        GREASE,
        /** Жидкая минеральная — рабочая до ~90 C. */
        OIL
    }

    /** Ёмкость резервуара в условных единицах. */
    public static final double CAPACITY = 1000.0;

    /** Расход за тик на ОДНУ нагруженную точку при 1000 milli-RPM.
     *  Полный бак (~1000 ед.) при 256 RPM на двух втулках ~16 минут. */
    private static final double CONSUMPTION_PER_TICK = 1.0e-4;

    private Type type = Type.NONE;
    private double amount = 0.0;

    public Type type() {
        return type;
    }

    public double amount() {
        return amount;
    }

    public boolean available() {
        return type != Type.NONE && amount > 0;
    }

    /**
     * Заправка предметом-смазкой. Возвращает, сколько фактически влезло
     * (в условных единицах).
     */
    public double fill(Type newType, double amountIn) {
        if (amount > 0 && type != newType) {
            // смешивать типы нельзя — сначала выработать/слить старую
            return 0.0;
        }
        type = newType;
        final double accepted = Math.min(amountIn, CAPACITY - amount);
        amount += accepted;
        return accepted;
    }

    public void drain() {
        type = Type.NONE;
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
