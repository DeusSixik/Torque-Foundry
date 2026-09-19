package dev.sdm.torque_foundry.physics;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Мощность вращения на ребре/входе машины: обороты + момент + направление.
 * Mutable-архитектура: объекты переиспользуются, на тик не создаются.
 */
public class RotationalPower {

    public static RotationalPower from(RotationalPower input) {
        return new RotationalPower(
                input.speed,
                input.torque,
                input.direction
        );
    }

    public static RotationalPower from(long speed, long torque) {
        return from(speed, torque, RotationDirection.FORWARD);
    }

    public static RotationalPower from(long speed, long torque, RotationDirection direction) {
        return from(speed, torque, direction.index);
    }

    /** СИ-вход (RPM, Nm) — внутри умножается на SCALE. */
    public static RotationalPower from(long speed, long torque, byte direction) {
        return new RotationalPower(
                speed * PhysicsConstants.SCALE,
                torque * PhysicsConstants.SCALE,
                direction
        );
    }

    public static RotationalPower fromRaw(long speedRaw, long torqueRaw) {
        return fromRaw(speedRaw, torqueRaw, RotationDirection.FORWARD);
    }

    public static RotationalPower fromRaw(long speedRaw, long torqueRaw, RotationDirection direction) {
        return fromRaw(speedRaw, torqueRaw, direction.index);
    }

    public static RotationalPower fromRaw(long speedRaw, long torqueRaw, byte direction) {
        return new RotationalPower(speedRaw, torqueRaw, direction);
    }

    /**
     * Обороты в milli-RPM = A / {@link PhysicsConstants#SCALE}
     */
    protected long speed;

    /**
     * Момент в milli-Nm = A / {@link PhysicsConstants#SCALE}
     */
    protected long torque;

    /**
     * Направление вращения {@link RotationDirection}
     */
    protected byte direction;

    protected RotationalPower(long speed, long torque, byte direction) {
        this.speed = speed;
        this.torque = torque;
        this.direction = direction;
    }

    public void copyFrom(@NotNull RotationalPower power) {
        this.speed = power.speed;
        this.torque = power.torque;
        this.direction = power.direction;
    }

    /**
     * Обнуляет мощность без создания нового объекта (mutable-архитектура).
     */
    public void reset() {
        this.speed = 0;
        this.torque = 0;
        this.direction = RotationDirection.FORWARD.index;
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

    /** СИ-вход (RPM) — внутри умножается на SCALE. */
    public void setSpeed(long speed) {
        this.speed = speed * PhysicsConstants.SCALE;
    }

    public void setSpeedRaw(long speed) {
        this.speed = speed;
    }

    /** СИ-вход (Nm) — внутри умножается на SCALE. */
    public void setTorque(long torque) {
        this.torque = torque * PhysicsConstants.SCALE;
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
        return this.speed / (double) PhysicsConstants.SCALE;
    }

    public double getTorqueNm() {
        return this.torque / (double) PhysicsConstants.SCALE;
    }

    /** Мощность в ваттах (double, для отображения). */
    public double getPowerWatts() {
        return (double) this.torque * this.speed * (2.0 * Math.PI / 60.0) / PhysicsConstants.SCALE_2;
    }

    /** Мощность в ваттах (целочисленно, без потерь округления). */
    public long getPower() {
        return PhysicsMath.watts(this.torque, this.speed);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "RotationalPower{speed=%.3f RPM, torque=%.3f Nm, power=%d W, powerWatts=%.3f W, dir=%s}",
                getSpeedRpm(),
                getTorqueNm(),
                getPower(),
                getPowerWatts(),
                RotationDirection.from(this.direction)
        );
    }

    public RotationalPower plus(RotationalPower power) {
        return plus(power.speed, power.torque);
    }

    public RotationalPower plus(long speed, long torque) {
        this.speed += speed;
        this.torque += torque;
        return this;
    }

    public RotationalPower minus(RotationalPower power) {
        return minus(power.speed, power.torque);
    }

    public RotationalPower minus(long speed, long torque) {
        this.speed -= speed;
        this.torque -= torque;
        return this;
    }
}
