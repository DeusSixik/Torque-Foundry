package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import dev.sdm.torque_foundry.physics.RotationDirection;
import net.minecraft.core.Direction;

/**
 * Планетарный механизм: три звена (солнце/водило/корона), работаем
 * как редуктор с фиксированной блокировкой одного из звеньев.
 * <p>
 * CARRIER_OUT: солнце вход, корона заблокирована, водило выход.
 *              out = in * Zs / (Zs + Zr), направление сохраняется.
 * RING_OUT:    солнце вход, водило заблокировано, корона выход.
 *              out = in * Zs / Zr, направление НАОБОРОТ.
 * SUN_OUT:     корона вход, водило заблокировано, солнце выход.
 *              out = in * Zr / Zs, направление сохраняется.
 */
public class PlanetaryGearMachine extends MechanicalMachine {

    public enum PlanetaryMode {
        CARRIER_OUT,
        RING_OUT,
        SUN_OUT
    }

    private final PlanetaryMode mode;
    private final double factor;        // множитель оборотов (может быть < 1)
    private final boolean reverses;
    private final Direction outputSide;
    /** Скоростное отношение выхода как точная дробь (кэш для контуров). */
    private final long[] ratioFraction;

    public PlanetaryGearMachine(PlanetaryMode mode, int teethSun, int teethRing,
                                Direction inputSide, Direction outputSide) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.mode = mode;
        this.outputSide = outputSide;

        long ratioNum = 1;
        long ratioDen = 1;
        switch (mode) {
            case CARRIER_OUT -> {
                this.factor = (double) teethSun / (teethSun + teethRing);
                this.reverses = false;
                ratioNum = teethSun;
                ratioDen = teethSun + teethRing;
            }
            case RING_OUT -> {
                this.factor = (double) teethSun / teethRing;
                this.reverses = true;
                ratioNum = teethSun;
                ratioDen = teethRing;
            }
            case SUN_OUT -> {
                this.factor = (double) teethRing / teethSun;
                this.reverses = false;
                ratioNum = teethRing;
                ratioDen = teethSun;
            }
            default -> {
                this.factor = 1.0;
                this.reverses = false;
            }
        }
        // Сокращение дроби зубьев (например, Zs/(Zs+Zr) = 20/60 -> 1/3)
        final long g = gcd(ratioNum, ratioDen);
        this.ratioFraction = new long[]{ratioNum / g, ratioDen / g};

        port(inputSide, PortRole.INPUT);
        port(outputSide, PortRole.OUTPUT);
    }

    /** НОД для сокращения дроби зубьев. */
    private static long gcd(long a, long b) {
        while (b != 0) {
            final long t = a % b;
            a = b;
            b = t;
        }
        return Math.max(1, a);
    }

    /** Точная дробь отношения (для проверки замкнутых контуров, раздел 11.2). */
    @Override
    public long[] getOutputRatioFraction(Direction outputSide) {
        return outputSide == this.outputSide ? ratioFraction : null;
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

        // Сохранение мощности: обороты × factor, момент / factor
        final long outSpeedRaw = Math.round(input.getSpeedRaw() * factor);
        final long outTorqueRaw = factor != 0
                ? Math.round(input.getTorqueRaw() / factor)
                : input.getTorqueRaw();
        final byte dir = reverses
                ? RotationDirection.opposite(input.getDirection())
                : input.getDirection();

        return RotationalPower.fromRaw(outSpeedRaw, outTorqueRaw, dir);
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