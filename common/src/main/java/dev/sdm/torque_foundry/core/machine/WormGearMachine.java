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
    /** Скоростное отношение выхода {1, ratio} — сильное понижение. */
    private final long[] ratioFraction;

    public WormGearMachine(int ratio, Direction inputSide, Direction outputSide) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.ratio = Math.max(1, ratio);
        this.outputSide = outputSide;
        this.ratioFraction = new long[]{1, this.ratio};
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

        // Потери КПД учитывает решатель на узле (фаза B3, getEfficiency) — без дубля
        final long outSpeedRaw = input.getSpeedRaw() / ratio;
        final long outTorqueRaw = input.getTorqueRaw() * ratio;

        return RotationalPower.fromRaw(outSpeedRaw, outTorqueRaw, input.getDirection());
    }

    /** Точная дробь отношения (для проверки замкнутых контуров, раздел 11.2). */
    @Override
    public long[] getOutputRatioFraction(Direction outputSide) {
        return outputSide == this.outputSide ? ratioFraction : null;
    }

    /**
     * КПД передачи (игровое значение класса).
     */
    @Override
    public double getEfficiency() {
        return 0.75;
    }

    /**
     * Зубчатая ступень: на пределе температуры зуб выкрошен — заклинивает.
     */
    @Override
    public HeatFailureMode getHeatFailureMode() {
        return HeatFailureMode.JAM;
    }
}