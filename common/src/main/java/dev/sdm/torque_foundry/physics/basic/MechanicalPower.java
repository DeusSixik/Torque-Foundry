package dev.sdm.torque_foundry.physics.basic;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

import static dev.sdm.torque_foundry.physics.basic.MechanicalPowerConstants.PI2_60_DEN;
import static dev.sdm.torque_foundry.physics.basic.MechanicalPowerConstants.PI2_60_NUM;

public class MechanicalPower {

    public static MechanicalPower from(MechanicalPower input) {
        return new MechanicalPower(
                input.speed,
                input.torque,
                input.direction
        );
    }

    public static MechanicalPower from(long speed, long torque) {
        return from(speed, torque, RotationDirection.FORWARD);
    }

    public static MechanicalPower from(long speed, long torque, RotationDirection direction) {
        return from(speed, torque, direction.index);
    }

    public static MechanicalPower from(long speed, long torque, byte direction) {
        return new MechanicalPower(
                speed * MechanicalPowerConstants.SCALE,
                torque * MechanicalPowerConstants.SCALE,
                direction
        );
    }

    public static MechanicalPower fromRaw(long speedRaw, long torqueRaw) {
        return fromRaw(speedRaw, torqueRaw, RotationDirection.FORWARD);
    }

    public static MechanicalPower fromRaw(long speedRaw, long torqueRaw, RotationDirection direction) {
        return fromRaw(speedRaw, torqueRaw, direction.index);
    }

    public static MechanicalPower fromRaw(long speedRaw, long torqueRaw, byte direction) {
        return new MechanicalPower(speedRaw, torqueRaw, direction);
    }

    /**
     * RPM in milli-RPM = A / {@link MechanicalPowerConstants#SCALE}
     */
    protected long speed;

    /**
     * Nm in milli-Nm = A / {@link MechanicalPowerConstants#SCALE}
     */
    protected long torque;

    /**
     * Rotation direction {@link RotationDirection}
     */
    protected byte direction;

    protected MechanicalPower(long speed, long torque, byte direction) {
        this.speed = speed;
        this.torque = torque;
        this.direction = direction;
    }

    public void copyFrom(@NotNull MechanicalPower power) {
        this.speed = power.speed;
        this.torque = power.torque;
        this.direction = power.direction;
    }

    public void setParams(long speed, long torque, RotationDirection direction) {
        this.setParams(speed, torque, direction.index);
    }

    public void setParams(long speed, long torque, byte direction) {
        this.setParams(speed, torque);
        this.setDirection(direction);
    }

    public void setParams(long speed, long torque) {
        this.setSpeed(speed);
        this.setTorque(torque);
    }

    public void setSpeed(long speed) {
        this.speed = speed * MechanicalPowerConstants.SCALE;
    }

    public void setSpeedRaw(long speed) {
        this.speed = speed;
    }

    public void setTorque(long torque) {
        this.torque = torque * MechanicalPowerConstants.SCALE;
    }

    public void setTorqueRaw(long torque) {
        this.torque = torque;
    }

    public void setDirection(RotationDirection direction) {
        this.setDirection(direction.index);
    }

    public void setDirection(byte direction) {
        this.direction = direction;
    }

    public long getSpeedRaw() {
        return this.speed;
    }

    public long getTorqueRaw() {
        return this.torque;
    }

    public byte getDirection() {
        return this.direction;
    }

    public double getSpeedRpm() {
        return this.speed / (double) MechanicalPowerConstants.SCALE;
    }

    public double getTorqueNm() {
        return this.torque / (double) MechanicalPowerConstants.SCALE;
    }

    public double getPowerWatts() {
        return (double) this.torque * this.speed * (2.0 * Math.PI / 60.0) / MechanicalPowerConstants.SCALE_2;
    }

    public long getPower() {
        long num = this.torque * PI2_60_NUM;
        return (num / PI2_60_DEN) * this.speed / MechanicalPowerConstants.SCALE_2;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "MechanicalPower{speed=%.3f RPM, torque=%.3f Nm, power=%s powerWatts=%.3f W, dir=%s}",
                getSpeedRpm(),
                getTorqueNm(),
                getPower(),
                getPowerWatts(),
                RotationDirection.from(this.direction) // или this.direction, если нет вспомогательного метода
        );
    }

    public MechanicalPower plus(MechanicalPower power) {
        return plus(power.speed, power.torque);
    }

    public MechanicalPower plus(long speed, long torque) {
        this.speed += speed;
        this.torque += torque;
        return this;
    }

    public MechanicalPower minus(MechanicalPower power) {
        return minus(power.speed, power.torque);
    }

    public MechanicalPower minus(long speed, long torque) {
        this.speed -= speed;
        this.torque -= torque;
        return this;
    }
}
