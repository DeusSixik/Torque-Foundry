package dev.sdm.torque_foundry.api.events.physics;

import dev.sdm.torque_foundry.api.events.bus.FastEventBus;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;

/**
 * Диспетчер событий физики: единственное место, откуда стреляют
 * {@link PhysicsEvents} (внутренний — не для аддонов).
 *
 * <p>Zero-alloc: payload'ы переиспользуются per-thread (ThreadLocal) —
 * события стреляются каждый тик каждой группы, аллокации в тике запрещены.
 * ThreadLocal.get на установившемся пути не аллоцирует.
 *
 * <p>Firing — из потока-владельца состояния:
 * <ul>
 *   <li>GroupTick и GroupJam события — воркер пула
 *       (см. тредовый контракт событий);</li>
 *   <li>Cycle события — серверный тред из PhysicsPipeline.</li>
 * </ul>
 */
public final class PhysicsEventDispatcher {

    private static final ThreadLocal<GroupTickEvent> GROUP_TICK =
            ThreadLocal.withInitial(GroupTickEvent::new);
    private static final ThreadLocal<GroupJamEvent> GROUP_JAM =
            ThreadLocal.withInitial(GroupJamEvent::new);
    private static final ThreadLocal<CycleEvent> CYCLE =
            ThreadLocal.withInitial(CycleEvent::new);

    private PhysicsEventDispatcher() {
    }

    /**
     * [PHYSICS THREAD] Начало тика группы.
     */
    public static void fireGroupTickStart(MechanicalGroup group, long simTick) {
        final GroupTickEvent event = GROUP_TICK.get();
        event.set(group, simTick);
        FastEventBus.DEFAULT_BUS.fire(PhysicsEvents.GROUP_TICK_START.type(), event);
    }

    /**
     * [PHYSICS THREAD] Конец тика группы.
     */
    public static void fireGroupTickEnd(MechanicalGroup group, long simTick) {
        final GroupTickEvent event = GROUP_TICK.get();
        event.set(group, simTick);
        FastEventBus.DEFAULT_BUS.fire(PhysicsEvents.GROUP_TICK_END.type(), event);
    }

    /**
     * [PHYSICS THREAD] Переход не-клин -> клин.
     */
    public static void fireGroupJammed(MechanicalGroup group, long simTick, int jammedMachines) {
        final GroupJamEvent event = GROUP_JAM.get();
        event.set(group, simTick, jammedMachines);
        FastEventBus.DEFAULT_BUS.fire(PhysicsEvents.GROUP_JAMMED.type(), event);
    }

    /**
     * [PHYSICS THREAD] Переход клин -> не-клин.
     */
    public static void fireGroupUnjammed(MechanicalGroup group, long simTick) {
        final GroupJamEvent event = GROUP_JAM.get();
        event.set(group, simTick, 0);
        FastEventBus.DEFAULT_BUS.fire(PhysicsEvents.GROUP_UNJAMMED.type(), event);
    }

    /**
     * [SERVER THREAD] Цикл отправлен в пул.
     */
    public static void fireCycleSubmitted(int groupCount, int slots) {
        final CycleEvent event = CYCLE.get();
        event.set(groupCount, slots);
        FastEventBus.DEFAULT_BUS.fire(PhysicsEvents.CYCLE_SUBMITTED.type(), event);
    }

    /**
     * [SERVER THREAD] Цикл завершён (все слоты дотикали).
     */
    public static void fireCycleCompleted(int groupCount, int slots) {
        final CycleEvent event = CYCLE.get();
        event.set(groupCount, slots);
        FastEventBus.DEFAULT_BUS.fire(PhysicsEvents.CYCLE_COMPLETED.type(), event);
    }
}
