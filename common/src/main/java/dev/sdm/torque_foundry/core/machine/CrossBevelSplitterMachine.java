package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import dev.sdm.torque_foundry.physics.RotationDirection;
import net.minecraft.core.Direction;

/**
 * Многовальный конический редуктор-разветвитель (крестовая коническая
 * раздатка): один вход и от 1 до N конических выходов (по числу свободных
 * граней — обычно до 3, получаеся "крест").
 *
 * Каждый выход независим: своё передаточное число по зубцам
 * (teethIn/teethOut) и свой флаг реверса — коническая пара может
 * крутить в любую сторону в зависимости от нарезки зубьев.
 *
 * Пример (вход с запада, крест из трёх выходов):
 * <pre>
 *   new CrossBevelSplitterMachine(Direction.WEST,
 *       new BevelOutput(Direction.EAST,  1, 2, false),  // x0.5 скорости, x2 момента
 *       new BevelOutput(Direction.UP,    1, 1, true),   // 1:1, реверс, вверх
 *       new BevelOutput(Direction.NORTH, 2, 1, false)); // x2 скорости
 * </pre>
 */
public class CrossBevelSplitterMachine extends MechanicalMachine {

    /**
     * Описание одного конического выхода.
     *
     * @param side     грань выхода
     * @param teethIn  зубцы на стороне входа (общая шестерня)
     * @param teethOut зубцы на стороне выхода
     * @param reverses направление вращения выхода (нарезка зубьев)
     */
    public record BevelOutput(Direction side, int teethIn, int teethOut, boolean reverses) {
    }

    private final BevelOutput[] outputs;

    public CrossBevelSplitterMachine(Direction inputSide, BevelOutput... outputs) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.outputs = outputs != null ? outputs.clone() : new BevelOutput[0];

        port(inputSide, PortRole.INPUT);
        for (BevelOutput output : this.outputs) {
            port(output.side(), PortRole.OUTPUT);
        }
    }

    @Override
    protected void createDirections() {
        // Порты задаёт конструктор
    }

    public int getOutputCount() {
        return outputs.length;
    }

    @Override
    public RotationalPower transform(RotationalPower input, Direction outputSide) {
        for (BevelOutput output : outputs) {
            if (output.side() == outputSide) {
                final int ti = Math.max(1, output.teethIn());
                final int to = Math.max(1, output.teethOut());

                final long outSpeedRaw = input.getSpeedRaw() * ti / to;
                final long outTorqueRaw = input.getTorqueRaw() * to / ti;
                final byte dir = output.reverses()
                        ? RotationDirection.opposite(input.getDirection())
                        : input.getDirection();

                return RotationalPower.fromRaw(outSpeedRaw, outTorqueRaw, dir);
            }
        }

        return input;
    }
}
