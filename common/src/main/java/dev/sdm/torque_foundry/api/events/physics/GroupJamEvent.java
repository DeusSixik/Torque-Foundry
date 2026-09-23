package dev.sdm.torque_foundry.api.events.physics;

import dev.sdm.torque_foundry.physics.group.MechanicalGroup;

/**
 * Payload {@link PhysicsEvents#GROUP_JAMMED}/{@link PhysicsEvents#GROUP_UNJAMMED}.
 *
 * <p>Поток-источник: воркер пула физики (см. тредовый контракт PhysicsEvents).
 * Экземпляр переиспользуется (ThreadLocal в месте firing): не хранить ссылку
 * на событие за пределами слушателя.
 */
public final class GroupJamEvent {

    private MechanicalGroup group;
    private long simTick;
    /**
     * Сколько машин в клине на момент перехода (для JAMMED).
     */
    private int jammedMachines;

    /**
     * Группа (только внутри слушателя, тредовые правила физики).
     */
    public MechanicalGroup group() {
        return group;
    }

    /**
     * Номер тика группы, на котором произошёл переход.
     */
    public long simTick() {
        return simTick;
    }

    /**
     * Число машин в клине (актуально для JAMMED).
     */
    public int jammedMachines() {
        return jammedMachines;
    }

    /* package-private */ void set(MechanicalGroup group, long simTick, int jammedMachines) {
        this.group = group;
        this.simTick = simTick;
        this.jammedMachines = jammedMachines;
    }
}
