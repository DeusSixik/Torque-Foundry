package dev.sdm.torque_foundry.physics.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sdm.torque_foundry.api.events.bus.EventScope;
import dev.sdm.torque_foundry.api.events.physics.CycleEvent;
import dev.sdm.torque_foundry.api.events.physics.PhysicsEvents;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.TestRig;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Цикловые события пайплайна: SUBMITTED при сабмите, COMPLETED при барьере
 * следующего tick()/stop(). Оба — на серверном треде.
 */
class PipelineCycleEventsTest {

    private ExecutorService executor;
    private EventScope scope;

    @BeforeEach
    void setUp() {
        MechanicalGroupManager.clearAll();
        scope = new EventScope();
    }

    @AfterEach
    void tearDown() {
        scope.close();
        MechanicalGroupManager.clearAll();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private MechanicalGroup groupOf(int machines) {
        final MechanicalGroup group = new MechanicalGroup();
        for (int i = 0; i < machines; i++) {
            group.addElement(TestRig.shaft(new BlockPos(i, 0, 0)));
        }
        MechanicalGroupManager.register(group);
        return group;
    }

    @Test
    void submittedAndCompletedFireOncePerCycle() {
        executor = Executors.newFixedThreadPool(2);
        final PhysicsPipeline pipeline = new PhysicsPipeline(2, executor);

        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger lastGroupCount = new AtomicInteger(-1);
        scope.listen(PhysicsEvents.CYCLE_SUBMITTED, (CycleEvent e) -> {
            submitted.incrementAndGet();
        });
        scope.listen(PhysicsEvents.CYCLE_COMPLETED, (CycleEvent e) -> {
            completed.incrementAndGet();
            lastGroupCount.set(e.groupCount());
        });

        // Пустой мир: циклов нет — событий нет.
        pipeline.tick();
        assertEquals(0, submitted.get(), "пустой мир — без событий");

        // Два цикла с группами.
        final MechanicalGroup g1 = groupOf(2);
        final MechanicalGroup g2 = groupOf(3);
        pipeline.tick(); // сабмит цикла 1
        pipeline.tick(); // барьер цикла 1 (COMPLETED) + сабмит цикла 2
        pipeline.stop(); // барьер цикла 2 (COMPLETED)

        assertEquals(2, submitted.get(), "SUBMITTED на каждый цикл с группами");
        assertEquals(2, completed.get(), "COMPLETED после каждого цикла");
        assertEquals(2, lastGroupCount.get(), "в цикле участвовало 2 группы");

        pipeline.stop();
    }
}
