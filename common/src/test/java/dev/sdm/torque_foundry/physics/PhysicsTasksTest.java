package dev.sdm.torque_foundry.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sdm.torque_foundry.api.physics.GroupTask;
import dev.sdm.torque_foundry.api.physics.GroupTaskContext;
import dev.sdm.torque_foundry.api.physics.PhysicsTasks;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Ф2 mailbox («Concurrency in Torque Foundry.md», §5): post с любого
 * треда, дренаж серверным тредом, порядок FIFO, отбрасывание задач
 * исчезнувших групп, изоляция исключений в задаче.
 */
class PhysicsTasksTest {

    private static MechanicalGroup registeredChain() {
        final var shafts = new dev.sdm.torque_foundry.physics.machine.MechanicalMachine[2];
        for (int i = 0; i < shafts.length; i++) {
            shafts[i] = TestRig.shaft(new BlockPos(1 + i, 0, 0));
        }
        final MechanicalGroup group = TestRig.line(
                TestRig.generator(256_000, 64_000, new BlockPos(0, 0, 0)),
                shafts,
                TestRig.consumer(64_000, 32_000, new BlockPos(3, 0, 0)));
        MechanicalGroupManager.register(group);
        return group;
    }

    @AfterEach
    void drainRest() {
        // Чистим очередь между тестами, чтобы счётчики не перетекали.
        PhysicsTasks.drainPending();
    }

    @Test
    void taskExecutesAgainstGroupWithSnapshotData() {
        final MechanicalGroup group = registeredChain();
        group.computeTick(); // чтобы был опубликованный tickId/netSpeed

        final AtomicLong seenGroupId = new AtomicLong(-1);
        final AtomicLong seenNetSpeed = new AtomicLong(-1);
        final AtomicLong seenSimTick = new AtomicLong(-1);

        PhysicsTasks.post(group.getGroupId(), ctx -> {
            seenGroupId.set(ctx.groupId());
            seenNetSpeed.set(ctx.netSpeedRaw());
            seenSimTick.set(ctx.simTick());
            // Мутация через контекст: вал получает перекос.
            ctx.machine(1).setMisalignmentDeg(5.0);
        });

        assertEquals(1, PhysicsTasks.drainPending());

        assertEquals(group.getGroupId(), seenGroupId.get());
        assertEquals(group.getNetSpeedRaw(), seenNetSpeed.get());
        assertEquals(group.getSimTick(), seenSimTick.get());
        // Мутация легла в живую машину группы.
        assertEquals(5.0, group.getMachine(1).getMisalignmentDeg(), 1e-9);
    }

    @Test
    void tasksExecuteInPostOrder() {
        final MechanicalGroup group = registeredChain();

        final StringBuilder order = new StringBuilder();
        PhysicsTasks.post(group.getGroupId(), ctx -> order.append('a'));
        PhysicsTasks.post(group.getGroupId(), ctx -> order.append('b'));
        PhysicsTasks.post(group.getGroupId(), ctx -> order.append('c'));

        assertEquals(3, PhysicsTasks.drainPending());
        assertEquals("abc", order.toString(), "FIFO дренажа");
    }

    @Test
    void unknownGroupDroppedWithoutThrowing() {
        final long before = PhysicsTasks.droppedCount();
        PhysicsTasks.post(-987_654_321L, ctx -> {
            throw new IllegalStateException("не должно исполниться");
        });

        assertEquals(0, PhysicsTasks.drainPending());
        assertEquals(before + 1, PhysicsTasks.droppedCount());
    }

    @Test
    void throwingTaskDoesNotKillDrain() {
        final MechanicalGroup group = registeredChain();
        final AtomicLong executed = new AtomicLong();

        PhysicsTasks.post(group.getGroupId(), ctx -> {
            throw new RuntimeException("баг в задаче аддона");
        });
        PhysicsTasks.post(group.getGroupId(), ctx -> executed.incrementAndGet());

        assertEquals(1, PhysicsTasks.drainPending());
        assertEquals(1, executed.get());
        assertTrue(PhysicsTasks.droppedCount() > 0);
    }

    @Test
    void contextBindsEachGroupFresh() {
        final MechanicalGroup g1 = registeredChain();
        final MechanicalGroup g2 = new MechanicalGroup();
        final var shaft = TestRig.shaft(new BlockPos(0, 0, 0));
        g2.addElement(shaft);
        MechanicalGroupManager.register(g2);

        final AtomicLong first = new AtomicLong(-1);
        final AtomicLong second = new AtomicLong(-1);
        final GroupTask both = ctx -> {
            if (first.compareAndSet(-1, ctx.groupId())) {
                return;
            }
            second.compareAndSet(-1, ctx.groupId());
        };
        PhysicsTasks.post(g1.getGroupId(), both);
        PhysicsTasks.post(g2.getGroupId(), both);

        assertEquals(2, PhysicsTasks.drainPending());
        assertEquals(g1.getGroupId(), first.get());
        assertEquals(g2.getGroupId(), second.get());
    }
}
