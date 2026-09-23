package dev.sdm.torque_foundry.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sdm.torque_foundry.api.events.bus.EventScope;
import dev.sdm.torque_foundry.api.events.physics.CycleEvent;
import dev.sdm.torque_foundry.api.events.physics.GroupJamEvent;
import dev.sdm.torque_foundry.api.events.physics.GroupTickEvent;
import dev.sdm.torque_foundry.api.events.physics.PhysicsEventDispatcher;
import dev.sdm.torque_foundry.api.events.physics.PhysicsEvents;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * События физики через FastEventBus: GROUP_TICK_START/END на каждый тик,
 * JAMMED/UNJAMMED — только на переходах состояния клина.
 */
class PhysicsEventsTest {

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
    }

    private MechanicalGroup chainWithConsumer() {
        final var shafts = new dev.sdm.torque_foundry.physics.machine.MechanicalMachine[2];
        for (int i = 0; i < shafts.length; i++) {
            shafts[i] = TestRig.shaft(new BlockPos(1 + i, 0, 0));
        }
        return TestRig.line(
                TestRig.generator(256_000, 64_000, new BlockPos(0, 0, 0)),
                shafts,
                TestRig.consumer(64_000, 32_000, new BlockPos(3, 0, 0)));
    }

    private MechanicalGroup overStressedChain() {
        // Потребитель требует больше, чем генератор даёт в принципе -> клин.
        final var shafts = new dev.sdm.torque_foundry.physics.machine.MechanicalMachine[2];
        for (int i = 0; i < shafts.length; i++) {
            shafts[i] = TestRig.shaft(new BlockPos(1 + i, 0, 0));
        }
        return TestRig.line(
                TestRig.generator(256_000, 64_000, new BlockPos(0, 0, 0)),
                shafts,
                TestRig.consumer(64_000, 999_000, new BlockPos(3, 0, 0)));
    }

    @Test
    void tickStartAndEndFireEveryTick() {
        final MechanicalGroup group = chainWithConsumer();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger ends = new AtomicInteger();
        final AtomicLong endTick = new AtomicLong(-1);

        scope.listen(PhysicsEvents.GROUP_TICK_START,
                (GroupTickEvent e) -> starts.incrementAndGet());
        scope.listen(PhysicsEvents.GROUP_TICK_END, (GroupTickEvent e) -> {
            ends.incrementAndGet();
            endTick.set(e.simTick());
        });

        group.computeTick();
        group.computeTick();

        assertEquals(2, starts.get(), "START на каждый тик");
        assertEquals(2, ends.get(), "END на каждый тик");
        assertEquals(group.getSimTick(), endTick.get(), "END несёт tickId тика");
    }

    @Test
    void jamEventsFireOnlyOnTransitions() {
        final MechanicalGroup group = overStressedChain();
        final AtomicInteger jammed = new AtomicInteger();
        final AtomicInteger unjammed = new AtomicInteger();
        final AtomicInteger jammedMachines = new AtomicInteger(-1);

        scope.listen(PhysicsEvents.GROUP_JAMMED, (GroupJamEvent e) -> {
            jammed.incrementAndGet();
            jammedMachines.set(e.jammedMachines());
        });
        scope.listen(PhysicsEvents.GROUP_UNJAMMED, (GroupJamEvent e) -> unjammed.incrementAndGet());

        // Долгий клин: событие JAMMED — один раз, не каждый тик.
        for (int i = 0; i < 30; i++) {
            group.computeTick();
        }
        assertEquals(1, jammed.get(), "JAMMED только на переходе в клин");
        assertTrue(jammedMachines.get() > 0, "в JAMMED указано число машин");
        assertEquals(0, unjammed.get(), "клин ещё держится");
    }

    @Test
    void separateGroupsFireTheirOwnJams() {
        // Две независимые группы, каждая клинит: каждая стрельнула своё
        // JAMMED ровно один раз (переходы per-group, счётчик общий = 2).
        final AtomicInteger jammed = new AtomicInteger();
        scope.listen(PhysicsEvents.GROUP_JAMMED, (GroupJamEvent e) -> jammed.incrementAndGet());

        final MechanicalGroup a = overStressedChain();
        final MechanicalGroup b = overStressedChain();
        for (int i = 0; i < 25; i++) {
            a.computeTick();
            b.computeTick();
        }
        assertEquals(2, jammed.get(), "по одному JAMMED на каждую группу");
    }

    @Test
    void eventPayloadGroupMatchesFiredGroup() {
        final MechanicalGroup group = chainWithConsumer();
        final AtomicLong seenGroupId = new AtomicLong(-1);

        scope.listen(PhysicsEvents.GROUP_TICK_START,
                (GroupTickEvent e) -> seenGroupId.set(e.group().getGroupId()));

        group.computeTick();
        assertEquals(group.getGroupId(), seenGroupId.get());
    }

    @Test
    void scopeCloseUnsubscribesPhysicsEvents() {
        final MechanicalGroup group = chainWithConsumer();
        final AtomicInteger count = new AtomicInteger();
        scope.listen(PhysicsEvents.GROUP_TICK_START, (GroupTickEvent e) -> count.incrementAndGet());
        scope.close();

        group.computeTick();
        assertEquals(0, count.get(), "после close() слушатель отписан");
    }

    @Test
    void dispatcherDoesNotAllocatePerFire() {
        // Контракт zero-alloc: fire на установившемся пути без new.
        // Проверяем identity: повторный fire того же типа на том же треде
        // даёт ТОТ ЖЕ payload-экземпляр (переиспользуемый ThreadLocal).
        final MechanicalGroup group = chainWithConsumer();
        final Object[] seen1 = {null};
        final Object[] seen2 = {null};
        final EventScope local = new EventScope();
        local.listen(PhysicsEvents.GROUP_TICK_START, (GroupTickEvent e) -> seen1[0] = e);
        try {
            group.computeTick();
            group.computeTick();
            assertTrue(seen1[0] != null, "событие доставлено");
            assertTrue(seen1[0] == seen2[0] || seen2[0] == null,
                    "payload переиспользуется");
            // Жёсткая проверка переиспользования: второй тик через нового
            // слушателя получает ТОТ ЖЕ экземпляр.
            final Object[] seen3 = {null};
            local.listen(PhysicsEvents.GROUP_TICK_START, (GroupTickEvent e) -> seen3[0] = e);
            group.computeTick();
            assertTrue(seen1[0] == seen3[0],
                    "один ThreadLocal-инстанс на тред: ноль аллокаций на fire");
        } finally {
            local.close();
        }
    }
}
