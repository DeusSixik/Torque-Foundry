package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Ременная передача: шкивы разного диаметра, даёт передаточное число
 * и фиксированное проскальзывание (потерю момента).
 */
public class BeltDriveMachine extends MechanicalMachine {

    private final int ratio;      // out speed = in speed * ratio (по шагу ремня)
    private final double slip;    // 0..1, доля потерь момента
    private final Direction outputSide;

    public BeltDriveMachine(int ratio, double slip, Direction inputSide, Direction outputSide) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.ratio = Math.max(1, ratio);
        this.slip = Math.max(0.0, Math.min(1.0, slip));
        this.outputSide = outputSide;
        port(inputSide, PortRole.INPUT);
        port(outputSide, PortRole.OUTPUT);
    }

    @Override
    protected void createDirections() {
        // Порты задаёт конструктор
    }

    @Override
    public RotationalPower transform(RotationalPower input, Direction outputSide) {
        if (outputSide != this.outputSide) {
            return input;
        }

        final long outSpeedRaw = input.getSpeedRaw() * ratio;
        final long outTorqueRaw = Math.round(
                input.getTorqueRaw() / (double) ratio * (1.0 - slip));

        return RotationalPower.fromRaw(outSpeedRaw, outTorqueRaw, input.getDirection());
    }

    /**
     * КПД передачи (игровое значение класса).
     */
    @Override
    public double getEfficiency() {
        return 0.92;
    }
}