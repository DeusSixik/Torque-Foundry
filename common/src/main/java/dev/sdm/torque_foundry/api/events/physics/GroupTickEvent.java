package dev.sdm.torque_foundry.api.events.physics;

import dev.sdm.torque_foundry.physics.group.MechanicalGroup;

/**
 * Payload {@link PhysicsEvents#GROUP_TICK_START}/{@link PhysicsEvents#GROUP_TICK_END}.
 *
 * <p>Поток-источник: воркер пула физики (см. тредовый контракт PhysicsEvents).
 * Экземпляр переиспользуется (ThreadLocal в месте firing): НЕ хранить ссылку
 * на событие за пределами слушателя — поля перезапишутся следующим тиком.
 * Ссылка на {@link MechanicalGroup} валидна только внутри слушателя и
 * подчиняется инвариантам физического треда (I1–I3).
 */
public final class GroupTickEvent {

    private MechanicalGroup group;
    private long simTick;

    /**
     * Группа, чей тик стартует/завершён (только внутри слушателя).
     */
    public MechanicalGroup group() {
        return group;
    }

    /**
     * Номер тика группы (растёт на 1 за тик).
     */
    public long simTick() {
        return simTick;
    }

    /**
     * Заполнение переиспользуемого экземпляра (место firing).
     */
    /* package-private */ void set(MechanicalGroup group, long simTick) {
        this.group = group;
        this.simTick = simTick;
    }
}
