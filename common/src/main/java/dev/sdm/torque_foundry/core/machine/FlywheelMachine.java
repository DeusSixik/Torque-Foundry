package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Маховик: накапливает кинетическую энергию и сглаживает сеть.
 *
 * Механика:
 *  - transform: пока в запасе есть энергия, поддерживает момент на выходе
 *    до паспортного (ratedTorqueRaw) — если сеть даёт меньше;
 *  - onNetworkTick: сводит баланс — излишек (received > потребили) заряжает
 *    маховик, дефицит (потребили больше received, покрыв маховиком) —
 *    разряжает его. Потери цикла учитывает efficiency.
 *
 * Энергия в джоулях: E = 0.5 * I * omega^2. Заряд/разряд за тик = ватты / 20.
 */
public class FlywheelMachine extends MechanicalMachine {

    /** Ёмкость запаса, джоули. */
    private final double capacity;

    /** КПД цикла заряд/разряд. */
    private final double efficiency;

    /** Паспортный момент (milli-Nm), до которого маховик поддерживает сеть. */
    private final long ratedTorqueRaw;

    /** Текущий запас, джоули. */
    private double energy;

    private final Direction inputSide;
    private final Direction outputSide;

    public FlywheelMachine(double capacityJoules, double efficiency, long ratedTorqueRaw,
                           Direction inputSide, Direction outputSide) {
        super(MechanicalPower.fromRaw(0, 0), (byte) -1);
        this.capacity = Math.max(1.0, capacityJoules);
        this.efficiency = Math.max(0.1, Math.min(1.0, efficiency));
        this.ratedTorqueRaw = ratedTorqueRaw;
        this.energy = 0;
        this.inputSide = inputSide;
        this.outputSide = outputSide;
        port(inputSide, PortRole.IN_OUT);
        port(outputSide, PortRole.IN_OUT);
    }

    @Override
    protected void createDirections() {
        // Порты задаёт конструктор
    }

    @Override
    public boolean coversDeficitFromBuffer() {
        return true;
    }

    @Override
    public boolean hasBufferReserve() {
        return energy > 0;
    }

    public double getEnergy() {
        return energy;
    }

    public double getCapacity() {
        return capacity;
    }

    /**
     * Заполненность 0..1 (для UI/рендера).
     */
    public double getFill() {
        return energy / capacity;
    }

    /**
     * Поддержка момента: если вход даёт меньше паспортного момента маховика,
     * разница покрывается из запаса (пока хватает энергии).
     */
    @Override
    public MechanicalPower transform(MechanicalPower input, Direction outputSide) {
        if (outputSide != this.outputSide) {
            return input;
        }

        final long speed = input.getSpeedRaw();
        if (speed <= 0 || energy <= 0) {
            return input;
        }

        final long torque = input.getTorqueRaw();
        if (torque >= ratedTorqueRaw) {
            return input;
        }

        // Биллинг произойдёт в onNetworkTick (дети получат буст ->
        // childrenWatts - receivedWatts = расход маховика за тик).
        final long newTorque = Math.min(ratedTorqueRaw, Math.max(torque, ratedTorqueRaw));
        return MechanicalPower.fromRaw(speed, newTorque, input.getDirection());
    }

    /**
     * Баланс тика: излишек мощности сети — заряд, дефицит — разряд.
     */
    @Override
    public void onNetworkTick(long receivedWatts, long childrenWatts) {
        final long delta = childrenWatts - receivedWatts;

        if (delta > 0) {
            // Покрыли дефицит из запаса (с потерями КПД)
            final double joules = (delta / 20.0) / efficiency;
            energy = Math.max(0, energy - joules);
        } else if (delta < 0) {
            // Излишек — заряжаемся
            final double joules = ((double) -delta / 20.0) * efficiency;
            energy = Math.min(capacity, energy + joules);
        }
    }
}
