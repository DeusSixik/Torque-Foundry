package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Раздаточная коробка: один вход, два выхода с независимыми
 * передаточными числами (мосты с разными скоростями).
 */
public class TransferCaseMachine extends MechanicalMachine {

    private final Direction outputSideA;
    private final Direction outputSideB;
    private final int ratioA;
    private final int ratioB;

    public TransferCaseMachine(Direction inputSide,
                               Direction outputSideA, int ratioA,
                               Direction outputSideB, int ratioB) {
        super(MechanicalPower.fromRaw(0, 0), (byte) -1);
        this.outputSideA = outputSideA;
        this.outputSideB = outputSideB;
        this.ratioA = Math.max(1, ratioA);
        this.ratioB = Math.max(1, ratioB);
        port(inputSide, PortRole.INPUT);
        port(outputSideA, PortRole.OUTPUT);
        port(outputSideB, PortRole.OUTPUT);
    }

    @Override
    protected void createDirections() {
        // Порты задаёт конструктор
    }

    @Override
    public MechanicalPower transform(MechanicalPower input, Direction outputSide) {
        if (outputSide == outputSideA) {
            return MechanicalPower.fromRaw(
                    input.getSpeedRaw() * ratioA,
                    input.getTorqueRaw() / ratioA,
                    input.getDirection());
        }
        if (outputSide == outputSideB) {
            return MechanicalPower.fromRaw(
                    input.getSpeedRaw() * ratioB,
                    input.getTorqueRaw() / ratioB,
                    input.getDirection());
        }
        return input;
    }
}
