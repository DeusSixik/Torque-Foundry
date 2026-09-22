package dev.sdm.torque_foundry.api.physics;

import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Публичный фасад MAILBOX-задач групп («Concurrency in Torque Foundry.md»,
 * §5, §8.2). Мутация чужой группы — только через сюда; чтение — через
 * {@link PhysicsReads}.
 *
 * <p>Очередь — глобальный lock-free MPMC ({@link ConcurrentLinkedQueue}):
 * {@code post} с любого треда без блокировок, дренаж — одним потребителем
 * (серверный тред, после барьера тика). Заявки редки, аллокация узла
 * очереди на post — осознанный компромисс (заявки не бывают в hot path).
 *
 * <p>Дренаж исполняет задачи в порядке поступления (FIFO) на серверном
 * треде — детерминизм. Задача для исчезнувшей группы молча отбрасывается
 * (счётчик {@link #droppedCount()} для диагностики).
 */
public final class PhysicsTasks {

    private record Pending(long groupId, GroupTask task) {
    }

    private static final Queue<Pending> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final GroupTaskContext CONTEXT = new GroupTaskContext();

    private PhysicsTasks() {
    }

    /**
     * Поставить задачу группе. Исполнится владельцем группы на серверном
     * треде в ближайшем дренаже (лаг — 1 тик, см. GroupTask).
     *
     * @param groupId id группы-получателя
     * @param task задача (не null)
     */
    public static void post(long groupId, GroupTask task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        QUEUE.add(new Pending(groupId, task));
    }

    /**
     * Дренаж очереди: исполнить все накопленные задачи. Вызывается ТОЛЬКО
     * серверным тредом (конвейер физики после завершения тика, до
     * пробуждения воркеров) — инвариант «один потребитель».
     *
     * <p>Задачи, поставленные во время этого дренажа, останутся в очереди
     * до следующего тика; сами задачи постить запрещены — дренаж не
     * порождает заявок.
     *
     * @return число исполненных задач
     */
    public static int drainPending() {
        if (QUEUE.isEmpty()) {
            return 0;
        }
        int executed = 0;
        final Iterator<Pending> it = QUEUE.iterator();
        while (it.hasNext()) {
            final Pending pending = it.next();
            it.remove();
            final var group = MechanicalGroupManager.getGroup(pending.groupId());
            if (group == null) {
                // Группа исчезла (распад/выгрузка) между post и дренажом.
                DROPPED.incrementAndGet();
                continue;
            }
            CONTEXT.bind(group);
            try {
                pending.task().execute(CONTEXT);
                executed++;
            } catch (RuntimeException e) {
                // Баг в задаче не должен ронять серверный тик.
                DROPPED.incrementAndGet();
                TorqueFoundry.LOGGER.error(
                        "GroupTask failed for group {}", pending.groupId(), e);
            } finally {
                CONTEXT.bind(null);
            }
        }
        return executed;
    }

    /** Сколько задач отброшено (группа исчезла / исключение в execute). */
    public static long droppedCount() {
        return DROPPED.get();
    }

    /** Число заявок в очереди (диагностика/тесты). */
    public static int pendingCount() {
        return QUEUE.size();
    }
}
