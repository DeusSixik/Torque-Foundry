package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Механический дифференциал (упрощённая версия v1):
 * один вход, два выхода с одинаковыми оборотами входа;
 * на одной из сторон направление вращения обратное.
 *
 * Распределение потока между выходами в текущей DAG-модели естественное:
 * каждый выход транслирует всю мощность входа, а потребители берут
 * столько, сколько им нужно (см. фазу B — расчёт demand снизу вверх).
 * Динамическое перераспределение по нагрузке — будущая версия (решатель).
 */
public class DifferentialMachine extends MechanicalMachine {

    private final Direction outputSideA;
    private final Direction outputSideB;
    private final boolean reverseSideB;

    public DifferentialMachine(Direction inputSide,
                               Direction outputSideA, Direction outputSideB,
                               boolean reverseSideB) {
        super(MechanicalPower.fromRaw(0, 0), (byte) -1);
        this.outputSideA = outputSideA;
        this.outputSideB = outputSideB;
        this.reverseSideB = reverseSideB;
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
            return input;
        }
        if (outputSide == outputSideB && reverseSideB) {
            return MechanicalPower.fromRaw(
                    input.getSpeedRaw(),
                    input.getTorqueRaw(),
                    dev.sdm.torque_foundry.physics.basic.RotationDirection.opposite(input.getDirection()));
        }
        return input;
    }
}
