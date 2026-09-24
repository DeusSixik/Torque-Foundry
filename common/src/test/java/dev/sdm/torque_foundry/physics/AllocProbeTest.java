package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

/**
 * Диагностика аллокаций тика (не регрессия): печатает байты на тик,
 * измеренные ThreadMXBean — без шумов JMH. Запускается вручную при
 * подозрениях на нарушение zero-alloc; в CI показателен выводом в stdout.
 */
class AllocProbeTest {

    @Test
    void printAllocatedBytesPerTick() {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        for (int i = 1; i <= 8; i++) {
            final MechanicalMachine shaft = new ShaftMachine();
            shaft.setBlockPos(new BlockPos(i, 0, 0));
            group.addElement(shaft);
        }
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 32_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(9, 0, 0));
        group.addElement(consumer);

        // Прогрев: рост буферов, JIT, ThreadLocal — вне измерения
        for (int i = 0; i < 600; i++) {
            group.computeTick();
        }

        final com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long threadId = Thread.currentThread().getId();
        final long before = bean.getThreadAllocatedBytes(threadId);
        final int ticks = 1000;
        for (int i = 0; i < ticks; i++) {
            group.computeTick();
        }
        final long after = bean.getThreadAllocatedBytes(threadId);

        System.out.println("ALLOC_PER_TICK_BYTES = " + ((after - before) / ticks));
    }
}
