package dev.sdm.torque_foundry.physics.machine;

/**
 * Тип опорной точки вала (подшипника) и его паспорт.
 * Опора — МОДИФИКАТОР узла трения (см. док): снижает трение относительно
 * «голого упора» шасси, но имеет рейтинг оборотов — превышение изнашивает.
 */
public enum BearingType {

    /** Пустая точка: вал держит только «голый упор» шасси. */
    NONE(0, 1.0),

    /**
     * Втулка: дёшево, огромные радиальные нагрузки, но низкий рейтинг
     * оборотов и заметное трение.
     */
    SLEEVE(500, 0.85),

    /**
     * Роликовый цилиндрический: высокие обороты, среднее трение,
     * жадный до смазки.
     */
    ROLLER(1_500, 0.70),

    /**
     * Радиальный шарикоподшипник закрытого типа: очень высокие обороты,
     * минимальное трение, смазки не требует.
     */
    BALL(2_500, 0.50);

    /** Рейтинг оборотов, RPM: выше — износ подшипника. */
    private final int rpmRating;

    /** Множитель трения узла у ЗДОРОВОГО смазанного подшипника. */
    private final double frictionMultiplier;

    BearingType(int rpmRating, double frictionMultiplier) {
        this.rpmRating = rpmRating;
        this.frictionMultiplier = frictionMultiplier;
    }

    public int rpmRating() {
        return rpmRating;
    }

    public double frictionMultiplier() {
        return frictionMultiplier;
    }
}
