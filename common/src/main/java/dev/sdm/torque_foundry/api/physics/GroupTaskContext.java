package dev.sdm.torque_foundry.api.physics;

import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;

/**
 * Узкое окно к живой группе на время исполнения {@link GroupTask}.
 *
 * <p>Выдаётся только на время {@code execute} и валидно только внутри него:
 * после выхода из задачи контекст перепривязывается к следующей группе —
 * сохранять ссылку на контекст (или на машины из него) за пределами
 * {@code execute} запрещено.
 *
 * <p>Внутренние поля группы напрямую не открываются: доступ — через методы
 * контекста (контроль инварианта I2 «состояние трогает только владелец»
 * одной точкой входа).
 */
public final class GroupTaskContext {

    private MechanicalGroup group;

    /** Привязка контекста к группе (движок, при дренаже). */
    /* package-private */ void bind(MechanicalGroup group) {
        this.group = group;
    }

    /** Id группы. */
    public long groupId() {
        return group.getGroupId();
    }

    /** Число машин в группе. */
    public int machineCount() {
        return group.getSize();
    }

    /**
     * Машина по индексу (слот группы, стабильный между тиками
     * при неизменной топологии).
     *
     * @param index 0..machineCount()-1
     * @return машина или null вне диапазона
     */
    public MechanicalMachine machine(int index) {
        return group.getMachine(index);
    }

    /** Обороты сети, milli-RPM (последний завершённый тик). */
    public long netSpeedRaw() {
        return group.getNetSpeedRaw();
    }

    /** Счётчик физических тиков группы. */
    public long simTick() {
        return group.getSimTick();
    }
}
