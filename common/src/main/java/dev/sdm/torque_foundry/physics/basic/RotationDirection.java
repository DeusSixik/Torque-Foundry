package dev.sdm.torque_foundry.physics.basic;

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

    public RotationDirection opposite() {
        return this == FORWARD ? REVERSE : FORWARD;
    }

    public static byte opposite(byte index) {
        return (byte) (index == 0 ? 1 : 0);
    }

    public static RotationDirection from(byte index) {
        return index == 0 ? FORWARD : REVERSE;
    }
}
