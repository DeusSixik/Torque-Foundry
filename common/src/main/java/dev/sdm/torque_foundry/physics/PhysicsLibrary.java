package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.physics.basic.MechanicalPowerConstants;

public class PhysicsLibrary {

    public static long tickSpeed(long currentSpeedRaw, long targetMaxSpeedRaw, long netTorqueRaw, long totalInertia) {
        if (totalInertia <= 0) {
            return targetMaxSpeedRaw; // без инерции разгон мгновенный
        }

        // Если есть свободный крутящий момент и мы еще не достигли предела мотора
        if (netTorqueRaw > 0 && currentSpeedRaw < targetMaxSpeedRaw) {
            // dSpeed = (torque * 477) / (inertia * 1000)
            long deltaSpeed = (netTorqueRaw * MechanicalPowerConstants.ACCEL_NUM) / (totalInertia * (MechanicalPowerConstants.ACCEL_DEN / 1000));

            // Защита от нулевого прироста при малом моменте (минимальный шаг 1 milli-RPM)
            if (deltaSpeed == 0) {
                deltaSpeed = 1;
            }

            long newSpeed = currentSpeedRaw + deltaSpeed;
            return Math.min(newSpeed, targetMaxSpeedRaw);
        }

        // Если мотор выключен или нагрузка превысила тягу — торможение (netTorqueRaw < 0)
        if (netTorqueRaw < 0 && currentSpeedRaw > 0) {
            long brakeTorque = -netTorqueRaw;
            long deltaSpeed = (brakeTorque * MechanicalPowerConstants.ACCEL_NUM) / (totalInertia * (MechanicalPowerConstants.ACCEL_DEN / 1000));
            if (deltaSpeed == 0) deltaSpeed = 1;

            return Math.max(0, currentSpeedRaw - deltaSpeed);
        }

        return currentSpeedRaw;
    }
}
