package dev.sdm.torque_foundry.physics.basic;

import dev.team_argentum.ga_utils.api.Struct;

import java.util.Objects;

public final class MechanicalPower {

    private final long speed;  // raw, в тысячных долях RPM
    private final long torque; // raw, в тысячных долях Nm

    public MechanicalPower(long speed, long torque) {
        this.speed = speed;
        this.torque = torque;
    }

    public long power() {
        // ВАЖНО: это power в масштабе SCALE*SCALE — см. ниже про GUI
        return speed * torque;
    }


    public long speed() {
        return speed;
    }

    public long torque() {
        return torque;
    }

    // Для вывода игроку — единственное место, где вообще всплывает деление на SCALE
    public double speedRpm() {
        return speed / (double) BasicEngine.SCALE;
    }

    public double torqueNm() {
        return torque / (double) BasicEngine.SCALE;
    }


    public enum RotationDirection {
        FORWARD(0),
        REVERSE(1);

        public final byte index;

        RotationDirection(int index) {
            this((byte) index);
        }

        RotationDirection(byte index) {
            this.index = index;
        }
    }
}
