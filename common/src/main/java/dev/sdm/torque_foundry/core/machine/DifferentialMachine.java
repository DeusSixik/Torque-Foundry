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
 * <p>
 * Распределение момента делает решатель (фаза B3 группы): момент входа
 * делится между выходами по их спросу, а не копируется на каждый —
 * до этой фазы блок удваивал энергию сети и был «сломанной раздаткой»
 * (см. раздел 4 документа физики). Равенство моментов открытого
 * дифференциала (ω_in = (ω_a + ω_b)/2, T_a = T_b) — очередь С3:
 * решатель с несколькими скоростями. До неё блок не обещает
 * выравнивания моментов и работает как развилка со спросом.
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

    /**
     * КПД передачи (игровое значение класса).
     */
    @Override
    public double getEfficiency() {
        return 0.97;
    }

    /** Зубчатая ступень: на пределе температуры зуб выкрошен — заклинивает. */
    @Override
    public HeatFailureMode getHeatFailureMode() {
        return HeatFailureMode.JAM;
    }
}