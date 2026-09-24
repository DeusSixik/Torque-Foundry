package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Раздаточная коробка: один вход, два выхода с независимыми
 * передаточными числами (мосты с разными скоростями).
 * <p>
 * {@link #transform} задаёт только кинематику ветви (отношение и знак).
 * Делёж момента входа между выходами делает решатель (фаза B3 группы):
 * вход делится по спросу выходов, а не копируется на каждый — до этого
 * каждый выход получал полную мощность входа и сеть удваивала энергию.
 * Потери — на узле целиком один раз (КПД узла), а не на каждом ребре.
 */
public class TransferCaseMachine extends MechanicalMachine {

    private final Direction outputSideA;
    private final Direction outputSideB;
    private final int ratioA;
    private final int ratioB;
    /** Скоростные отношения выходов {ratio, 1} (кэш для контуров). */
    private final long[] ratioFractionA;
    private final long[] ratioFractionB;

    public TransferCaseMachine(Direction inputSide,
                               Direction outputSideA, int ratioA,
                               Direction outputSideB, int ratioB) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.outputSideA = outputSideA;
        this.outputSideB = outputSideB;
        this.ratioA = Math.max(1, ratioA);
        this.ratioB = Math.max(1, ratioB);
        this.ratioFractionA = new long[]{this.ratioA, 1};
        this.ratioFractionB = new long[]{this.ratioB, 1};
        port(inputSide, PortRole.INPUT);
        port(outputSideA, PortRole.OUTPUT);
        port(outputSideB, PortRole.OUTPUT);
    }

    @Override
    protected void createDirections() {
        // Порты задаёт конструктор
    }

    /** Точная дробь отношения ветви (для проверки замкнутых контуров, 11.2). */
    @Override
    public long[] getOutputRatioFraction(Direction outputSide) {
        if (outputSide == outputSideA) {
            return ratioFractionA;
        }
        if (outputSide == outputSideB) {
            return ratioFractionB;
        }
        return null;
    }

    @Override
    public RotationalPower transform(RotationalPower input, Direction outputSide) {
        if (outputSide == outputSideA) {
            return RotationalPower.fromRaw(
                    input.getSpeedRaw() * ratioA,
                    input.getTorqueRaw() / ratioA,
                    input.getDirection());
        }
        if (outputSide == outputSideB) {
            return RotationalPower.fromRaw(
                    input.getSpeedRaw() * ratioB,
                    input.getTorqueRaw() / ratioB,
                    input.getDirection());
        }
        return input;
    }

    /**
     * КПД передачи (игровое значение класса).
     */
    @Override
    public double getEfficiency() {
        return 0.94;
    }

    /**
     * Зубчатая ступень: на пределе температуры зуб выкрошен — заклинивает.
     */
    @Override
    public HeatFailureMode getHeatFailureMode() {
        return HeatFailureMode.JAM;
    }
}