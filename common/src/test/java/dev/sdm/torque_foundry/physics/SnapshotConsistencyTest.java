package dev.sdm.torque_foundry.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sdm.torque_foundry.api.physics.GroupSnapshotView;
import dev.sdm.torque_foundry.api.physics.PhysicsReads;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.simulation.GroupSnapshot;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Ф1 протокола снапшотов (Concurrency in Torque Foundry.md, §4):
 * согласованность среза по tickId, корректность контрольной суммы,
 * устойчивость под конкурентной публикацией/чтением.
 */
class SnapshotConsistencyTest {

    /** Поколение: генератор + 4 вала + потребитель, обычная тестовая цепь. */
    private static MechanicalGroup chain() {
        final dev.sdm.torque_foundry.physics.machine.MechanicalMachine[] shafts =
                new dev.sdm.torque_foundry.physics.machine.MechanicalMachine[4];
        for (int i = 0; i < shafts.length; i++) {
            shafts[i] = TestRig.shaft(new net.minecraft.core.BlockPos(1 + i, 0, 0));
        }
        final MechanicalGroup group = TestRig.line(
                TestRig.generator(256_000, 64_000,
                        new net.minecraft.core.BlockPos(0, 0, 0)),
                shafts,
                TestRig.consumer(64_000, 32_000,
                        new net.minecraft.core.BlockPos(5, 0, 0)));
        TestRig.settle(group, TestRig.SETTLE_TICKS);
        // Чтение идёт через PhysicsReads -> группу надо зарегистрировать
        // в менеджере (игровой путь регистрации — createOrAdd на BE).
        dev.sdm.torque_foundry.core.data.MechanicalGroupManager.register(group);
        return group;
    }

    @Test
    void snapshotMatchesLiveStateAfterSettle() {
        final MechanicalGroup group = chain();
        final GroupSnapshotView view = new GroupSnapshotView();

        assertTrue(PhysicsReads.copySnapshot(group.getGroupId(), view),
                "группа должна читаться по id");
        assertTrue(view.tickId > 0, "после settle тики уже публиковались");
        assertEquals(group.getSimTick(), view.tickId);
        assertEquals(group.getNetSpeedRaw(), view.netSpeedRaw);
        assertEquals(group.getSize(), view.machineCount);

        // Межпольная согласованность среза = контрольная сумма
        assertTrue(GroupSnapshot.verifyChecksum(view),
                "контрольная сумма среза должна сходиться");

        // Состояния/скорости живых машин того же тика = срезу
        for (int i = 0; i < view.machineCount; i++) {
            final var machine = group.getMachine(i);
            assertEquals(machine.getWorkState(), view.states[i], "machine " + i);
            assertEquals(machine.getReceived().getSpeedRaw(),
                    view.receivedSpeedRaw[i], "machine " + i);
        }
    }

    @Test
    void tryCopyFailsWhenLockHeld() throws Exception {
        final MechanicalGroup group = chain();
        final GroupSnapshotView view = new GroupSnapshotView();

        // Чужой тред держит замок снапшота: tryCopy обязан отказаться,
        // view остаётся нетронутым (reader работает с прошлым кадром).
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> {
            group.holdSnapshotLockForTest(locked, release);
        });
        holder.start();
        locked.await();

        final long staleTick = -42;
        view.tickId = staleTick;
        final boolean copied = PhysicsReads.tryCopySnapshot(group.getGroupId(), view);
        release.countDown();
        holder.join(1000);

        assertTrue(!copied, "при занятом замке tryCopySnapshot = false");
        assertEquals(staleTick, view.tickId, "view не должен быть тронут при отказе");
    }

    @Test
    void concurrentPublishAndReadStaysConsistent() throws Exception {
        final MechanicalGroup group = chain();
        final GroupSnapshotView view = new GroupSnapshotView();
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong reads = new AtomicLong();
        final CountDownLatch readersDone = new CountDownLatch(2);

        // Два читателя: непрерывно копируют срез и сверяют контрольную сумму.
        final Runnable reader = () -> {
            final GroupSnapshotView local = new GroupSnapshotView();
            long lastTick = -1;
            while (!stop.get()) {
                if (PhysicsReads.tryCopySnapshot(group.getGroupId(), local)) {
                    assertTrue(GroupSnapshot.verifyChecksum(local),
                            "рваный срез: чексумма не сошлась");
                    assertTrue(local.tickId >= lastTick,
                            "tickId не может откатываться назад");
                    lastTick = local.tickId;
                    reads.incrementAndGet();
                }
            }
            readersDone.countDown();
        };
        final Thread r1 = new Thread(reader);
        final Thread r2 = new Thread(reader);
        r1.start();
        r2.start();

        // Писатель: крутит физику, каждая публикация под замком.
        // Останавливаемся, когда читатели гарантированно прочитали серию
        // срезов (или по лимиту тиков — страховка от зависания).
        int ticks = 0;
        while (reads.get() < 1000 && ticks < 50_000) {
            group.computeTick();
            ticks++;
        }
        stop.set(true);
        readersDone.await();
        r1.join(1000);
        r2.join(1000);

        assertTrue(reads.get() > 0, "читатели должны были прочитать срезы");
        // Финальный срез — последнего тика.
        assertTrue(PhysicsReads.copySnapshot(group.getGroupId(), view));
        assertEquals(group.getSimTick(), view.tickId);
        assertTrue(GroupSnapshot.verifyChecksum(view));
    }
}
