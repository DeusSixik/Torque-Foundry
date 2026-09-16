package dev.sdm.torque_foundry.physics.basic;

public class BasicEngine {

    public static final int SCALE = 1000;

    private final long maxSpeedRaw;   // хранится как rawUnits
    private final long torqueRaw;

    // Конструктор принимает "человеческие" RPM и Nm
    public BasicEngine(int maxSpeedRpm, int torqueNm) {
        this.maxSpeedRaw = (long) maxSpeedRpm * SCALE;
        this.torqueRaw = (long) torqueNm * SCALE;
    }

    public MechanicalPower output() {
        return new MechanicalPower(maxSpeedRaw, torqueRaw);
    }
}
