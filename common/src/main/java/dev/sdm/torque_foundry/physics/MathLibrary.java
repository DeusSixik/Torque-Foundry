package dev.sdm.torque_foundry.physics;

import net.minecraft.util.Mth;

public final class MathLibrary {

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
}
