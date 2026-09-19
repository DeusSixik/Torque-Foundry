package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Пассивный передатчик: две осевые грани — одновременно INPUT и OUTPUT
 * (IN_OUT), энергия проходит насквозь в направлении, которое задаёт
 * положение источника. Перпендикулярные грани мощность не проводят.
 */
public class ShaftMachine extends MechanicalMachine {

    public ShaftMachine() {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        setPassive(true);
        applyAxisPorts();
    }

    @Override
    public void setAxis(Direction.Axis axis) {
        super.setAxis(axis);
        applyAxisPorts();
    }

    private void applyAxisPorts() {
        // Мировые порты напрямую: ось берётся из blockstate блока,
        // локальный поворот (facing) для вала не применяется
        clearWorldPorts();
        for (Direction dir : Direction.values()) {
            if (dir.getAxis() == getAxis()) {
                worldPort(dir, PortRole.IN_OUT);
            }
        }
    }
}
