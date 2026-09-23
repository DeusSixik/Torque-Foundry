package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.GearRatio;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Коробка передач: вход и выход на противоположных гранях,
 * меняет соотношение RPM/Nm по передаточному числу.
 */
public class GearboxMachine extends MechanicalMachine {

    private final GearRatio gearbox;
    private final Direction outputSide;

    public GearboxMachine(int ratio, boolean stepUp, boolean reverses,
                          Direction inputSide, Direction outputSide) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.gearbox = new GearRatio(ratio, stepUp, reverses);
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
        return outputSide == this.outputSide ? gearbox.transform(input) : input;
    }

    /**
     * КПД передачи (игровое значение класса).
     */
    @Override
    public double getEfficiency() {
        return 0.95;
    }
}