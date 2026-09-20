package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

/**
 * Узел разветвления (junction): пассивный передатчик, принимает мощность
 * с ЛЮБОЙ грани и отдаёт во ВСЕ остальные. В отличие от вала (два торца
 * по оси) — полная крестовина 6 направлений: к узлу можно подключать
 * машины со всех сторон.
 *
 * <p>Раздача работает на DAG-движке: узел питает всех соседей, принимающих
 * мощность (output->input), мёртвые ветви (за потребителем) сеть не
 * нагружают, обратные рёбра отсечены уровневой фильтрацией — зациклить
 * или удвоить мощность через узел нельзя.
 */
public class JunctionMachine extends MechanicalMachine {

    public JunctionMachine() {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        setPassive(true);
        // Все 6 граней: вход И выход одновременно.
        for (Direction dir : Direction.values()) {
            worldPort(dir, PortRole.IN_OUT);
        }
    }
}
