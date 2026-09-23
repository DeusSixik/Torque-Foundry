package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Червячный разветвитель (червячная передача): большая редукция
 * за одну ступень, направление вращения сохраняется, КПД понижен.
 */
public class WormGearMachine extends MechanicalMachine {

    private final int ratio;
    private final Direction outputSide;

    public WormGearMachine(int ratio, Direction inputSide, Direction outputSide) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.ratio = Math.max(1, ratio);
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

        // Потери КПД учитывает applyEfficiency (getEfficiency) — без дубля
        final long outSpeedRaw = input.getSpeedRaw() / ratio;
        final long outTorqueRaw = input.getTorqueRaw() * ratio;

        return RotationalPower.fromRaw(outSpeedRaw, outTorqueRaw, input.getDirection());
    }

    /**
     * КПД передачи (игровое значение класса).
     */
    @Override
    public double getEfficiency() {
        return 0.75;
    }
}