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

    public PlanetaryGearMachine(PlanetaryMode mode, int teethSun, int teethRing,
                                Direction inputSide, Direction outputSide) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.mode = mode;
        this.outputSide = outputSide;

        switch (mode) {
            case CARRIER_OUT -> {
                this.factor = (double) teethSun / (teethSun + teethRing);
                this.reverses = false;
            }
            case RING_OUT -> {
                this.factor = (double) teethSun / teethRing;
                this.reverses = true;
            }
            case SUN_OUT -> {
                this.factor = (double) teethRing / teethSun;
                this.reverses = false;
            }
            default -> {
                this.factor = 1.0;
                this.reverses = false;
            }
        }

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
}
