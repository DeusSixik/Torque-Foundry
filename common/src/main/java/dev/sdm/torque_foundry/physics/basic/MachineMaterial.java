package dev.sdm.torque_foundry.physics.basic;

/**
 * Характеристики материала механических деталей.
 *
 * @param name            отображаемое имя
 * @param maxSafeSpeedRpm безопасные обороты: выше — износ (ShaftWearHook)
 * @param friction        коэффициент трения: момент трения = friction * speedRaw
 * @param density         плотность: вклад в инерцию сети (разгон/торможение)
 * @param strength        прочность (для будущих повреждений при заклинивании)
 */
public record MachineMaterial(String name, int maxSafeSpeedRpm, double friction, double density, int strength) {

    @Override
    public String toString() {
        return name;
    }
}
