package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import dev.sdm.torque_foundry.physics.RotationDirection;
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
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
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
    public RotationalPower transform(RotationalPower input, Direction outputSide) {
        if (outputSide == outputSideA) {
            return input;
        }
        if (outputSide == outputSideB && reverseSideB) {
            return RotationalPower.fromRaw(
                    input.getSpeedRaw(),
                    input.getTorqueRaw(),
                    RotationDirection.opposite(input.getDirection()));
        }
        return input;
    }
}
