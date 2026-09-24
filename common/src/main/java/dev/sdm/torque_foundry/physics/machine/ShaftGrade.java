package dev.sdm.torque_foundry.physics.machine;

/**
 * Тир балансировки вала из качества крафта (станок определяет Grade).
 * Grade задаёт стартовый перекос — множители трения и износа растут с ним.
 *
 * <p>Тиры (согласовано в доке): C=2.0°, B=1.0°, A=0.5°, S=0.1°.
 * Механика ручной докалибровки (поднять показатели Grade B до A) — ПЛАН.
 */
public enum ShaftGrade {

    /**
     * Верстак: заметный перекос, трение +100%, износ +200%.
     */
    C("c", 2.0),

    /**
     * Простой станок: трение +50%, износ +100%.
     */
    B("b", 1.0),

    /**
     * Точный станок: трение +25%, износ +50%.
     */
    A("a", 0.5),

    /**
     * Технологический станок: почти идеальная центровка.
     */
    S("s", 0.1);

    private final String serializedName;

    /**
     * Стартовый перекос вала, градусы.
     */
    private final double misalignmentDeg;

    ShaftGrade(String name, double misalignmentDeg) {
        this.serializedName = name;
        this.misalignmentDeg = misalignmentDeg;
    }

    public double misalignmentDeg() {
        return misalignmentDeg;
    }

    public String getSerializedName() {
        return serializedName;
    }

    /**
     * Тир по имени компонента (дефолт — C).
     */
    public static ShaftGrade byName(String name) {
        for (ShaftGrade g : values()) {
            if (g.serializedName.equals(name)) {
                return g;
            }
        }
        return C;
    }
}
