package dev.sdm.torque_foundry.physics.machine;

/**
 * Мутабельное состояние одной опорной точки вала.
 * Износ растёт от превышения рейтинга оборотов и сухого хода;
 * полный износ = отказ опоры (трение хуже «голого упора» — менять!).
 */
public final class Bearing {

    /**
     * Скорость износа при работе НА рейтинге (доля за тик).
     */
    private static final double WEAR_PER_TICK = 2.0e-6;

    /**
     * Текущий тип опоры (NONE — точка пуста).
     */
    private BearingType type = BearingType.NONE;

    /**
     * Износ 0..1: 0 — новый, 1 — отказ.
     */
    private double wear = 0.0;

    public BearingType type() {
        return type;
    }

    public double wear() {
        return wear;
    }

    public void install(BearingType newType) {
        this.type = newType;
        this.wear = 0.0;
    }

    /**
     * Снятие опоры (гаечный ключ) — возвращает предметное состояние.
     */
    public void remove() {
        this.type = BearingType.NONE;
        this.wear = 0.0;
    }

    public boolean present() {
        return type != BearingType.NONE;
    }

    /**
     * Требует ли опора смазки (закрытый шариковый — нет).
     */
    public boolean needsLubrication() {
        return present() && type != BearingType.BALL;
    }

    /**
     * Полный износ — опора отказала.
     */
    public boolean broken() {
        return present() && wear >= 1.0;
    }

    /**
     * Тик износа опоры.
     *
     * @param speedRaw   обороты сети, milli-RPM
     * @param wearFactor множитель износа узла (перекос, сухой ход)
     */
    public void wearTick(long speedRaw, double wearFactor) {
        if (!present()) {
            return;
        }
        // Износ ∝ (обороты / рейтинг)^2: превышение рейтинга — быстро,
        // работа ниже рейтинга — практически вечный подшипник
        final double ratio = (speedRaw / 1000.0) / type.rpmRating();
        if (ratio <= 0) {
            return;
        }
        wear += ratio * ratio * WEAR_PER_TICK * wearFactor;
        if (wear > 1.0) {
            wear = 1.0;
        }
    }

    /**
     * Множитель трения опоры. Здоровый смазанный — паспортный тип опоры,
     * умноженный на фактор сорта смазки (&lt;1 — скользче). Изношенный и
     * сухой деградируют к «голому упору» (1.0) и выше; отказавшая опора —
     * максимальные 1.8 (обломки скребут корпус). На сухую свойства сорта
     * не действуют: смазки нет — её множителей нет.
     *
     * @param lubricated      есть ли смазка в резервуаре
     * @param lubricantFactor множитель трения сорта смазки (&gt; 0; на сухую — 1.0)
     */
    public double frictionMultiplier(boolean lubricated, double lubricantFactor) {
        if (!present()) {
            return 1.0;
        }
        final double best = type.frictionMultiplier()
                * (lubricated ? Math.max(0.05, lubricantFactor) : 1.0);
        double degradation = wear;
        if (needsLubrication() && !lubricated) {
            degradation = Math.max(degradation, 0.8);
        }
        if (broken()) {
            degradation = 1.0;
        }
        return best + degradation * (1.8 - best);
    }

    public Bearing copy() {
        final Bearing b = new Bearing();
        b.type = this.type;
        b.wear = this.wear;
        return b;
    }
}
