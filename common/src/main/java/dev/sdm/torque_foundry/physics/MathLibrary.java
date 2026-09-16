package dev.sdm.torque_foundry.physics;

import net.minecraft.util.Mth;

public final class MathLibrary {

    // Плотность воздуха на уровне моря при 15°C (кг/м³)
    public static final float RHO_AIR = 1.225f;

    // Эмпирический коэффициент формы вала (подбирается экспериментально)
    public static final float K_DRAG = 0.05f;

    // t = I * a
    public static float momentOfInertia(
            /* I */ float momentOfInertia,
            /* a */ float angularAcceleration
    ) {
        return momentOfInertia * angularAcceleration;
    }

    // J = (π * r⁴) / 2
    public static float polarMomentOfInertia(
            /* r⁴ */ float radius
    ) {
        return (Mth.PI * (float) Math.pow(radius, 4) / 2);
    }

    // τ_stress = T * r / J
    public static float torqueAndShaftRupture(
            /* T */ float torque,
            /* r */ float radius,
            /* J */ float polarMomentOfInertia
    ) {
        return  torque * radius / polarMomentOfInertia;
    }

    // E_k = 1/2 * I * w²
    public static float kineticEnergy(
            /* I */ float momentInertia,
            /* w */ float omega
    ) {
        return 0.5f * momentInertia * (float) Math.pow(omega, 2);
    }

    // θ = (t * L) / (G * J)
//    public static float torsionalTwist(
//            /* t */
//    )

    /**
     * Вычисляет момент аэродинамического торможения (Nm). <p> (Tair = k * pair * w² * r⁴ * L) </p>
     *
     * @param rhoAir плотность среды (кг/м³)
     * @param omega  угловая скорость (rad/s)
     * @param radius радиус вала (м)
     * @param length длина вала (м)
     * @return момент сопротивления воздуха (Н·м)
     */
    public static float calculateAirTorque(float rhoAir, float omega, float radius, float length) {
        float r2 = radius * radius;
        float r4 = r2 * r2;
        float omega2 = omega * omega;

        return K_DRAG * rhoAir * omega2 * r4 * length;
    }

    /**
     * Вспомогательный метод для перевода оборотов в минуту (RPM) в рад/с:
     * <p> omega = rpm * (2 * PI / 60) </p>
     */
    public static double rpmToOmega(double rpm) {
        return rpm * (Math.PI / 30.0);
    }
}
