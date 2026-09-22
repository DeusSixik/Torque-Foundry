package dev.sdm.torque_foundry.physics.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.TestRig;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ф3: пул воркеров пайплайна — балансировка по размеру групп и сквозной
 * тик через слоты (каждая группа тикается ровно один раз за цикл, I1).
 */
class PhysicsPipelineTest {

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        MechanicalGroupManager.clearAll();
    }

    @AfterEach
    void tearDown() {
        MechanicalGroupManager.clearAll();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Группа из n машин (размер = n) без источников: тик безопасен. */
    private static MechanicalGroup groupOf(int machines) {
        final MechanicalGroup group = new MechanicalGroup();
        for (int i = 0; i < machines; i++) {
            group.addElement(TestRig.shaft(new BlockPos(i, 0, 0)));
        }
        return group;
    }

    @Test
    void balanceDistributesBySizeGreedy() {
        final MechanicalGroup[] groups = {groupOf(10), groupOf(10), groupOf(10),
                groupOf(1), groupOf(1), groupOf(1)};
        final int slots = 2;
        final long[] loads = new long[slots];
        final int[] slotOf = new int[groups.length];

        PhysicsPipeline.balance(groups, slots, loads, slotOf);

        // Детерминизм: повторный прогон даёт ту же раскладку.
        final long[] loads2 = new long[slots];
        final int[] slotOf2 = new int[groups.length];
        PhysicsPipeline.balance(groups, slots, loads2, slotOf2);

        for (int g = 0; g < groups.length; g++) {
            assertEquals(slotOf[g], slotOf2[g], "детерминизм, группа " + g);
        }
        // Баланс: разрыв нагрузок не больше самой большой группы.
        long max = Math.max(loads[0], loads[1]);
        long min = Math.min(loads[0], loads[1]);
        assertTrue(max - min <= 10, "loads=" + loads[0] + "/" + loads[1]);
        // Все группы распределены, сумма сошлась.
        assertEquals(33, max + min);
    }

    @Test
    void balanceWithMoreSlotsThanGroupsUsesMinSlots() {
        final MechanicalGroup[] groups = {groupOf(1), groupOf(1)};
        final long[] loads = new long[4];
        final int[] slotOf = new int[2];

        PhysicsPipeline.balance(groups, 4, loads, slotOf);

        for (int g = 0; g < groups.length; g++) {
            assertTrue(slotOf[g] >= 0 && slotOf[g] < 2,
                    "слоты выше числа групп не используются");
        }
    }

    @Test
    void pipelineTicksEveryGroupExactlyOncePerCycle() throws Exception {
        executor = Executors.newFixedThreadPool(3);
        final PhysicsPipeline pipeline = new PhysicsPipeline(3, executor);

        final MechanicalGroup[] groups = new MechanicalGroup[7];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = groupOf(1 + i);
            MechanicalGroupManager.register(groups[i]);
        }

        // Семантика цикла: tick() ждёт ПРЕДЫДУЩИЙ цикл и сабмитит новый.
        // Значит N тиков + stop() (ждёт последний сабмит) = N исполненных
        // циклов; чтение счётчика — только после барьера stop().
        pipeline.tick();
        pipeline.tick();
        pipeline.stop(); // ждёт цикл, сабмитнутый вторым tick()
        for (int i = 0; i < groups.length; i++) {
            assertEquals(2, groups[i].getSimTick(),
                    "группа " + i + ": ровно один тик за цикл, два цикла (I1)");
        }

        // Ещё цикл поверх.
        pipeline.tick();
        pipeline.stop();
        for (int i = 0; i < groups.length; i++) {
            assertEquals(3, groups[i].getSimTick(), "группа " + i);
        }
    }

    @Test
    void pipelineSurvivesEmptyAndSingleGroupTicks() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        final PhysicsPipeline pipeline = new PhysicsPipeline(2, executor);

        // Пустой мир: tick не падает и не сабмитит.
        pipeline.tick();
        pipeline.tick();

        // Одна группа при 2 слотах: используется один слот.
        final MechanicalGroup group = groupOf(3);
        MechanicalGroupManager.register(group);
        pipeline.tick();
        pipeline.tick();
        assertEquals(1, group.getSimTick());

        pipeline.stop();
    }
}
