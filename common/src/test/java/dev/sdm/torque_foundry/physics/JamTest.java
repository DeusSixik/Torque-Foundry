package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.hook.PhysicsHook;
import dev.sdm.torque_foundry.physics.hook.PhysicsHooks;
import dev.sdm.torque_foundry.physics.simulation.SimulationContext;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Заклинивание: перегруженный потребитель клинит всю цепь
 * до источников, клин держится, хук onJam находит производителей.
 */
public class JamTest {

    private static final long GEN_SPEED = 256_000;
    private static final long GEN_TORQUE = 64_000;

    private final List<MechanicalMachine> allMachines = new ArrayList<>();
    private final List<MechanicalMachine> hookJammed = new ArrayList<>();
    private final List<MechanicalMachine> hookProducers = new ArrayList<>();
    private int jamCallCount = 0;

    private final PhysicsHook recorder = new PhysicsHook() {
        @Override
        public void onJam(MechanicalGroup group, List<MechanicalMachine> jammed,
                          List<MechanicalMachine> producers, SimulationContext context) {
            jamCallCount++;
            hookJammed.addAll(jammed);
            hookProducers.addAll(producers);
        }
    };

    @BeforeEach
    void setUp() {
        allMachines.clear();
        hookJammed.clear();
        hookProducers.clear();
        jamCallCount = 0;
        dev.sdm.torque_foundry.physics.hook.PhysicsHooks.register(recorder);
    }

    @AfterEach
    void tearDown() {
        dev.sdm.torque_foundry.physics.hook.PhysicsHooks.unregister(recorder);
    }

    /** Генератор(64 Nm) -> 2 вала -> Потребитель(128 Nm) = перегруз x2. */
    private MechanicalGroup overloadedChain() {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        allMachines.add(gen);

        for (int i = 1; i <= 2; i++) {
            final MechanicalMachine shaft = new ShaftMachine();
            shaft.setBlockPos(new BlockPos(i, 0, 0));
            group.addElement(shaft);
            allMachines.add(shaft);
        }

        final MechanicalMachine consumer = new ConsumerMachine(64_000, 128_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(3, 0, 0));
        group.addElement(consumer);
        allMachines.add(consumer);

        return group;
    }

    @Test
    void overloadJamsWholeChain() {
        final MechanicalGroup group = overloadedChain();

        // Сеть раскручивается без нагрузки (потребитель грузит только
        // при оборотах >= его требуемых 64 RPM). Когда обороты досигают
        // 64 RPM — потребитель включается, перегружает, клинит всю цепь.
        boolean jamObserved = false;
        for (int t = 0; t < 500 && !jamObserved; t++) {
            group.computeTick();
            jamObserved = allMachines.stream().allMatch(
                    m -> m.getWorkState() == WorkState.JAMMED);
        }

        assertTrue(jamObserved, "overload must jam the whole chain");
        assertTrue(jamCallCount >= 1, "onJam hook must fire");
        assertEquals(4, hookJammed.size(), "4 machines must be in jam list");
    }

    @Test
    void jamStickyWhileOverloaded() {
        final MechanicalGroup group = overloadedChain();

        // Крутим пока не заклинит
        boolean jamObserved = false;
        for (int t = 0; t < 500 && !jamObserved; t++) {
            group.computeTick();
            jamObserved = allMachines.stream().allMatch(
                    m -> m.getWorkState() == WorkState.JAMMED);
        }
        assertTrue(jamObserved, "chain must jam");

        // Клин держится: заклинивший потребитель продолжает давить
        for (int t = 0; t < 50; t++) {
            group.computeTick();
            for (MechanicalMachine m : allMachines) {
                assertEquals(WorkState.JAMMED, m.getWorkState(), "jam must be sticky");
            }
        }
    }
}
