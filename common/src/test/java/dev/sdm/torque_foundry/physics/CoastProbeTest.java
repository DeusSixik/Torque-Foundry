package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Временный пробник выбега: печатает скорость сети и машин по тикам. */
@Disabled("диагностика, запускается вручную")
class CoastProbeTest {

    @Test
    void printCoastTrace() {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        final MechanicalMachine s1 = new ShaftMachine();
        s1.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(s1);
        final MechanicalMachine s2 = new ShaftMachine();
        s2.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(s2);

        for (int t = 0; t < 100; t++) {
            group.computeTick();
        }
        group.removeElement(gen);

        for (int t = 0; t < 20; t++) {
            group.computeTick();
            System.out.printf("t=%d net=%d s1recv=%d s2recv=%d s1=%s s2=%s%n",
                    t, group.currentSpeedForTest(),
                    s1.getReceived().getSpeedRaw(), s2.getReceived().getSpeedRaw(),
                    s1.getWorkState(), s2.getWorkState());
        }
    }
}
