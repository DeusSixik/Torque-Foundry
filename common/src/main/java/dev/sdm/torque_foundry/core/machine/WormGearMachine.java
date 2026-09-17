package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Червячный разветвитель (червячная передача): большая редукция
 * за одну ступень, направление вращения сохраняется, КПД понижен.
 */
public class WormGearMachine extends MechanicalMachine {

    private static final double EFFICIENCY = 0.8; // КПД червячной пары

    private final int ratio;
    private final Direction outputSide;

    public WormGearMachine(int ratio, Direction inputSide, Direction outputSide) {
        super(MechanicalPower.fromRaw(0, 0), (byte) -1);
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
    public MechanicalPower transform(MechanicalPower input, Direction outputSide) {
        if (outputSide != this.outputSide) {
            return input;
        }

        final long outSpeedRaw = input.getSpeedRaw() / ratio;
        final long outTorqueRaw = Math.round(
                input.getTorqueRaw() * ratio * EFFICIENCY);

        return MechanicalPower.fromRaw(outSpeedRaw, outTorqueRaw, input.getDirection());
    }
}
