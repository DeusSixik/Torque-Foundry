package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.basic.MechanicalGearbox;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Коробка передач: вход и выход на противоположных гранях,
 * меняет соотношение RPM/Nm по передаточному числу.
 */
public class GearboxMachine extends MechanicalMachine {

    private final MechanicalGearbox gearbox;
    private final Direction outputSide;

    public GearboxMachine(int ratio, boolean stepUp, boolean reverses,
                          Direction inputSide, Direction outputSide) {
        super(MechanicalPower.fromRaw(0, 0), (byte) -1);
        this.gearbox = new MechanicalGearbox(ratio, stepUp, reverses);
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
        return outputSide == this.outputSide ? gearbox.transform(input) : input;
    }
}
