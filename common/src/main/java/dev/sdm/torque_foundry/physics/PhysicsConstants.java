package dev.sdm.torque_foundry.physics;

/**
 * Целочисленные константы сетевых единиц движка.
 * Скорости — milli-RPM, моменты — milli-Nm (1 RPM / 1 Nm = 1000 milli).
 */
public final class PhysicsConstants {

    /**
     * Сетевой масштаб: 1 RPM = 1000 milli-RPM, 1 Nm = 1000 milli-Nm.
     */
    public static final int SCALE = 1000;

    /**
     * SCALE² — для целочисленного расчёта мощности.
     */
    public static final int SCALE_2 = SCALE * SCALE;

    /**
     * Рациональное приближение 2π/60 (рад/с из RPM) для целочисленного
     * расчёта мощности: 355/3390.
     */
    public static final long PI2_60_NUM = 355;
    public static final long PI2_60_DEN = 3390;

    /**
     * Рациональное приближение dt·60/(2π) (Шаг интегрирования оборотов):
     * 477/1 000 000.
     */
    public static final long ACCEL_NUM = 477;
    public static final long ACCEL_DEN = 1_000_000;

    private PhysicsConstants() {
    }
}
